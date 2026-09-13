# 美术源文件目录

本目录存放**可追溯来源**的原始美术资源，不直接被 Cocos 导入。

- 外部资源按来源分目录，保留原许可证和来源提交号。
- 最终进入客户端包体的资源，必须经过尺寸、格式、图集与署名清单检查后复制到 `client/assets/ui/**`，或放入 `client/assets/resources/ui/**` 走热更边界。
- 当前第一套来源：
  - `kenney/ui-pack-adventure`：CC0，面板、按钮、进度条和箭头。
  - `game-icons/svg`：CC BY 3.0，资源、建筑、兵种与稀有度图标。
- 生成资源统一放在 `generated/`，生成提示和最终采用版本记录在 `GENERATION_PROMPTS.md` 与 `manifest.json`。
- 已从 Game-icons SVG 生成透明 PNG 与 8 列图集，索引见 `game-icons/icons-atlas.json`；SVG 仍是可追溯源文件，PNG 是运行时候选产物。
- `generated/accepted/` 是本项目使用 `gpt-image-2` 生成并完成透明化、裁边、压缩的第一批 UI 与地图资源；`generated/drafts/` 只保留可追溯中间图，不作为运行时输入。
- 微信 release 包体收口时允许在 `client/assets/resources/ui/generated/**` 对 accepted 原图做
  LANCZOS 降采样；原图继续保留在 `generated/accepted/`，禁止只改运行时副本后失去可追溯来源。

## 运行时目录约定

- 首包必需：`client/assets/ui/**`
- 可热更：`client/assets/resources/ui/**`

任何源码资源在接入 Cocos 前都要先做透明通道检查、九宫格边界检查和首包体积检查。
