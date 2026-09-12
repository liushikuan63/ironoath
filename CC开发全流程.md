# CC（Cocos Creator）开发全流程 · PROJECT_IRON_OATH

> 用途：客户端从「工程能跑」到「微信小游戏可提审」的完整路径。
> 用法：**按阶段推进，不跳阶段**；每项做完才勾选，勾选时必须能指出验收证据。
> 维护约定：本文件是客户端方向的唯一路线图；阶段内新增的坑与边界写进对应条目的「备注」，
> 不另开一份清单（收口清单管全仓库的问题，本文件管客户端推进顺序）。
>
> 创建：2026-09-12 ｜ 当前阶段：**阶段 3（表现层补齐）**

---

## 阶段总览

| 阶段 | 目标 | 状态 |
|---|---|---|
| 0 | 环境与工程骨架 | ✅ 完成 |
| 1 | 首屏可运行（登录→内城→操作） | ✅ 完成 |
| 2 | 面板导航：九个系统全部可达 | ✅ 完成 |
| 3 | 表现层补齐：世界地图、主城可视化、选择器、红点 | ⏳ 进行中（3.1 第一步完成） |
| 4 | 美术资源替换（占位色块 → 正式资源） | ⬜ 未开始 |
| 5 | 微信小游戏适配与构建 | ⬜ 未开始 |
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

## 阶段 3 · 表现层补齐 ⏳

**目标**：从「列表式占位」补成「游戏画面」；玩家不看文档也知道自己在哪、能做什么。

### 3.1 世界地图接入导航 ⏳（第一步已完成，交互待验证）
- [x] 把 `WorldMap` 加入 `PanelNav`（key：`world`，导航条第 10 项）
- [x] 数据流接线：`AppRoot.start()` 已含 `refresh('world')` → `enterWorld()` →
      `initializeWorld` + `bindWorldRequester`；`WorldMap.update()` 每帧从 `worldModel()` 取帧
- [x] 面板节点补全屏 `UITransform`：触摸命中按它算，缺了它地图「能看不能拖」
- [ ] **镜头：拖拽平移、缩放** —— 待验证。CDP 触摸模拟（`Input.dispatchTouchEvent` /
      `Input.dispatchMouseEvent` + `setEmitTouchEventsForMouse`）都没能让镜头移动，
      需要在真实浏览器里手玩确认是"输入链路"还是"拖拽实现"的问题
- [ ] 「回城」按钮（`out.home` 接缝已就位，未实测）
- [ ] 迷雾遮罩：截图里已有迷雾表现（未探索区域留黑），需确认与 `fogChunks` 同源
- **验收**：能拖动地图、缩放、看到自己的城与已探索区域；拖动 60 秒无内存持续上涨（B07 验收 3）
- **已实测**：切到「地图」后渲染正常 —— 红方块是自己的城、绿色/灰色小方块是实体、
  左上角「放大/缩小/回城/流亡迁城」HUD、右上角坐标 `(301, 387)`，控制台零错误

### 3.2 主城可视化 ⬜
- [ ] 内城面板从「建筑列表」升级为「城内网格布局」：按 `gridX/gridY` 排布建筑
- [ ] 建筑卡片：图标位 + 等级 + 状态（升级中/可收割）
- [ ] 点击建筑 → 详情/升级/加速（复用现有回调）
- **验收**：一眼能看出城里有几栋、哪栋在升级

### 3.3 二级选择器 ⬜
- [ ] 道具目标选择器（加速类道具需要 targetId；`useItem(needsTarget)` 已有接缝）
- [ ] 出战阵容选择器（`challenge` 需要阵容；B05）
- [ ] 首次建造的坐标选择（B03 `gridX/gridY`）
- **验收**：三个「缺输入」的动作都能完成一次真实操作（收口清单 #36 的边界②）

### 3.4 红点角标 ⬜
- [ ] 红点树（`/social/reddot` 等）已有注册点与读端点，客户端只缺「画角标」
- [ ] 导航条 + 面板行两级角标
- **验收**：有未读求助时「社交」角标亮起，进面板处理后消失

---

## 阶段 4 · 美术资源替换 ⬜

**目标**：去掉全部 `Graphics` 色块占位，换成正式资源；首包仍在预算内。

- [ ] 4.1 目录约定：`assets/resources/ui/**`（可热更）与 `assets/ui/**`（首包）的边界先定
- [ ] 4.2 UI 九宫格：面板底、按钮（常态/按下/禁用）、页签
- [ ] 4.3 图标：资源六种、建筑十余种、兵种四类、道具稀有度四档
- [ ] 4.4 地图：地形瓦片（512×512 世界、32 格 chunk）、城池/野怪/资源点
- [ ] 4.5 字体：中文位图字体（小游戏下系统字体渲染有平台差异）或明确接受系统字体
- [ ] 4.6 图集：自动图集 + drawcall 检查
- **验收**：首包 ≤ `PERF_FIRST_PACKAGE_MAX_BYTES`（`check-package-size.sh` 可量）；
  真机 FPS ≥ `PERF_MIN_FPS`

**备注**：美术资源需要外部产出，本阶段与美术并行；代码侧先做「资源可替换」的接缝
（贴图字段、九宫格配置、图集分组），不要等到资源到位再改结构。

---

## 阶段 5 · 微信小游戏适配与构建 ⬜

- [ ] 5.1 装微信开发者工具，注册测试 appid（真机预览必须）
- [ ] 5.2 Cocos 构建平台 `wechatgame`：构建参数、`startScene`、分包配置
- [ ] 5.3 平台适配：`wx.login` → openid 换 session（服务端 B15 未做，见上线检查清单 §二 1）；
      存储走 `sys.localStorage`（已适配）；支付/分享按合规要求接（禁「分享领奖」）
- [ ] 5.4 包体：首包 4MB 以内，超出部分进分包/远程资源（`remoteServerAddress`）
- [ ] 5.5 真机预览：iOS + Android 各一台
- **验收**：真机能进游戏、能登录、能完成一次升级；首包体积达标

**备注**：服务端的微信登录与米大师支付还没做（上线检查清单 §二/§三），
客户端这一阶段只能先接「开发期身份」（`X-Player-Id`）。

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