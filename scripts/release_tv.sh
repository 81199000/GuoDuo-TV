#!/bin/bash
# 一键发布 GuoDuo 电视版：正式签名出包 → 传 R2 → 写 latest.json（电视端「自动检测更新」读它）
#   scripts/release_tv.sh 0.6.7 96 "· 更新内容一
# · 更新内容二"
set -euo pipefail
VER="$1"; CODE="$2"; NOTES="${3:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${TMPDIR:-/tmp}/guoduo-tv-release"
rm -rf "$BUILD" && mkdir -p "$BUILD"
cd "$ROOT"
# 只打已提交的代码（防止半成品进正式包）
git diff --quiet && git diff --cached --quiet || { echo "工作区有未提交改动，先提交再发布"; exit 1; }
git archive HEAD | tar -x -C "$BUILD"
cp -R third_party/hgtv_media3_dependencies "$BUILD/third_party/"
# 正式签名：GUODUO_SIGNING_ENV 指向一个 env 文件（LVGUO_KEYSTORE / LVGUO_STORE_PASSWORD / LVGUO_KEY_ALIAS / LVGUO_KEY_PASSWORD），不入库
[[ -n "${GUODUO_SIGNING_ENV:-}" ]] && { set -a; . "$GUODUO_SIGNING_ENV"; set +a; }
[[ -n "${LVGUO_KEYSTORE:-}" ]] || { echo "没配置正式签名（GUODUO_SIGNING_ENV 或 LVGUO_KEYSTORE）"; exit 1; }
: "${JAVA_HOME:?先设 JAVA_HOME}" "${ANDROID_SDK_ROOT:?先设 ANDROID_SDK_ROOT}"
export JAVA_HOME ANDROID_SDK_ROOT
export LVGUO_APPLICATION_ID=me.rrmm.guoduo.tv LVGUO_APP_LABEL=GuoDuo LVGUO_OUTPUT_NAMES=guoduo-tv.apk
export LVGUO_VERSION_NAME="$VER" LVGUO_VERSION_CODE="$CODE" LVGUO_BUILD_DIR="$BUILD/.build"
(cd "$BUILD" && python3 build_local.py)
mkdir -p "$ROOT/out" && cp "$BUILD/out/guoduo-tv.apk" "$ROOT/out/guoduo-tv-$VER.apk"
python3 "$ROOT/scripts/sync_tv_r2.py" "$ROOT/out/guoduo-tv-$VER.apk" --notes "$NOTES"
