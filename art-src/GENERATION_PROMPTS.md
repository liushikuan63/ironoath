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

## 8. A10 补最后 9 行无图道具（2026-09-19 生成并收编）

`item.json` 到本轮前只剩 9 行没有图标（背包里是 Graphics 占位方块）。9 行拆成三条路，
**分路依据是行名自己怎么说**，不是"要不要省一次生成"：

**① 2 张原创：主技能秘卷 / 副技能残卷**
名字是"秘卷 / 残卷"两个不同物件，所以各画一个轮廓：主卷 = **铜箍扎起、垂一条红缎签的完整卷轴**，
副卷 = **边缘撕缺、只用麻绳捆住的残片**。这两张**不派生** —— 派生会把"残"这个信息整个丢掉。
与经验书 `item-book-exp-*` 的区分靠形：经验书是叠放的手抄本，技能卷是卷轴。

**② 4 张派生：觉醒石两档 + R/N 碎片两档**
- 碎片 R / N 从 SR 母图 `g3-item-shard_sr-v0.png` 调色：R 苔绿 h92、N 冷灰（饱和 ×0.16）。
- 觉醒石两档**都从同一张红晶母图**（`drafts/a10-item-variants/item-awaken-2-v0.png`）调色：
  初阶 = 铜 h14、高阶 = 金 h23。铁爪座因为明度低、几乎不掉色 ⇒ 两档轮廓 100% 一致，只有晶体在走稀有度色。

理由与 G9 的 hover/disabled 同一条：**两次独立生成必然画成两件形状不同的东西**，并排时读起来是
两个无关道具而不是一条阶梯。首轮这里确实独立生成了两张觉醒石（一簇烟晶原矿 / 一枚爪座红晶），
收编前对着图自查发现"初阶 / 高阶"读不成同族，于是废弃重做为派生；被废的那张留在
`a10-item-variants/` 里当母版，不进包。
N 档取低饱和而不是灰掉：完全去彩会和"未激活"态同形。

命令：`python art-src/derive_rarity_variant.py <母版> <输出> <预设名 | H:Sat:Val>`
（预设 `r/n/awaken_sr/awaken_ssr/scroll_train/scroll_research`；三元组写法留给"不属于稀有度阶梯、
只是要换个色族"的场合，见下面卷轴那一段。）

**③ 5 行零新增纹理**（资源袋 / 金币，A11）：复用启动图集里已有的
`icon:resources/{wood,stone,iron,grain,gold}`，包装量（1 万 / 5 千 / 袋）由行上的名称与数量表达，
不烘焙进图片。这五行一张图都没加。

**顺带修掉一个新量具查出来的旧缺陷**：为 A10 写的 `art-src/check_icon_legibility.py`（把图缩到背包
**真实显示尺寸 26px** 再两两算距离，见收口清单 #228）第一次跑就报
`scroll_research ↔ scroll_train` Δ=11.9、`scroll_build ↔ scroll_{train,research}` Δ≈17.5 ——
**G3 那批三张加速令在 26px 下是撞脸的**：三者的区别只在火漆印图案，而印在 26px 里只占 3~4px。
改成按用途分色（建造 = 原铜黄不动、募兵 = 苔绿 h78、研究 = 钢蓝 h158，母题与印都不动）后
items 族最近跨族对升到 **Δ=33.6**；四族在阈值 12 下全绿，最紧的是
`equip-mount-pojun ↔ equip-mount-xuanwu` Δ=17.1（余量 1.4 倍，下次加装备图要盯这条）。

- 批次清单：`generated/drafts/batch-2026-09-19-a10.json`，绿底原始图在 `raw/2026-09-19/`；
  四张 `residual_green` 全 0.000%，bbox 分别为 (887,599)/(390,890)/(739,638)/(372,895)。
- 收编：`accept_to_runtime.py --drafts art-src/generated/drafts/a10-final --size 128`
  → 六张共 **28.6KB** 进 `items/`；两张改色卷轴另走
  `--drafts art-src/generated/drafts/a10-scroll-recolour`（覆盖 `item-scroll-{train,research}-v1.png`，10.2KB）。
- 登记进 `FAMILY_ASSETS.item`（`shard_n` / `shard_r` 按稀有度升序插在 `shard_sr` 前）与
  `ITEM_ICON_BY_CONFIG`。此后 `tests/ArtFamilies.test.ts` 里那条断言从"记着哪些行还没图"升级为
  **覆盖满 ⇒ item.json 新加一行不登记就红**，反向（映射指到已删行）同时咬住。

## 9. A16 内城构图稿（2026-09-19，概念图，不进包）

一张 16:9 舞台图 + 两张由脚本生成的示意图。生图只负责**构图**，热区与拆层必须是精确图，不能交给生成器：

- 原图：`generated/concepts/a16-city-composition/a16-city-composition-v0-raw.png`（1792×1024，ImageGen 直出，无绿底 —— 它是场景概念，不走抠图管线）。
- 提示词要点（全文见下）：**五区**（王庭高台中上、城门与军事区下部、行政民生右台地、城郊生产带边缘、装饰远景最外圈）；
  **一条视线主轴**（城门 → 市集广场 → 台基长阶 → 主堡）；**明确禁止网格/棋盘/等距槽位/地表贴砖**；
  医院用**原创树形纹 + 药钵**徽记而非受保护的红十字；无 UI / 无文字 / 无数字 / 无水印 / 无发光魔法。
- 产物三件：`a16-city-composition-v0-raw.png`（构图）、`a16-layers.svg`（L0 远景 → L8 HUD 拆层 + 遮挡规则）、
  `a16-hotzones.svg`（36 格 → 非均匀锚点 + 五区地坪 + 主轴路）。后两张由
  `make-a16-diagrams.mjs` 从**同一张锚点表**生成，`--table` 导出文档里那张表 —— 图与表不会各说各话。
- 生成器自带四条自检（锚点数 = 6×6、anchorId 唯一、36 个格位全覆盖、**任一行水平间距全相等即判失败**）。
  最后一条是规格 §3.3 的硬约束"不许把 36 个锚点均匀排成棋盘再称为城景化"；
  植入第 4 行等距 132 实测退 1 并点名行号与间距值，还原后复跑退 0。
- **已知不足（如实）**：① 提示词写了"无人群"，成图里仍有零星人物剪影（箭场、市集各几个点）——
  当舞台图不碍事，但**不能直接当 A17 底图素材**，A17 要重出无人版；
  ② 热区图是**地块分配图**，回答"哪一格属于哪个区、接哪条路"，它的排布仍带弱网格感，
  "像城不像棋盘"这一条由构图稿与 A17 的真实地坪/遮挡承担，等距自检只是必要条件不是充分条件。

## 10. A17 舞台底图三张候选（2026-09-19，已处理并接入运行时）

候选原图落在 **`art-src/generated/drafts/raw/2026-09-19/`（该目录被 .gitignore 排除，不入仓）**，
所以这里把提示词全文留档，保证任何人能重出同一张图；量测数与决策见 `素材缺口清单.md` §十。
收尾处理清单是 `art-src/stage-assets-2026-09-19.json`，处理脚本是
`art-src/process_stage_assets.py`；三张 v0 草稿再经 `accept_to_runtime.py` 收编到
`client/assets/resources/ui/generated/city/`，由 `ArtCatalog` 启动预载、`CityPanelView` 真实消费。

**① 地表平铺砖**（请求 1024×1024，拿到 1024×1024）：
中世纪城景舞台的地表贴图，正俯视、无建筑/无人/无物件/无上方投影；
风化鹅卵石巷道 + 夯土与踩实的泥地 + 草丛与苔痕 + 碎石 + 排水浅沟 + 一块磨损石板地；
暗铁/旧铜/暗红布/灰石的低饱和土色系，统一左上柔光，手绘游戏质感；
**四边必须可无缝平铺**，无边界、无画框、无暗角、无暴露网格的重复图案、**无横贯整幅的直路**。

**② 城墙带**（请求 1536×1024，拿到 1536×1024；纯绿 #00FF00 底，走抠图管线）：
一段风化灰花岗石城墙，顶部城齿 + 短柱支撑的木挑台 + 一座带箭缝的方塔 + 暗红布旗 +
旧铜箍与铁墙锚 + 基部苔痕；左上统一光；**左右直切以便重复拼接**；
无文字/数字/水印/真实国家旗/人物/发光/绿底投影。

**③ 远景山脊背景带**（v0 请求 2560×1080 → **实拿 1024×1024，比例被静默降级**；
v1 改请求 **1792×1024 → 真拿到 1792×1024**，采用 v1）：
分层远山 + 林坡 + 远处小村 + 河谷薄雾，大气透视、冷灰蓝向地平淡出、左上柔光、手绘概念质量；
**山脊线落在画面中下**，**顶 1/4 是低对比淡天**，**底边自然淡成中性雾**（好压在地板层后面）；
无前景建筑/城堡/城墙/人物/飞鸟/粒子/魔法光/文字/水印/画框/暗角。

**档位经验**：要横长图请请求 16:9 的 1792×1024，21:9 的 2560×1080 会被回落成正方形；
生成后**必须回读实际尺寸**再进管线（已记入长期记忆）。

## 11. A18 建筑主体三例母版（2026-09-19，草稿态，未进包）

母版在 `generated/drafts/a18-buildings/`（.gitignore 排除），处理清单 `batch-2026-09-19-a18.json`，
量测与决策见 `素材缺口清单.md` §十一。三张都是**纯平 #00FF00 绿底、无投影、无地台盘**的抠图路线。

- **主堡**（1024²）：高台石堡 —— 中央方塔 + 城齿女墙 + 锥形深板岩顶、四角小塔、铁箍拱门带闸门、
  石造大厅侧翼、窄长窗、暗红布旗、宽 ceremonial 台阶；**全场景最高、轮廓必须压住其他楼**。
- **兵营**（512²）：低矮长方形营区 —— 木构兵舍 + 深板岩顶 + 石砌烟囱、前面栅栏场带**兵器架与训练木人**、
  一侧帆布帐篷、入口小岗亭挂暗红旗；**横向、明显矮于城楼**。
- **农田**（512²）：低伏农庄 —— 茅草顶木谷仓 + 敞开黑门洞、围起的小农地、**前面几块不规则田畦带垄沟与金色茬秆**、
  一车麦束、一个稻草人；**贴地 sprawl，靠耕地而非高度取胜**，且**不许出现整齐矩形田格**。

共同禁项：无文字/字母/数字/水印/Logo/人物/牲畜/魔法光/绿底投影/地台圆盘。
**这一批目视评审发现两处必须重出的缺陷**（详见 §十一）：① 主堡旗面出现"圆环十字"徽记（现实宗教感符号，
规格 §1.2 禁止）；② 母版自带岩石地台与一圈城墙，会与 A17 地坪层叠成浮岛、并让装饰城墙冒充功能 `wall`。
⇒ 下一批提示词必须钉死"旗帜只用原创几何纹（王冠/星/菱形连续纹）"与"底部直接收口、无地台无基座盘"。

## 12. V25 弹窗「铁誓」族 P-01~P-13（2026-10-08 实跑，V25-b 生产轮）

草稿图**不入库**（`.gitignore` 忽略 `vibe_images/` 与 `art-src/generated/drafts/`）⇒ **本节就是可复现物**。
共用段一律逐字取 `art-src/弹窗面板美术风格_VibeCoding规格.md` 的 §4.1（风格锚）、§4.2（九宫格约束，**只有九宫格件加**）、
§4.4（Avoid 负面段）。下面只写每件接在中间的**主体段**与画布尺寸。

**三条本轮换来的通用写法，缺一条就白跑**：
- 九宫格件的边带饰物**必须贯穿整条边或只待在四角**，"居中一块"是拉伸杀手（P-03 v0 实测复发）；
- 铜内线（inlay）要写明"留在边带内侧、别压到图面中线 20% 采样窗"，否则 σ 判据稳定误红（P-03 v0 rel=2.51）；
- **禁写** `filling the canvas nearly edge to edge` —— 会把小方件画成满幅、四角没绿底，抠绿前提直接崩（P-08 v1 实测）。
  正向写法：`floating at about 70% of the canvas, flat pure green reaching all four corners`。

| 件 | 画布 | 主体段（关键句，逐字） | 结果 |
|---|---|---|---|
| P-01 `panel-iron` | 1024×768 | 见 §4.3 原行 | v1 过（上一轮） |
| P-02 `panel-parchment` | 1024×768 | `an aged parchment sheet as the inner face, surrounded by a hammered dark iron frame band with aged bronze corner caps and four rivets per cap, thin bronze inlay line running the inner edge; the parchment's deckle (torn) edges stay strictly inside the frame band and run roughly parallel to it on all four sides` + `the paper face must read dim and desaturated — a greyed tan one step darker than ordinary parchment, so dark text stays readable; no bright cream, no sun-bleached yellow, no glow` | 过（rel 4.47 / 512×373 / 59.0KB） |
| P-03 `panel-warning` | 1024×768 | v0 用 §4.3 原文 ⇒ **边中独立红绶带，判红作废**；v1 改 `a dark oxblood silk stripe painted as a STRAIGHT, CONSTANT-WIDTH horizontal band that spans the ENTIRE top frame band from the left corner cap to the right corner cap, edge to edge` + `Hard prohibitions: NO ribbon ends, NO hanging tails, NO knot, NO rosette, NO fold crossing the middle, NO bronze clasp sitting at the centre, NO highlight blob in the middle, NO short centred segment` + `the frame band is a little wider so the inlay line stays well outside the middle of the plate` | v1 过（rel 3.24 / 512×348 / 49.2KB） |
| P-04 `panel-gilt` | 1024×768 | `an ornate bronze-gilt frame band around a dark hammered iron face; the warm gilt finish is CONTINUOUS and even along all four edges, and the extra gilt scrollwork / raised bead ornament lives only on the four corner caps` + `do NOT place a hanging chain, a wax seal, a medallion, a crown, a gem or any other centred ornament on the middle of any edge — reward chains and wax seals are separate decorative assets laid on top at runtime` | 过（rel 4.79 / 512×357 / 51.9KB） |
| P-05/P-06 `plate-band`（+ active 派生） | 1792×1024 | 上一轮已出；本轮 active **不再生成**，用 `ImageEnhance` brightness 1.16 / color 1.22 派生（alpha 逐像素相同） | 512×52 / 8.9KB；active 10.1KB |
| P-07 `button-iron` | 1792×1024 | 见 §4.3（含 `corners must be square 90 degrees`） | v1 过；hover 6.9KB / disabled 3.9KB |
| P-08 `chip-close` | 1024×1024 | v0 `a dark hammered iron square plate with an aged bronze bevel frame and exactly one rivet at each of the four corners` ⇒ 模型给成**四角大铜凸台**；v1 加 `every ornament must live inside the outer 8% … NO large bronze corner plates, NO big round bosses` ⇒ **满幅无绿底**；v2 再去掉 `filling it nearly edge to edge`、改 `floating in the middle at about 70% of the canvas width, with a wide margin of flat pure green on ALL FOUR sides` | v2 过（52×52 / 2.0KB，填充率 78%，目视四角只有小铆钉点、中心无 × 符号） |
| P-09 `plate-tooltip` | 1792×1024 | `a slightly warmer dark iron strip than the main panels, with a thin aged bronze bevel line along the top and bottom edges only; very shallow, flat, no corner ornament, no rivets, no emblem anywhere` + `displayed only 26-38 pixels tall ⇒ the top and bottom bevels must be bold, about 8% of the sprite height each` + `corners must be square 90 degrees` | 过（256×61 / 4.4KB，填充率 81%） |
| P-10 `banner-crest` | 1792×1024 | 上一轮已出 | 512×234 / 27.2KB |
| P-11 `crest-league` | 1024×1024 | `alliance war-banner emblem — a forked dark-red (oxblood) silk flag hanging from an aged bronze crossbar on a bronze pole, with a small iron fringe along the bottom edge of the cloth, and a blank shield face in the centre of the flag (empty, no charge, no device)` | 过（178×256 / 12.9KB，目视无文字无符号） |
| P-11 族 `crest-nation` | 1024×1024 | 同母版语言，主体换 `a square aged-bronze imperial seal plate seen face-on, with a dark red wax seal impression pressed into its centre; the wax face must stay BLANK (a plain depressed disc with a bronze rim, no carving inside it)` + `no seal-script carving` | 过（246×256 / **30.3KB**，超装饰件旧预算线，见规格 §七 Q7） |
| P-11 族 `crest-battle` | 1024×1024 | `two crossed short spears with iron heads behind a small forked dark-red (oxblood) war pennant, bound at the crossing with an aged bronze ring; the pennant cloth stays blank` + `no skull` | 过（223×256 / 18.4KB）⚠ 中心环内是交叉枪杆，小尺寸可能读成 × ⇒ 规格 §七 Q8 |
| P-11 族 `crest-reward` | 1024×1024 | `a short gold chain looped over an aged bronze crossbar, hanging a folded dark-red (oxblood) silk ribbon with a small warm gilt pendant plate at its foot; the pendant plate face stays completely blank` | 过（207×256 / 16.9KB） |
| P-12 `seal-wax` | 1024×1024 | `a round dark red (oxblood) wax seal blob with irregular pressed edges, an impressed aged bronze ring near its rim, and a completely BLANK depressed centre — plain wax, nothing carved in it` + `no letter, no rune, no character, no monogram, no sunburst` | 过（256×256 / 19.8KB） |
| P-13 `divider-rope` | 1792×1024 | `a braided dark leather cord running horizontally, with two small aged bronze beads slid onto it (one left of centre, one right of centre) and a dark red (oxblood) diamond knot in the exact centre; both ends taper and fade to nothing` + `a very wide, very thin horizontal strip … its height only about one sixth of its width` + `the cord must stay perfectly straight and horizontal` | 过（512×40 / 5.1KB） |

**尺寸口径**：交付尺寸 = 该件 keyed 草稿的 **PIL `alpha>0` `getbbox()`** 反算（与 `accept_to_runtime.py` 同一把尺），
长边按档取 A/B/装饰横件 512、C 按钮与提示条 256、方 chip 52、顶饰 256。
用 `>160` 的 mask 量会系统性偏小，10:1 以上长条差 5px 就是 10% 比例误差 ⇒ 会被">3% 直接失败"拒（本轮真实撞过 4 件）。
