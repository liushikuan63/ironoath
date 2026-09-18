# 美术源文件目录

本目录存放**可追溯来源**的原始美术资源，不直接被 Cocos 导入。

- 外部资源按来源分目录，保留原许可证和来源提交号。
- 最终进入客户端包体的资源，必须经过尺寸、格式、图集与署名清单检查后复制到 `client/assets/ui/**`，或放入 `client/assets/resources/ui/**` 走热更边界。
- 当前来源：
  - `kenney/ui-pack-adventure`：CC0，面板、按钮、进度条和箭头（第一批接入）。
  - `kenney/` 下另有 6 个通用包（2026-09-16 增补，直连 kenney.nl 下载）：`ui-pack`（通用 UI 元素）、
    `ui-pack-rpg-expansion`（暗色 RPG 风 UI，最贴近本项目调性）、`fantasy-ui-borders`（装饰边框）、
    `game-icons`（Kenney 自家 105 图标）、`board-game-icons`（中世纪主题）、`generic-items`（通用道具图）。
    均为 CC0；清单与用途见 `manifest.json` 的 `library.kenneyPacks`。
  - `game-icons/svg`：CC BY 3.0，资源、建筑、兵种与稀有度图标（第一批）；2026-09-16 增补
    `svg/systems/` 34 个功能图标（邮件/活动/排行/战令/好友/举报/王权/科技/世界首领…，
    按 `CC开发全流程.md` 阶段 8 的缺口清单挑选），逐项上游路径与用途见 `manifest.json` 的
    `library.gameIconsSystems`，作者署名见 `ATTRIBUTION.md`。
- 生成资源统一放在 `generated/`，生成提示和最终采用版本记录在 `GENERATION_PROMPTS.md` 与 `manifest.json`。
  绿底草稿的抠绿/去溢色/裁边/降采样配方已脚本化为 `process_generated.py`
  （`python art-src/process_generated.py <批次清单.json>`，残留绿 >0.5% 即非零码失败）；
  2026-09-18 起 B19 弹窗族与 B24 头像框族的草稿批次已按此归档在 `generated/drafts/`，原始图在 `generated/drafts/raw/`。
- 已从 Game-icons SVG 生成 PNG 与 8 列图集，索引见 `game-icons/icons-atlas.json`；SVG 仍是可追溯源文件，PNG 是运行时候选产物。
  `png/` 与 `png/systems/` 的逐图 PNG 为 256×256、黑底白图（渲染配方：resvg 按 `fitTo width=256`
  直接栅格化 —— 2026-09-16 用既有 `wood.png` 复核过，平均逐像素差 0.34/255，仅抗锯齿差异）。
  **新图标接入图集与 `ArtCatalog` 属各自功能批次的事**：图集是 8 列打包 + `icons-atlas.json` 索引，
  改它要连带 `client/assets/resources/ui/generated/icons/**` 与客户端类型，不在这里预做（避免只下发没人消费的资源）。
- `library`（`manifest.json` 顶层）登记的素材是**已下载、尚未接入运行时**的库：取用时按上面同一条路径复制进
  `client/assets/**` 并过体积检查，不要直接引用 `art-src/`。
- `generated/accepted/` 是本项目使用 `gpt-image-2` 生成并完成透明化、裁边、压缩的第一批 UI 与地图资源；`generated/drafts/` 只保留可追溯中间图，不作为运行时输入。
- 微信 release 包体收口时允许在 `client/assets/resources/ui/generated/**` 对 accepted 原图做
  LANCZOS 降采样；原图继续保留在 `generated/accepted/`，禁止只改运行时副本后失去可追溯来源。

## 运行时目录约定

- 首包必需：`client/assets/ui/**`
- 可热更：`client/assets/resources/ui/**`

任何源码资源在接入 Cocos 前都要先做透明通道检查、九宫格边界检查和首包体积检查。
