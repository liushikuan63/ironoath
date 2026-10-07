# Cocos 客户端调试落地清单 · PROJECT_IRON_OATH

> 用途：拿到这份仓库的人，按顺序做完就能**在浏览器里看到并操作第一屏**。
> 更新时间：2026-09-13 ｜ 对应收口清单 #103/#105/#107/#108/#109/#110
> 本清单里的每一步都在本机实测过，不是"应该可行"。

---

## 一、已经就绪的东西（2026-09-13 实测）

| 项 | 状态 | 位置 |
|---|---|---|
| Cocos Creator 3.8.7（Windows 绿色版） | ✅ 已安装并启动过 | `D:\Cocos\Creator\3.8.7\CocosCreator.exe`（3.65 GB） |
| Cocos 工程 | ✅ 可直接打开 | 工程根就是 `client/`（`assets/` + `settings/` + `package.json`） |
| 启动场景 | ✅ 已生成 | `client/assets/scenes/Boot.scene`（Canvas + Camera + `Game` 节点） |
| 挂载的组件 | ✅ | 同一节点上：`GameBootstrap`；`PanelNav` 在运行期创建十个全屏面板节点 |
| Web 构建产物 | ✅ | `client/build/web-mobile/`（本次 debug 构建约 7.3 MB；release 构建原先约 3.5 MB） |
| 实测证据 | ✅ | 登录 → 内城/地图导航；按钮与双指缩放、回城、进内城、迁城二次确认均已实测；控制台零异常 |
| 红点角标 | ✅ | 导航条与社交页签两级角标已实测；服务端红点亮时显示，帮助全部处理后同轮熄灭 |
| 行军列表 | ✅ | 地图「行军」按钮可展开；行军中/驻扎中可召回，采集中可收取，动作后倒计时与列表同步 |
| 第一批美术 | ✅ | 运行时资源已接到 `client/assets/resources/ui/generated/**`：面板底与按钮四态、29 个图标（图集子帧）、16 个地形变体与 5 个地图实体；运行期校验见 `tools/verify-art-runtime.mjs` |
| 字体策略 | ✅ | 明确接受系统字体；所有 Label 统一走 `scene/UiFont.ts` 的中文字体回退栈，不引入全量 CJK 字体包 |
| 微信 release 产物 | ✅ | `npm run build:wechat`；release **3.30MB / 4MB**，横屏，产物检查退 0；debug 包只用于开发者工具 |

> Cocos 下载直链（记在这里，重装时不用再找）：
> `https://download.cocos.com/CocosCreator/v3.8.7/CocosCreator-v3.8.7-win-080718.zip`
> （1,026,871,993 字节；解压即用，无需安装器；首次启动会在 `C:\Users\<你>\.CocosCreator\` 建 profile）

---

## 二、跑起来（两条命令 + 一个浏览器）

```bash
# ① 服务端（dev profile，内存存储，8080）
npm run dev

# ② 托管已构建的客户端（8090）
cd client/build/web-mobile
python -m http.server 8090
```

浏览器打开 <http://localhost:8090> —— 这就是上面那张"内城"截图。

> **窗口宽度至少 900px**。游戏按 960×640 横屏设计：在窄高窗口（侧栏、竖屏）里等比缩放后
> 画面会缩到顶部一小条、字小到看不清。现在遇到这种窗口，页面会直接弹出
> 「窗口太窄，画面会小到看不清」+「进入全屏」按钮，而不是让你对着一个看不懂的画面猜。
> 把窗口拉宽后提示自动消失。

**不需要编辑器也能看**；要改代码/场景时再开编辑器：

```powershell
& 'D:\Cocos\Creator\3.8.7\CocosCreator.exe' --project 'D:\Java\GitHub\ironoath\client'
```

首次打开编辑器需要**登录 Cocos 账号**（编辑器自身要求，与游戏无关）；
首次导入资源会生成 `.meta`（已在仓库里，正常情况下直接复用）。

---

## 三、在编辑器里预览（构建产物之外的第二条路）

1. 打开工程后，资源管理器进入 `assets/scenes`，双击 `Boot.scene`。
2. 选中 `Canvas/Game` 节点，属性检查器里能看到 `GameBootstrap`：
   `baseUrl = http://localhost:8080`、`wsUrl = ws://localhost:8080/ws`、
   `deviceId`（留空则本机固定存储，清缓存后换号）、`nickName`。
   `PanelNav` 与十个面板 View 都由该组件在运行期装配，不需要手工摆节点。
3. 点编辑器右上角 ▶ 预览；服务端仍在 8080 跑着即可。

**为什么场景里只有一个空节点**：11 个面板全部是**自绘**（`Graphics` 画底 + `Label` 写字 + `NodePool` 复用行节点），
场景侧不需要摆任何 UI 子节点 —— 把组件挂在同一个节点上就行。这条约定来自
`GameBootstrap`：它用 `getComponent`（不是 `getComponentInChildren`）找面板，
"没接上"必须是显式的装配错误，而不是"按钮没反应"。

**同一时间只挂一个全屏面板**：每个面板都按"占满整屏"实现；
`PanelNav` 只激活当前 key 对应的节点，底部导航负责切换。

---

## 四、面板清单（挂哪个、看什么、依赖什么）

把组件加到 `Canvas/Game` 节点上即可（和 `GameBootstrap` 同一节点）。

| 组件（`assets/scripts/scene/`） | 挂上后能看到 | 主要依赖端点 |
|---|---|---|
| `CityPanelView` | **内城**：资源条、建筑列表、升级/加速/收割 | `/city/list`、`/city/upgrade`、`/city/collect` |
| `ArmyPanelView` | 军队：兵力、训练/治疗队列 | `/army/list`、`/army/train` |
| `HeroPanelView` | 武将册、编队 | `/hero/list` |
| `BagPanelView` | 背包与资源明细、使用道具 | `/bag/list`、`/item/use` |
| `StagePanelView` | 关卡列表、挑战/扫荡 | `/stage/list`、`/stage/challenge` |
| `SocialPanelView` | 小队/联盟/互助/成员 | `/social/summary`、`/social/helpRequests` |
| `PowerPanelView` | 战力构成 | `/power/detail` |
| `TargetSearchView` | 目标搜索（圈层/距离） | `/world/search` |
| `QuestPanelView` | 任务列表、领取、首日三选一 | `/quest/list`、`/quest/claim` |
| `WorldMap` | 世界地图、迷雾、行军（自绘瓦片） | `/world/viewport` 等 |
| `BattlePlaybackView` / `GachaDisclosureView` | 战报回放 / 抽卡概率公示 | 由各自入口触发 |

---

## 五、本轮修掉的三个「只在 Cocos 下才暴露」的问题

这三个都是 `tsc` + `node:test` 绿、进 Cocos 就红的类型 —— 收口清单 #103 有完整记录。

1. **测试不能放在 `assets/` 下**：Cocos 会编译 `assets/**` 里的所有 `.ts`，
   而测试 `import 'node:test'`，编辑器直接报
   `无法加载模块 node:test，这是因为：Cocos Creator 不提供 Node.js 内置模块`。
   → 已把 `client/assets/scripts/test/` 迁到 **`client/tests/`**，CI 配置同步更新。
2. **Set / Map 不能用 spread**：Cocos 的转译把 `[...aSet]` 编译成 `[].concat(aSet)`，
   运行时得到的是 `[Set]` 而不是元素数组 —— 表现是
   `[Store] 快照订阅者抛出异常 TypeError: y is not a function`（而且被 try/catch 隔离，
   只在控制台刷日志，不会崩）。受影响 11 处（Store、EventBus 之外的集合遍历、
   WorldMap/WorldViewModel/ChunkCache/ReddotTree/AppRoot）。
   → 已全部改成 `Array.from(...)`。**在 `assets/` 下的新代码里不要对 Set/Map 用 `[...x]`**。
3. **HTTP 跨域**：客户端在 8090、服务端在 8080，浏览器预检失败时页面只会"一片红"，
   服务端日志里什么都看不到。WebSocket 早就 `setAllowedOriginPatterns("*")`，
   HTTP 一直没有。
   → 新增 `DevCorsConfig`，**只在 `dev` profile** 放行 `http://localhost:*` / `http://127.0.0.1:*`。
   生产与测试 profile 不放行。

---

## 六、微信小游戏构建（阶段 5.2 实测）

```powershell
# 1) 出包（debug 给开发者工具，release 给提审；两者都先 --force）
& 'D:\Cocos\Creator\3.8.7\CocosCreator.exe' --project 'D:\Java\GitHub\ironoath\client' `
  --build 'platform=wechatgame;debug=true;startScene=bdd25b4c-ff73-4d64-953d-e6119fc37fd8' --force

# 2) 方向归一化（Cocos 的微信方向在嵌套选项里，CLI 传不进去）
node scripts/patch-wechat-orientation.mjs

# 3) 卡口：方向必须是 landscape；release 体积必须 ≤ 4MB
bash scripts/check-wechat-artifact.sh
bash scripts/check-package-size.sh
```

产物在 `client/build/wechatgame/`，用微信开发者工具打开该目录即可预览（需要测试 AppID）。
**实测体积**：release **3.30MB / 4MB**；关闭未使用的引擎模块并对运行期图集做屏幕尺寸降采样后，
当前无需分包。debug 包 9.10MB，只用于开发者工具，不参与提审预算。

**微信开发者工具（2026-09-13 安装并实测）**

- 安装：`winget install --id Tencent.WeixinDevTools`（本机 2.02.2608070，
  目录 `D:\Program Files (x86)\Tencent\微信web开发者工具\`）。
- **CLI 服务端口默认关闭**：不开则所有 `cli open/preview/auto` 直接报
  `IDE service port disabled`；开关在 IDE「设置 → 安全设置 → 服务端口」，本机 HTTP 端口 45269。
- 打开产物：`cli.bat open --project client/build/wechatgame`。
  构建脚本现在会把 `project.config.json` 的 appid 写成 `WECHAT_GAME_APPID`
  （默认 `wxa048c9e48c2fc7d1`）；Cocos 模板自带的 `wx6ac3f5090a6b99c5` 是游客 AppID，
  新版 IDE 会拒绝，表现是"构建成功但打不开项目"。
- **IDE 缓存里的项目类型必须是小游戏**：`compileType: "game"` 且 `engine: true`。
  若被写成 `weapp`，编辑器会按小程序找 `app.json`，日志报「在项目根目录未找到 app.json」。
- 成功判据（IDE 日志）：`[appservice] simulator launch success, set src http://127.0.0.1:<port>/appservice/...`。
- **`cli preview` 不适用于小游戏产物**：它走小程序上传通道，会对 `game.js` 项目报
  `/app.json not found`。真机预览请在 IDE 里扫码或走「真机调试」，不要把这条当成构建坏了。

## 七、常见问题

| 现象 | 原因与处理 |
|---|---|
| 面板画出来了但按钮没反应 | 面板组件没和 `GameBootstrap` 挂在**同一个节点**上 |
| 页面一片黑 / 只有顶部一小条 | 窗口太窄（< 900px）：点提示里的「进入全屏」或把窗口拉宽 |
| 页面黑屏、控制台一堆网络错误 | 服务端没起，或没用 `dev` profile（CORS 只在 dev 放行） |
| 每次刷新都是新号 | `deviceId` 留空时用 `web-<时间戳>`；在场景里给 `GameBootstrap.deviceId` 填死一个值即可固定账号 |
| 编辑器里 TS 报错但 CI 全绿 | `client/types/cc.d.ts` 只是 headless 类型桩，以编辑器为准（`DEVELOPMENT.md` §八） |
| 构建报 `startScene(undefined)` | 没设初始场景：构建参数 `startScene=<场景 uuid>`，或在编辑器里设默认场景 |
| 构建产物 404 | 静态服务器要指向 `client/build/web-mobile/` 目录本身 |
| 黑屏、控制台报 `Error 3817` / `Missing class` | 旧增量缓存把脚本类判成 corrupted。先停掉正在运行的构建，再用 `CocosCreator.exe --project <client> --build platform=web-mobile;debug=true --force` 强制重建 |
| 地图贴图像被拉长的条带 | TILED 精灵跨 UV 的 [0,1] 时，纹理默认是 `clamp-to-edge` 就会重复边缘像素。地形加载后要 `Texture2D.setWrapMode(REPEAT, REPEAT)`（ArtCatalog 里已做） |
| 深链 `?panel=world` 看起来没生效 | 在 Cocos 构建产物里用裸字符串拼 URL 时 `&` 会被截断；调试脚本必须用 `URL` + `URLSearchParams` 拼参数 |

---

## 八、下一步（按价值排序）

1. **微信开发者工具（5.1）**：安装并注册测试 AppID，用已生成的 `client/build/wechatgame` 做真机预览。
2. **平台登录（5.3）**：**[2026-10-07 就地更正：服务端已实现]** `wx.login → openid → session` 服务端在册（`WeChatSessionCodeExchanger` + `LocalDevWeChatCodeExchanger`，`SecurityBeansConfig` 装配、`WeChatLoginTest` 在库；客户端 `GameSession`/`GameBootstrap` 亦在调 `wx.login`）——但**仍不能声称真机登录完成**：缺的是真机端到端（与本节"真机预览未验证"同因，原句"尚未实现"作废）。
3. **真机性能（阶段 6）**：drawcall/FPS 目前只有无头浏览器基线（内城 115 / 地图 45），需在低端安卓上复核。
