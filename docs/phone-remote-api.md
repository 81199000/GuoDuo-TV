# 果多电视版 · 手机遥控接口（给手机版 GuoDuo 用）

电视端内置一个局域网 HTTP 服务（`LocalService`，默认端口 8765，被占用时依次试 8766…8775）。
网页版遥控（扫码打开）和手机版 GuoDuo App 用的是同一套接口。

## 1. 发现电视（免扫码）

电视开着果多时，会在局域网广播 mDNS / DNS-SD 服务：

- 类型：`_guoduo._tcp`
- 名称：`果多 · <电视型号>`（例：`果多 · MiTV-MFFU0`）
- TXT：`id=<电视固定编号>`、`api=1`、`app=guoduo-tv`
- 端口：服务实际端口

iOS 用 `NWBrowser(for: .bonjour(type: "_guoduo._tcp", domain: nil))`（Info.plist 加
`NSBonjourServices = ["_guoduo._tcp"]` 和 `NSLocalNetworkUsageDescription`）。

## 2. 绑定

1. `GET /api/pair/info` → `{app:"guoduo-tv", api:1, id, name, model, version, port, remoteOn, needPassword, bound}`
   （公开信息，不含账号 / 记录）
2. `POST /api/pair/bind`，body `{"device":"我的 iPhone","password":"1234"}`（电视没设遥控密码时 password 可省）
   → `{ok:true, token, id, name}`；密码错 403，连错 5 次锁 60 秒（429）
3. 手机按 `id` 保存 `token`（Keychain）。之后所有请求带 `Authorization: Bearer <token>`。
   电视上改 / 清遥控密码时，所有旧令牌一起失效（再绑一次即可）。

## 3. 遥控

- `POST /api/remote/key` body `{"key":"up|down|left|right|ok|back|menu|home|playpause","phase":"tap|down|up|repeat"}`
  - 按键直接投递给电视当前界面，**效果和电视遥控器完全一样**（首页移焦点、播放器快进 / 换集 / 暂停 / 菜单…）
  - OK 要支持「按住 2 秒 = 2 倍速」：按下发 `phase:"down"`，抬起发 `phase:"up"`，由电视计时
  - 方向键按住连发：每 180ms 发一次 `tap`
  - 果多不在前台时，第一下只把果多叫到前台（返回 `opened:true`）
- `POST /api/control` body `{"action":"toggle|play|pause|seek|next|previous|forward|rewind|menu|boost|home|speed|quality|...","version":<status.version>}`
- `GET /api/status` → 正在播放的剧、集数、进度、倍速、清晰度等（1 秒轮询即可）
- 选剧：`GET /api/home|/api/search|/api/detail|...`，`POST /api/play {id,index,resume}`

## 4. 门禁

- 电视「设置 → 遥控」关掉手机遥控：所有接口 403 `{remoteOff:true}`
- 强制休息时段：423 `{rest:true}`
- 设了遥控密码而没授权：401 `{needPassword:true}` → 走一次绑定

