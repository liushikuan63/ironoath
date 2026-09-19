# 生成美术提示词

生成目标是与现有暗红、铜金、冷兵器乱世风格统一的横屏 SLG 资源，不带文字、不带水印、不带现代 UI 元素。

## 1. 主面板九宫格

```text
Use case: stylized-concept
Asset type: 2D mobile strategy game UI nine-slice panel
Primary request: ornate dark iron and aged copper panel frame for a medieval Chinese-inspired strategy game
Composition/framing: wide rectangular panel, clean empty center, thick readable border, matching corner geometry
Color palette: charcoal brown, aged bronze, muted dark red accents
Materials/textures: hammered iron, worn leather, subtle soot, crisp game-ready alpha edges
Constraints: no text, no letters, no symbols, no watermark, seamless nine-slice edges, centered symmetrical frame
Avoid: blue sci-fi accents, neon, glossy mobile-game gradients, photorealistic perspective
```

## 2. 按钮状态组

```text
Use case: stylized-concept
Asset type: 2D strategy game UI button sprite sheet
Primary request: primary, pressed, disabled, and hover states for a medieval copper-and-iron command button
Composition/framing: four separate wide buttons, equal size, centered, generous padding for Chinese labels
Color palette: dark charcoal base, warm copper border, muted brick-red pressed state, desaturated disabled state
Materials/textures: aged metal and leather, readable at small size
Constraints: no text, no letters, no watermark, transparent background, consistent nine-slice-safe edges
Avoid: glossy candy buttons, neon glow, rounded mobile app styling
```

## 3. 世界地图地块图集

```text
Use case: stylized-concept
Asset type: top-down strategy game terrain tile atlas
Primary request: seamless square terrain tiles for grass, forest, rocky hills, mountain, river, farmland, dirt road, and wasteland
Composition/framing: 4 by 4 atlas, each tile exactly the same square size, flat orthographic top-down view, tile edges must join cleanly
Color palette: muted olive green, umber, charcoal stone, desaturated water blue, dusty grain gold
Materials/textures: painterly game art, restrained detail, readable when zoomed out
Constraints: no text, no icons, no characters, no watermark, no borders between cells, seamless edges
Avoid: realistic satellite imagery, cartoon candy colors, perspective buildings, high-contrast noise
```

## 4. 地图实体图标组

```text
Use case: stylized-concept
Asset type: top-down strategy game map entity sprites
Primary request: separate player city, neutral monster camp, resource point, alliance building, and marching army markers drawn in the same medieval military style
Composition/framing: one centered object per image, orthographic top-down, readable silhouette, transparent background
Color palette: dark red player city, charcoal monster camp, moss green resource point, steel blue alliance building, aged gold march marker
Materials/textures: painted metal, timber, stone, cloth banners, game-ready edges
Constraints: no text, no letters, no watermark, no map base tile, transparent background
Avoid: perspective camera, modern icons, excessive glow, tiny unreadable detail
```

生成后的第一轮只做低/中质量草稿筛选，采用版本再进入 `art-src/generated/accepted/`，淘汰版本不打包。

## 5. 礼包弹窗素材族（B19，2026-09-18 生成草稿）

五件均为**纯绿底（#00FF00）单物体**，共用同一段风格约束：

```text
Use case: stylized-concept
Asset type: 2D mobile strategy game gift/popup sprite
Primary request: <见下逐件主体>
Color palette: charcoal brown, aged copper-gold, muted brick-red accents
Materials/textures: hammered iron, aged bronze, worn leather, painted silk ribbon
Background: completely flat solid pure green (#00FF00), no cast shadow on it, crisp painted alpha edges
Constraints: single centered object, generous padding, no text, no letters, no numbers, no watermark
Avoid: neon, glossy candy mobile styling, blue sci-fi accents, photorealism
```

- `b19-gift-first-charge-v0`：暗铁铜边宝箱满溢金币，缠暗红绸带，箱口有暖光（首充档封面）
- `b19-gift-monthly-card-v0`：铜铁纪念圆牌，中央浮雕新月，顶部皮带环（月卡封面）
- `b19-gift-battle-pass-v0`：竖长暗铁令牌，铜边缺口顶，浮雕交叉长枪与小冠，暗红绳挂（战令封面）
- `b19-gift-pack-generic-v0`：铜箍木箱缠暗红缎带蝴蝶结，缝里露金币（通用礼包图标）
- `b19-gift-voucher-v0`：羊皮+暗红布代金券，两端半圆缺口，中央铜火漆星印（代金券图标）

## 6. 头像框素材族（B24，2026-09-18 生成草稿）

四件为**方形装饰环、中心完全留空（绿底，抠图后透明）**，档位差异靠材质与纹样：

- `b24-avatar-frame-iron-v0`：暗铁窄环 + 四角铆钉（金币款·低档 800）
- `b24-avatar-frame-copper-v0`：铜环刻云雷纹 + 深色铜角帽（金币款·高档 1500）
- `b24-avatar-frame-season-v0`：暗铁底缠古金桂冠、顶部小冠浮雕、下两角霜蓝雪花（赛季限定 500 赛季币）
- `b24-avatar-frame-battlepass-v0`：锤面暗铁环缠暗红漆、边中铜钉、底交叉短枪、顶令牌缺口纹（战令付费线外观）

## 已采用

- `ui-panel-kingdom-v1.png`：1024×1024 低质量草稿，抠绿、裁边后压到 512×354。
- `ui-button-command-v1*.png`：按钮母版抠绿、裁边后压到 512×191，并派生 hover / pressed / disabled。
  **2026-09-19 已退出运行时**（母稿留这里）：端帽 54 / 边框 40 决定了它最小只能画 120×88，
  而全仓库 24 个按钮消费点高度都只有 26~34 —— 它从来没有被九宫格画过，四张 300KB 变成零消费装饰；
  接班的是下面 G9 的薄边 chip。判据见 `tools/verify-art-runtime.mjs` 的 `degenerateSlices`（收口清单 #216）。
- `map-terrain-atlas-v1.png`：4×4 地块图集，最终压缩到 512×512，单元格 128×128。
- `map-player-city-v1.png`、`map-monster-camp-v1.png`、`map-resource-point-v1.png`、`map-alliance-building-v1.png`、`map-march-marker-v1.png`：3×2 母版拆成 5 张 256×256 透明 PNG。

## 草稿批次 2026-09-18（B19 弹窗族 ×5 + B24 头像框族 ×4，未采用）

- 原始图（绿底 1024×1024）：`generated/drafts/raw/2026-09-18/*-raw.png`，逐张与生成清单的映射在 `generated/drafts/batch-2026-09-18.json`。
- 处理配方已脚本化：`python art-src/process_generated.py art-src/generated/drafts/batch-2026-09-18.json`
  （抠绿 alpha 过渡 → 去绿边溢色 → 裁边留 6px → 方形化 → LANCZOS 压到 512×512；残留绿像素 >0.5% 即非零码失败）。
- 产物在 `generated/drafts/b19-gift/` 与 `generated/drafts/b24-avatar-frames/`，九张残留绿均 0.000%、四张框中心 alpha 全 0；
  **尚未筛选采用**，采纳后进 `accepted/` 并按功能批次接 `ArtCatalog`。

## 7. 整体界面缺料 G1~G7（2026-09-18，全 UI 构建用）

范围与判据见 `素材缺口清单.md`；共用 §5 的绿底风格约束，两处与单图不同的硬规则：

- **母版拆格**（G2 装备 4×4、G3 道具 4×4、G4 活动 4×2、G5 学派 2×2、G6 战令 2×1、G7 头像 2×2）：
  提示词必须写明"每格一个物体、包围盒在水平与垂直投影上互不重叠、格间留宽绿带"——
  首版 G4 母版因长矛斜穿投影列失败（脚本按绿色间隔带投影切格，分段数≠网格数即报错，绝不静默等分），
  重生成 v1 通过。拆格产物逐格再裁边方形化到 512。
- **半身立绘**（G1 ×12）：允许主体触底（胸部截断），抠绿取样点因此用"上边+两侧 5 点"而非四角。

| 族 | 内容 | 产物目录 |
|---|---|---|
| G1 | hero.json 12 武将半身立绘（SSR 金甲/SR 铜甲/R 铁甲/N 布衣，档位靠甲胄华朴） | `drafts/g1-hero-portraits/` |
| G2 | equip.json 16 行 = 4 槽（武器/甲/马/符节）× 4 家族（铁/破军/玄武/文曲） | `drafts/g2-equip-icons/` |
| G3 | 道具 16：加速令×4、建造/募兵令、抽卡券、武将/资源匣、SR/SSR 碎片、免战/封库/集结、经验书 S/M | `drafts/g3-item-icons/` |
| G4 | 赛季币 + activity.json 8 行活动图标 | `drafts/g4-currency/`、`drafts/g4-activity-icons/` |
| G5 | 农/军/商/工四学派徽记 + 国旗底样（中央留空可换纹） | `drafts/g5-school-crests/`、`drafts/g5-nation/` |
| G6 | 战令免费/付费双轨徽、抽卡横幅背景（plain 直存 1280×731，不抠绿）、限时绶带角标 | `drafts/g6-*/` |
| G7 | 默认头像 4：男女君主/盟管/系统信使 | `drafts/g7-avatars/` |
| G8 | 底部导航页签 2 态：暗铁铆钉常态 + 金框红底选中态，**按格子比例 4:3 画满整幅**（不走九宫格，整图等比缩放） | `drafts/g8-nav-tab/` |
| G9 | 薄边小按钮 chip 3 态：整幅 3.08:1 长条，铜色斜面边框约占高度 7.1%、四角铆钉；hover 与 disabled **都由常态派生**（`derive_button_states.py`，亮度/饱和两组系数写死在脚本里），不再生成第二张以免造型漂移。第一版边框只占 1.7%，铺到 32px 高的格子会消失，作废重出 | `drafts/g9-button-chip/` |

- 批次清单：`generated/drafts/batch-2026-09-18-ui-full.json`（G4 已钉到 v1 母版）；运行日志 `batch-run.log`，全量 `ALL_OK`。
- **G8 批次清单（2026-09-19）**：`generated/drafts/batch-2026-09-19-g8-nav-tab.json`，两张都 `residual_green=0.000%`。
  收编用 `accept_to_runtime.py --size 128x96` —— **`--size` 现在支持 `WxH`**：非方形目标会先按 alpha bbox
  裁掉方形化补的透明边再铺满，且素材自身长宽比与目标差超过 3% 直接失败（防止把图压扁收进包）。
- 已知取舍：经验书 L 档复用 M 档图（母版只画了单本与三本两格）；G3 碎片刻意不用紫/蓝稀有度色（与头像框同族语言）。
- **接线轮（同日第二轮）**：G2 全部 16 + G3 的 13 张已量化进 `client/assets/resources/ui/generated/{equip,items}/`
  并接 `BagPanelView`；G1 的 12 张立绘（256px）进 `heroes/` 并接 `HeroPanelView`（真跑判据见 `素材缺口清单.md` §五）；
  `scroll_march`、`decree_×2` 无消费点留在草稿。
