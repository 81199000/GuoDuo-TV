#!/usr/bin/env python3
"""把 GuoDuo TV（安卓电视版）的安装包与清单同步到 Cloudflare R2（dl.oneduo.app），
手机版「我的 → 安装到电视」读清单、下载 APK 推给电视。

  python3 scripts/sync_tv_r2.py /path/to/guoduo-tv.apk [--notes "更新说明"]

流程：读 APK 的包名 / 版本（aapt2）→ 校验是正式签名（不是 debug）→ 算 SHA256 →
上传 guoduo/tv/v<版本>/guoduo-tv-<版本>.apk（不可变缓存）→ 写 docs/tv/latest.json（入库记录）→
上传 guoduo/tv/latest.json → 用公网地址回读校验 SHA256。

R2 布局（与 scripts/sync_r2.py 同一个桶）：
  guoduo/tv/latest.json                    清单 {version, versionCode, url, sha256, size, notes}
  guoduo/tv/v<版本>/guoduo-tv-<版本>.apk    安装包
凭据（不入库）：ONEDUO_CF_TOKEN 或 ~/.config/oneduo/cf_token。
正式签名 keystore 不入库，丢了就没法再更新已装用户，务必备份。
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.request

ACCOUNT = os.environ.get("ONEDUO_CF_ACCOUNT") or (pathlib.Path.home() / ".config/oneduo/cf_account").read_text().strip() \
    if (os.environ.get("ONEDUO_CF_ACCOUNT") or (pathlib.Path.home() / ".config/oneduo/cf_account").exists()) \
    else sys.exit("没有 Cloudflare 账号 ID（ONEDUO_CF_ACCOUNT 或 ~/.config/oneduo/cf_account）")
BUCKET = os.environ.get("ONEDUO_R2_BUCKET", "oneduo-dl")
PUBLIC = "https://dl.oneduo.app"
API = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/r2/buckets/{BUCKET}/objects/"
ROOT = pathlib.Path(__file__).resolve().parents[1]  # guoduo-tv 仓库根
PACKAGE = "me.rrmm.guoduo.tv"
SDK = pathlib.Path(os.environ.get("ANDROID_SDK_ROOT", "/opt/homebrew/share/android-commandlinetools"))
JAVA_HOME = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
UA = "GuoDuo-sync/1"


def token():
    t = os.environ.get("ONEDUO_CF_TOKEN")
    f = pathlib.Path.home() / ".config/oneduo/cf_token"
    if not t and f.exists():
        t = f.read_text().strip()
    if not t:
        sys.exit("没有 Cloudflare token（ONEDUO_CF_TOKEN 或 ~/.config/oneduo/cf_token）")
    return t


def build_tools():
    d = SDK / "build-tools" / "35.0.0"
    if not d.exists():
        sys.exit(f"找不到 Android build-tools：{d}")
    return d


def apk_info(apk: pathlib.Path):
    out = subprocess.run([str(build_tools() / "aapt2"), "dump", "badging", str(apk)],
                         capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    if not m:
        sys.exit("读不出 APK 的包名/版本")
    return m.group(1), int(m.group(2)), m.group(3)


def signer_dn(apk: pathlib.Path):
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:" + os.environ["PATH"])
    r = subprocess.run([str(build_tools() / "apksigner"), "verify", "--print-certs", str(apk)],
                       capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit("APK 签名校验失败：" + (r.stdout + r.stderr).strip()[:200])
    m = re.search(r"certificate DN: (.+)", r.stdout)
    return m.group(1).strip() if m else ""


def put(tok, key, data: bytes, ctype, cache):
    last = None
    for i in range(4):
        req = urllib.request.Request(API + key, data=data, method="PUT", headers={
            "Authorization": "Bearer " + tok, "Content-Type": ctype, "Cache-Control": cache, "User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                if json.loads(r.read()).get("success"):
                    print(f"  ↑ {key}  ({len(data):,} B)")
                    return
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"  上传失败重试 {i + 1}：{key} {e}")
    sys.exit(f"上传失败：{key} {last}")


def fetch(url):
    req = urllib.request.Request(url + ("&" if "?" in url else "?") + "t=" + str(os.getpid()),
                                 headers={"User-Agent": UA, "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--notes", default="")
    ap.add_argument("--allow-debug", action="store_true", help="允许 debug 签名（仅测试，别发给用户）")
    a = ap.parse_args()
    apk = pathlib.Path(a.apk).resolve()
    if not apk.is_file():
        sys.exit(f"没有这个文件：{apk}")

    pkg, code, ver = apk_info(apk)
    if pkg != PACKAGE:
        sys.exit(f"包名不对：{pkg}（应为 {PACKAGE}）")
    dn = signer_dn(apk)
    if "Android Debug" in dn and not a.allow_debug:
        sys.exit("这个 APK 是 debug 签名。debug 签名的包以后没法给用户更新，请用正式 keystore 重新出包。")

    data = apk.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    name = f"guoduo-tv-{ver}.apk"
    key = f"guoduo/tv/v{ver}/{name}"
    manifest = {"version": ver, "versionCode": code, "url": f"{PUBLIC}/{key}", "sha256": sha,
                "size": len(data), "notes": a.notes}
    print(f"包 {pkg} {ver} ({code})  签名 {dn}  sha256 {sha[:16]}…")

    tok = token()
    put(tok, key, data, "application/vnd.android.package-archive", "public, max-age=31536000, immutable")
    body = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    put(tok, "guoduo/tv/latest.json", body, "application/json; charset=utf-8", "public, max-age=60")
    (ROOT / "docs/tv").mkdir(parents=True, exist_ok=True)
    (ROOT / "docs/tv/latest.json").write_bytes(body + b"\n")

    # 用公网地址回读校验：清单、安装包都要和本地一致
    got = json.loads(fetch(f"{PUBLIC}/guoduo/tv/latest.json"))
    if got.get("sha256") != sha:
        sys.exit(f"❌ 公网清单不是刚传的：{got.get('version')}")
    remote = fetch(manifest["url"])
    if hashlib.sha256(remote).hexdigest() != sha:
        sys.exit("❌ 公网下载的安装包 SHA256 对不上")
    print(f"✅ {PUBLIC}/guoduo/tv/latest.json  → {ver}，安装包公网回读 SHA256 一致")


if __name__ == "__main__":
    main()
