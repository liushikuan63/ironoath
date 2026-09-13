# CC（Cocos Creator）开发全流程 · PROJECT_IRON_OATH

> 用途：客户端从「工程能跑」到「微信小游戏可提审」的完整路径。
> 用法：**按阶段推进，不跳阶段**；每项做完才勾选，勾选时必须能指出验收证据。
> 维护约定：本文件是客户端方向的唯一路线图；阶段内新增的坑与边界写进对应条目的「备注」，
> 不另开一份清单（收口清单管全仓库的问题，本文件管客户端推进顺序）。
>
> 创建：2026-09-12 ｜ 当前阶段：**阶段 5（微信小游戏适配与构建）**

---

## 阶段总览

| 阶段 | 目标 | 状态 |
|---|---|---|
| 0 | 环境与工程骨架 | ✅ 完成 |
| 1 | 首屏可运行（登录→内城→操作） | ✅ 完成 |
| 2 | 面板导航：九个系统全部可达 | ✅ 完成 |
| 3 | 表现层补齐：世界地图、主城可视化、选择器、红点 | ✅ 完成 |
| 4 | 美术资源替换（占位色块 → 正式资源） | ✅ 完成（系统字体方案已明确） |
| 5 | 微信小游戏适配与构建 | 🟡 进行中（构建与首包已达标，真机预览待外部工具） |
| 6 | 真机、性能与弱网验收 | ⬜ 未开始 |
| 7 | 发布、热更与灰度 | ⬜ 未开始 |

---

## 阶段 0 · 环境与工程骨架 ✅

**目标**：任何人拿到仓库，能打开工程、能构建、不改代码就能出产物。

- [x] Cocos Creator 3.8.7 安装（Windows 绿色版）
      路径 `D:\Cocos\Creator\3.8.7\CocosCreator.exe`；下载直链写在《Cocos调试落地清单》
- [x] `client/` 作为工程根：`assets/` + `settings/` + `.meta` 全部入库
- [x] headless 与编辑器配置分离：CI 用 `tsconfig.headless.json`，编辑器自己管 `tsconfig.json`
- [x] `assets/scenes/Boot.scene`：Canvas + Camera + Game 节点（挂 `GameBootstrap`）
- [x] headless 构建跑通（`--build platform=web-mobile`），**Missing class = 0**
- [x] 本地静态托管：`client/build/web-mobile` + `python -m http.server 8090`

**验收证据**：`CC开发全流程.md` 所在轮次的构建日志；《Cocos调试落地清单》第二节。

**备注（踩过的坑）**：
- 手写场景里脚本组件的 `__type__` 必须是**压缩 uuid**（如 `de341Wd9G9BbKflbHnZok+b`），写完整 uuid 会 Missing class。
- `assets/` 下的测试脚本会被当运行时脚本编译 → 测试放 `client/tests/`。
- Set/Map 的 spread 会被转译成 `[].concat(set)` → 一律用 `Array.from(...)`。

---

## 阶段 1 · 首屏可运行 ✅

**目标**：浏览器打开 → 自动登录 → 内城面板 → 点升级真的生效。

- [x] 组合根：`GameBootstrap`（场景层）→ `AppRoot`（编排层，不 import cc）
- [x] 登录链路：`/player/init` → `X-Player-Id` 头 → 预拉十个面板数据
- [x] dev profile 的 HTTP CORS（`DevCorsConfig`，仅 dev 放行 localhost）
- [x] 视口守卫：宽度 < 900px 显示「窗口太窄」+ 全屏按钮
- [x] 进度与倒计时同步（协议补 `startedAt`/`totalSeconds`，见收口清单 #104）
- [x] 收割消息分两态（真的有升级完成 / 只有产出补结算）
- [x] `deviceId` 用 `sys.localStorage` 持久化（刷新不换号）

**验收证据**：收口清单 #103、#104 的实测段落（含截图与逐点对账）。

---

## 阶段 2 · 面板导航 ✅

**目标**：九个系统都能切到，切过去是「已经有数据的面板」而不是空白。

- [x] `PanelNav`：每个面板一个子节点，初始未激活（不跑 onLoad、不画背景）
- [x] 底部导航条：内城/军队/武将/背包/关卡/任务/社交/战力/搜索，当前项高亮
- [x] 数据由 `AppRoot.start()` 预拉，各面板 `pending` 消费；`BagPanelView` 已补 pending
- [x] `GameBootstrap.targets()` 改为按导航 key 查子节点组件
- [x] 从 `Boot.scene` 移除最早那份 `CityPanelView`（空实例会盖住导航条）

**验收证据**：c7a8ad2 提交说明；真机 1712×960 下九个按钮切换正常。

**备注**：世界地图**刻意不在**导航条里 —— 它的数据流（viewport/marches 订阅 + 瓦片缓存 + 镜头）
与其余面板不同，作为阶段 3.1 单独接。

---

## 阶段 3 · 表现层补齐 ✅

**目标**：从「列表式占位」补成「游戏画面」；玩家不看文档也知道自己在哪、能做什么。

### 3.1 世界地图接入导航 ✅
- [x] 把 `WorldMap` 加入 `PanelNav`（key：`world`，导航条第 10 项）
- [x] 数据流接线：`AppRoot.start()` 已含 `refresh('world')` → `enterWorld()` →
      `initializeWorld` + `bindWorldRequester`；`WorldMap.update()` 每帧从 `worldModel()` 取帧
- [x] 面板节点补全屏 `UITransform`：触摸命中按它算，缺了它地图「能看不能拖」
- [x] **镜头拖拽**：直接派发 `PointerEvent` 验证通过 —— 坐标 `(301, 387)` → `(352, 183)`，
      位移方向与「手指往哪拖、地图跟着动」一致
- [x] **HUD 图层尺寸**：`MapLayer` / `HudLayer` 原先只有 `UITransform` 没尺寸，
      它们子树里的按钮全部收不到触摸（放大/缩小/回城点了没反应）→ 已补全屏尺寸
- [x] **回城**：家坐标唯一保存在 `WorldViewModel`；行军列表刷新和迁城结果都会改写同一份状态，
      回城按钮不再维护第二份缓存。实际拖动到 `(473, 73)` 后点回城，坐标精确回到 `(433, 95)`
- [x] 迷雾：未探索块留黑（截图可见），数据同源于 `WorldViewModel` 的 fog 帧
- [x] **按钮交互**：Playwright 实际点击验证 `缩放 0 → 1 → 0`，第二次放大切到内城、
      底部「地图」可返回；控制台无异常
- [x] **双指缩放**：在 `hasTouch` 浏览器上下文中用双指事件验证 `缩放 0 → 1`，
      第二次独立手势进入内城，单次手势最多跨一档
- [x] **迁城确认**：点击「流亡迁城」进入「确认迁城?」，5 秒未确认自动恢复，
      在途请求期间按钮锁定，避免重复发起
- [x] **行军列表**：显示状态、目标、当前位置、载重与剩余时间；行军中/驻扎中可召回，
      采集中可收取，动作在途时锁定避免重复请求
- [x] **动作落地**：召回与收取成功后重拉行军列表；提示返程倒计时或实际收取的资源，
      失败时保留服务端给出的原因
- [x] **构建收口**：旧增量产物会让 `GameBootstrap` 被 Cocos 判为 corrupted（黑屏、Error 3817）；
      使用 `--force` 强制重建后，`Game` 节点组件为 `UITransform + GameBootstrap + PanelNav`，
      浏览器控制台零错误
- **验收**：拖拽、按钮缩放、双指缩放、进内城 / 返回、回城、迁城确认、迷雾与实体、
  行军列表召回与收取 ✅；证据见 `收口清单.md` #105/#109

### 3.2 主城可视化 ✅
- [x] 内城面板从「建筑列表」升级为 **6×6 城内网格**：按服务端 `gridX/gridY` 落位；
      非法或重复坐标进入 `unplaced` 并在队列行明示，不静默丢建筑
- [x] 建筑卡片：色块图标位 + 等级 + 空闲/升级中/可收割/暂停状态 + 升级进度条
- [x] 点击建筑 → 底部详情条复用升级 / 广告加速 / 金币加速 / 收割回调；
      主城、兵营等现有动作不需要第二套接线
- [x] 建筑数量随响应接入：新号显示 `建筑 1/36`，主城用 `Lv1` 与实际坐标绘制
- **验收**：1712×960 实际截图可见 6×6 网格与唯一主城；点击「升级」后队列 `0/2 → 1/2`，
      主城卡片从「空闲」变为「升级 4%」，详情倒计时显示 `00分28秒`，控制台无异常；
      证据见 `收口清单.md` #106

### 3.3 二级选择器 ✅
- [x] 道具目标选择器：只列正在升级 / 训练的队列，选择后把真实 `targetId` 交给 `/item/use`
- [x] 出战阵容选择器：只列已编成且有兵力的阵容，选择后一次性提交英雄与全部可用兵力
- [x] 首次建造选择器：服务端下发未放置建筑的 `buildOptions`，选中建筑和地块后再提交 `gridX/gridY`
- [x] 滚动升级兼容：旧服务端缺少 `buildOptions` 时客户端按空候选处理，面板不崩
- **验收**：三类动作都完成真实选择链路；客户端 360/360、仓库静态检查与 Maven 1397 项全绿；
  证据见 `收口清单.md` #107

### 3.4 红点角标 ✅
- [x] `/social/reddot` 进入首屏拉取；`AppRoot` 持有唯一 `ClientReddotTree`，服务端整树替换后直接下发给场景层
- [x] 导航条角标：`city → city`、`social → social`；没有注册叶子的入口不装角标，避免假红点
- [x] 面板行角标：社交页签按 `social/invite`、`social/help`、`social/events` 读同一棵树
- [x] 写操作后重拉：升级/加速/收割、使用加速道具、单条帮助、一键帮助、事件已读都会刷新红点树
- [x] 场景动作接通：帮助、全部帮助、捐献、踢人、事件已读都从 `SocialPanelView` 进入 `AppRoot`；踢人回调带当前组织，不在组合根猜页签
- [x] 服务端补 `social/events` 叶子，复用 `SocialAppService.hasUnreadEvents`；叶子数从 3 增至 4
- **验收**：客户端 365/365、双工程类型检查退 0，`check.sh` 全绿，服务端 `mvn clean test` 1398/1398；
  Cocos `--force` 构建 22 秒完成且 `Missing class = 0`；浏览器运行时实测「社交」导航角标与「互助」行角标同时亮起，
  真实 `POST /social/helpAll` 返回 `helped=1` 后同页重载，两级角标同轮熄灭；证据见 `收口清单.md` #108

---

## 阶段 4 · 美术资源替换 ✅

**目标**：去掉全部 `Graphics` 色块占位，换成正式资源；首包仍在预算内。

- [x] 4.0 资源来源与授权：Kenney UI（CC0）、Game-icons（CC BY 3.0）、Noto CJK（OFL 1.1）登记到 `art-src/manifest.json`
- [x] 4.1 目录约定：`client/assets/resources/ui/**`（可热更）与 `client/assets/ui/**`（首包）的边界已写入 `art-src/README.md`
- [x] 4.2 UI 九宫格：面板底与按钮四态接入 Cocos。**按钮以九宫格渲染**而不是整图拉伸 ——
      母版 512×191，实际要铺到 26~34px 高，SIMPLE 会把四角压成椭圆
- [x] 4.3 图标：资源六种、建筑十五种、兵种四类、稀有度四档接入 NPC 图集子帧；
      内城/背包/军队/武将四个面板都已用真实图标替换色块；主城、粮食、步兵、稀有度四类
      子帧按索引坐标逐项校验，不再出现“图集上下翻转导致 SR 显示成主城”的假接入
- [x] 4.4 地图：4×4 地形图集（16 个变体全部启用）+ 玩家城/野怪/资源/联盟建筑/行军标记接入；
      地形走 TILED，运行期把纹理采样显式改为 REPEAT，否则跨格会被拉成条带
- [x] 4.5 字体：明确接受系统字体，不把全量 Noto CJK 塞进首包，也不做会漏掉昵称/联盟名的静态子集；
      客户端统一走 `scene/UiFont.ts` 的跨平台回退栈
      `Microsoft YaHei → PingFang SC → Noto Sans CJK SC → Noto Sans SC → sans-serif`，
      运行期校验 489 个 Label 全部 `useSystemFont=true` 且字体族唯一
- [x] 4.6 图集：图标与地形图集已接入；运行时 drawcall 已记录（内城 115 / 地图 45，1440×900 无头浏览器），
      真机 drawcall 仍归阶段 6 复核
- **当前证据**：第一批生成资源 11 张、约 1.99MB，PNG 校验通过；来源与提示词见 `art-src/manifest.json` 与 `art-src/GENERATION_PROMPTS.md`。
  接入与运行期证据见 `收口清单.md` #110/#111：`tools/verify-art-runtime.mjs` 实际跑 5 个面板，
  `activePanels` 全对、100 个命令按钮全为 `SLICED`、四类图标坐标全对、无 `[ArtCatalog]` 告警
- **首包下界**：`scripts/check-package-size.sh` 量到 `client/assets` **3.05MB / 4MB**
  （去掉重复的 555KB 地形图集后下降），仍缺微信真实构建产物
- **最终验收**：首包 ≤ `PERF_FIRST_PACKAGE_MAX_BYTES`（`check-package-size.sh` 可量）；
  真机 FPS ≥ `PERF_MIN_FPS`

**备注**：美术资源需要外部产出，本阶段与美术并行；代码侧先做「资源可替换」的接缝
（贴图字段、九宫格配置、图集分组），不要等到资源到位再改结构。

---

## 阶段 5 · 微信小游戏适配与构建 🟡

- [x] 5.1 微信开发者工具已装（winget `Tencent.WeixinDevTools` 2.02.2608070，
      安装目录 `D:\Program Files (x86)\Tencent\微信web开发者工具\`），
      CLI 服务端口已开启（IDE 设置 → 安全设置 → 服务端口；本机端口 45269）；
      测试 AppID：`wxa048c9e48c2fc7d1`
- [x] 5.2 Cocos 构建平台 `wechatgame`：构建参数、`startScene`、产物方向归一化；
      一键入口 `npm run build:wechat`（`scripts/build-wechatgame.sh`）
- [x] 5.3 平台适配 —— **微信登录链路已接通**（见 `收口清单.md` #113）：
      `GameBootstrap` 在小游戏运行时调 `wx.login` 取 code → `/player/init` 带 `wxCode`
      → 服务端 `code2session` 换 openid（本地用确定性实现）→ 账号键 `wx:<openid>`
      → 响应下发 HMAC 会话票据 → 之后每条请求带 `Authorization: Bearer <token>`；
      `wx.login` 失败时退回设备号建档，不阻断启动。
      **部署前必须配**：`WECHAT_APP_ID` / `WECHAT_APP_SECRET`（真兑换）与
      `WECHAT_SESSION_SECRET`（票据签名密钥）；缺任意一项时生产启动会被拒绝
      （`ProductionReadiness` 会点名 `LocalDevWeChatCodeExchanger`）。
      **仍未做**：实名/年龄（`MINOR_PAY_*` 两个限额还没有输入）、支付与分享。
      存储走 `sys.localStorage`（已适配）
- [x] 5.4 包体：真实 release 产物 **3.30MB / 4MB**（引擎裁剪后；`npm run build:wechat`）。
      达标靠裁剪未使用的引擎模块（animation/audio/video/webview/spine/dragon-bones/tween/mask 等），
      当前无需分包；预算下降后 `check-wechat-artifact.sh` 会立刻拦住
- [ ] 5.5 真机预览：iOS + Android 各一台
      **前置未满足**：`cli preview` 与 IDE 里的「预览/真机调试」按钮都要求项目被永久识别为小游戏
      （`attr.gameApp=true`），而 `wxa048c9e48c2fc7d1` 在微信侧返回的是 `gameApp:false`
      —— 它是小程序类目账号，工具因此把项目锁在「小程序模式」。换成小游戏类目的 AppID 后，
      用 `WECHAT_GAME_APPID=<你的id> npm run build:wechat` 重出包即可看到扫码入口。
      另：DevTools 内置的「小游戏测试号」（`touristappid`）能进小游戏模式跑模拟器，
      但工具栏不提供预览/真机按钮，不能替代真机验证；注册新账号需要主体与手机扫码，机器代办不了。
- **「测试号」方案的边界（实测记录）**：把项目配置成
  `compileType: "game"` + `appid: "touristappid"` + `setting.urlCheck: false` 后，
  IDE 确实会切到「小游戏模式」并显示「预览」按钮（这条可用于本地跑通形态）；
  但点预览 / `cli preview` 都会被服务端直接拒绝：
  `不存在此 AppID 请检查后重新输入 (code 10)`。
  ⇒ **扫码预览与真机调试只对真实注册的小游戏 AppID 开放**，测试号只能停留在模拟器。

**5.2 现场记录（2026-09-13 实测）**
- 构建命令（在仓库根执行；`startScene` 用 `Boot.scene` 的 uuid）：
  ```powershell
  & 'D:\Cocos\Creator\3.8.7\CocosCreator.exe' --project 'D:\Java\GitHub\tieshi\client' `
    --build 'platform=wechatgame;debug=true;startScene=bdd25b4c-ff73-4d64-953d-e6119fc37fd8' --force
  ```
  `debug=false` 出提审包。CLI 结束码可能是 36，但日志出现
  `build Task (wechatgame) Finished` 即产物已写好（与 web-mobile 同一条现象）。
- **方向必须在构建后归一化**：Cocos 的微信方向住在嵌套构建选项
  `packages.wechatgame.orientation`，CLI 的 `key=value` 只认顶层，传不进去；编辑器又会把
  `client/settings` 的引擎/构建配置按自身内存回写。所以产物生成后跑一次
  `node scripts/patch-wechat-orientation.mjs`，把 `game.json` 固定为 `landscape`。
- **卡口**：`bash scripts/check-wechat-artifact.sh` —— 方向必须是 landscape；
  体积按 debug/release 分开判（debug 只警告，release 超 4MB 直接红）。
- **实测体积**：引擎模块与运行期图集优化前 debug 9.10MB / release 5.50MB；最终
  `npm run build:wechat`（release）**3.30MB**，debug 仅用于开发者工具、不参与预算判断。裁剪只删了
  「全仓库零 import」的模块（scene 层实际只用到 20 个 `cc` 符号），并在 web-mobile
  运行期复验过 5 个面板零错误。
- **验收**：首包体积已达标；「真机能进游戏、能登录、能完成一次升级」仍待 5.5 真机预览。

**备注**：服务端的微信登录与米大师支付还没做（上线检查清单 §二/§三），
客户端这一阶段只能先接「开发期身份」（`X-Player-Id`）。

**5.1 现场记录（2026-09-13 实测）**
- 安装：`winget install --id Tencent.WeixinDevTools`（自动升级到 2.02.2608070）；
  CLI 在安装目录的 `cli.bat`，`cli.bat -h` 能列出 `open/preview/auto/upload` 等命令。
- **CLI 服务端口默认是关的**：直接 `cli open` 会报
  `IDE service port disabled`。开关在 IDE「设置 → 安全设置 → 服务端口」；
  配置落在 `User Data/<profile>/WeappLocalData/localstorage_*.json` 的 `security.enableServicePort`。
  本机已开启（HTTP 端口 45269）。
- **产物必须用真实 AppID**：Cocos 模板自带的 `wx6ac3f5090a6b99c5` 与 `touristappid`
  都会被新版 IDE 的自动化通道拒绝（日志 `formatProject reject tourist/empty appid`）。
  本项目测试 AppID 为 `wxa048c9e48c2fc7d1`。
- **项目缓存里的 `compileType` 要确认是小游戏**：IDE 首次导入时可能把它缓存成
  `weapp`（并按小程序找 `app.json`，报 `在项目根目录未找到 app.json`）。
  正确值是 `compileType: "game"` 且 `engine: true`；改完要重启 IDE 才生效。
  用 CLI 打开后日志出现 `[appservice] simulator launch success` 即模拟器已起。
- **Cocos 3.8.7 × DevTools 2.02.2608070 兼容阻塞（2026-09-13 实测）**：
  小游戏 AppID 能进入“小游戏模式”，但模拟器启动时稳定报
  `Object.defineProperty called on non-object`，栈落在 Cocos 生成的
  `web-adapter.js` → `WAGameSubContext.js`；打开并清空编译缓存、关闭
  `enhance/useIsolateContext/useMultiFrameRuntime/useApiHostProcess` 后仍可复现。
  因此 5.5 真机预览暂不能继续：需要换与 Cocos 3.8.7 兼容的开发者工具版本，
  或升级 Cocos 适配器后重跑；不要在项目里继续补第三方生成文件。
- **`cli preview` 不走小游戏**：它会对游戏产物报
  `/app.json not found`（那是小程序上传通道）。真机验证要在 IDE 里点「预览」扫码，
  或用「真机调试」；命令行预览这条对当前小游戏产物不适用。

---

## 阶段 6 · 真机、性能与弱网验收 ⬜

- [ ] FPS：战斗与地图场景，低端安卓 ≥ `PERF_MIN_FPS`（必须留机型与实测数字）
- [ ] 内存：10 分钟挂机 GC 后回基线（B16 验收 1）
- [ ] 弱网：3G 模拟下不崩、有超时与重试提示
- [ ] 启动：首屏 ≤ `PERF_FIRST_SCREEN_MAX_MS`
- [ ] 接口 payload ≤ `PERF_PAYLOAD_MAX_BYTES`（抓包）

---

## 阶段 7 · 发布、热更与灰度 ⬜

- [ ] 提审材料（版号/备案、隐私协议、客服与退款入口 —— 见上线检查清单）
- [ ] 热更范围与版本闸门（`HOT_UPDATE_SCOPE`、`RELEASE_*`）
- [ ] 灰度 5% → 全量（`RELEASE_GRAY_PERCENT`）
- [ ] 崩溃率与埋点看板（`/ops/*`）

---

## 每次推进的收尾纪律（每个阶段都适用）

1. `bash scripts/test-client.sh`（350+ 项，含 tsc 双 project）
2. `bash scripts/check.sh`（14 项静态检查；改了契约必须 `npm run gen`）
3. Cocos headless 构建（`Missing class = 0`）
4. 真机/浏览器实测：**先自检，再让人看**（截图或 CDP 证据）
5. 提交按功能边界拆分，提交信息写清「为什么」而不是「改了什么」
