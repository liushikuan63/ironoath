# 弹窗面板美术风格 · Vibe Coding 规格（V25 · 代号「铁誓 UI」）

> 依赖：**无功能依赖**（可与 V22-b / V22-c 并行，领地只在 `client/assets/scripts/scene` + `game/ui` + `art-src`）
> ｜ 落地拆分：**V25-a…V25-e**（见 §五）｜ 并行：**否**（一次改 32 个视图文件，全量探针必须整体重跑）
>
> **一句话风险**：本仓已经有过一次「素材做出来却画不上去」的事故——`ui-button-command-v1` 端帽 54 / 边框 40，
> 而全仓 24 个按钮消费点高度只有 **26~34**，四张 300KB 图从来没有被九宫格画过一次，已退出运行时
> （原文与判据见 `art-src/GENERATION_PROMPTS.md` 「已采用」段 + `tools/verify-art-runtime.mjs:765-772`）。
> ⇒ **本方案的第一约束不是好不好看，而是「边厚 ÷ 消费尺寸」**。§二 的三档契约就是为防止重演而存在的，
> 任何素材复用跨档，一律按缺陷处理，不按"观感差异"处理。

---

## 〇、现状（2026-10-07 现跑读数；引用前请再现跑一次）

统计方法：正则按行抽 `roundRect(x, y, w, h, r)` 的第 3/4 参，同文件 `const` 数字表解析；参数是运行时表达式的会落进"待人工判档"，**不当已判**。

| 维度 | 现跑读数 | 证据 |
|---|---|---|
| 圆角矩形绘制点 | `roundRect` **133 处 / 32 文件** | `client/assets/scripts` 全量 grep |
| 直角矩形绘制点 | `.rect(` **85 处**；`fillRect` **3 处（全在 1 个文件）** | `scene/GiftPopupView.ts:147` |
| 真素材上屏的唯一入口 | `applySlicedSprite` **只有 5 处调用** | `scene/ArtCatalog.ts:382`、消费点 `CityPanelView.ts:1146`、`MarchPanelView.ts:195` |
| 已有面板贴图 | **1 张**：`ui/generated/ui/panel-kingdom-v1.png`（384×266 RGBA **170.9KB 未量化**，meta 四向 border=44） | 该图 `.png.meta` 的 `borderTop/Bottom/Left/Right` |
| 面板底色常量 | `COLOR_PANEL` **17 处各自定义、5 个不同取值**（主流 `new Color(40,33,27,255)` 占 12 处；离群 `(36,30,25,250)`/`(38,30,22,245)`/`(38,31,26,255)`/`(43,36,29,255)`） | 各 `*PanelView.ts` 头部 |
| 遮罩色 | **6 个不同取值**，alpha 从 160 到 238 不等 | 各视图 `COLOR_MASK` |
| tokens 文件 | **不存在**（无 Theme/tokens，色值散成 333 处局部常量） | `client/assets/scripts` 全量 |
| 标题栏 | 全是**裸 Label，无底色块**：`GiftPopupView.ts:153`（26 号铜金）、`NationPanelView.ts:262`（22 号金，左对齐）、`BagPanelView.ts:291`（22 号铜金） | 同左 |
| 关闭键 | **两形态并存**：文字 `×` 裸节点 36×36 无 Graphics（`GiftPopupView.ts:180-183`、`StaminaDetailOverlay.ts:101`）vs Graphics 圆角 + 文字「关闭」（`NationPanelView.ts:945-959`、`CreditsOverlay.ts:113`、`EquipPanelView.ts:237`、`TechPanelView.ts:303`、`GachaHistoryView.ts:114`、`MarchPanelView.ts:222`） | 同左 |
| 字体 | 统一入口已存在且可用，**本轮不动** | `scene/UiFont.ts:9-20` |

**用户诉求的物证**：礼包弹窗底板 = `fillRect` 直角实心矩形（`GiftPopupView.ts:145-147`），标题 = 浮在矩形上的一行字，关闭 = 一个 `×` 字符。这就是"方方正正的框框"的字面含义。

### 消费尺寸三档实测（决定素材边厚上限）

| 档 | 实测消费尺寸 | 出现值（高） | 位置样例 |
|---|---|---|---|
| **A 主弹窗底板** | 460×300 ～ 720×600 | 300 / 356 / 420 / 430 / 460 / 600 | `GiftPopupView.ts:30-31`（460×300）、`ChoiceOverlay.ts:63`（w×430）、`MarchPanelView.ts:200`（720×356）、`OfflineReportOverlay.ts:25-26` |
| **B 条行 / 卡片** | 高 40 ～ 96 | 40 / 44 / 46 / 52 / 56 / 58 / 60 / 62 / 66 / 74 / 96 | `BagPanelView.ts:541`（680×46）、`SocialPanelView.ts:1213`（680×52）、`HeroPanelView.ts:351`（680×96） |
| **C 小件（chip / 按钮 / 角标）** | 高 **26 ～ 38** | 26 / 28 / 30 / 32 / 34 / 38 | `ChoiceOverlay.ts:177`（120×38）、`OfflineReportOverlay.ts:143`（180×38）、`PanelNav.ts` 导航格 |

> ⚠ 上一版把「一张框打天下」的想法用在 C 档上，就产出了零消费的 `ui-button-command-v1`。
> 另一条同源经验：G9 的 chip 第一版边框只占图高 **1.7%**，铺到 32px 高的格子上边框直接消失、作废重出
> （`art-src/GENERATION_PROMPTS.md` §7 G9）。⇒ **C 档素材的可见边厚下限 = 图高的 7%，上限 = 消费高的 25%**。

---

## 一、风格锁定：「铁誓」材质语法（2026-10-07 拍板，A 案）

**一句话基调**：暗铁为骨、古铜为框、羊皮为面、暗红为号——弹窗是一块**挂在城墙上的军令牌**，不是一个网页对话框。

材质语法六条，逐条都是可核对的：

| # | 部位 | 必须满足 | 明确不接受 |
|---|---|---|---|
| 1 | 底板 | 锤面暗铁为底 + 古铜包角（四角各一块角帽）+ 角帽上铆钉；中心区必须是**纯净无装饰的深色面**（九宫格可切） | 纯色填充矩形、圆角矩形、现代卡片阴影、玻璃拟态 |
| 2 | 内衬 | 长文内容（礼包说明、离线战报、鸣谢）用羊皮纸内衬，纸面有纤维与烧边，但**不遮字**：纸亮度只提一档，靠深字 tokens 保对比度 | 满屏米黄、纸纹花到影响读字、纸面压住数值 |
| 3 | 标题 | 铜框匾额横幅（整图非九宫格，中央留空放字），压住底板上沿中线 | 浮空一行字没有承载物、把标题烘焙进素材（违反"不带文字"铁律） |
| 4 | 顶饰 | 按语义换**小顶饰**（见下表），只出材质不出文字 | 每张面板临时决定装饰、用 emoji 或几何色块充当顶饰 |
| 5 | 按钮 | C 档薄边铁钮（4px 级），主按钮铜面高光，禁用态降饱和不降形状 | 现代 app 的圆角胶囊、糖果渐变、发光描边 |
| 6 | 遮罩 | 全场统一一个值（见 §六 tokens），带极轻的暗角 | 每个面板自己定 alpha（现状是 6 个值并存） |

**语义顶饰映射**（决定 V25-b 要出哪几张顶饰，不靠临场发挥）：

| 语义 | 顶饰 | 落位视图（现跑文件名） |
|---|---|---|
| 信息 / 列表 | 无顶饰，仅匾额 | `BagPanelView`、`HeroPanelView`、`QuestPanelView`、`TechPanelView` |
| 确认 / 警示 | 铁框 + 暗红绶带角 | `ChoiceOverlay`、`SocialCreateOverlay`、`LineupEditOverlay` |
| 奖励 / 礼包 | 金链绶带 + 火漆印 | `GiftPopupView`、`ShopPanelView`、`GachaHistoryView` |
| 联盟 | 旗帜纹章 | `SocialPanelView`、集结类面板 |
| 国家 | 玉玺火漆 | `NationPanelView`、`AvatarFramePanelView` |
| 战斗 | 交叉长枪 | `BattleReportPanelView`、`MarchPanelView`、`MarchComposeOverlay` |

**色族**（起点值，不是定案——V25-b 必须目视校准后写回本表；不改字体、不改场景美术基线）：

| 角色 | 起点值 | 依据 |
|---|---|---|
| 遮罩 | `Color(8, 6, 5, 190)` | 收敛现跑 6 个值到 1 个，取中位 alpha |
| 底板铁面 | `Color(22, 18, 16, 255)` | 现跑基线（`BagPanelView.ts:32-35`），素材缺失时的兜底色 |
| 面板兜底 | `Color(40, 33, 27, 255)` | 现跑 17 处里的主流值（12 处），保留以免一次性推翻 |
| 铜金高光 | `Color(184, 134, 11, 255)` | 现跑基线（`BagPanelView.ts:36`），与内城规格 §1.1「铜金集中给王权/交互」同一条 |
| 羊皮纸面 | 候选 `#D9C89E` ～ `#C9B482` 区间，**待定** | 新值，必须与铜金同色相族且深字对比度 ≥ 4.5:1，实测后定案（§七 Q2） |
| 暗红号色 | 候选 `#7B2B25` ～ `#5E1F1B` | 同上，与现有暗旌旗同族 |

---

## 二、三档素材契约（防「零消费素材」的硬判据）

判据公式（与 `tools/verify-art-runtime.mjs:765-772` 同一条，另加安全余量）：

- **不退化**（既有门）：`contentWidth ≥ insetLeft + insetRight` 且 `contentHeight ≥ insetTop + insetBottom`；
- **不吃内容**（本批新增，V25-e 进门）：同一组不等式右侧 ÷ 尺寸 ≤ **0.6**，否则边框吃掉可读区；
- **可见下限**（G9 教训）：边厚 ÷ 图高 ≥ **0.07**。

| 档 | 素材长边 | border（L/R · T/B） | 中心净区 | 消费尺寸下限 | 复用禁令 |
|---|---|---|---|---|---|
| **A 底板** | 512×344 ～ 512×373（**逐件现量**，见下表） | ~~48 · 36~~ → **80 · 72**（2026-10-08 裁决，理由见 §4.8 第 1 条） | 352×200 | 460×300（现跑最小底板） | 不得用于 B/C |
| **B 条行** | 512×52（实测，原写 512×48 是被 `>160` 的 bbox 骗了） | **12 · 8** | 488×36 | 180×40（现跑 B 档最矮） | 不得用于 C |
| **C 小件** | 256×65（按钮 / 提示条）· 52×52（方 chip） | **6 · 4** | 244×57 | 64×26（现跑 C 档最小） | 不得用于 A/B |
| 装饰件 | 整图（非九宫格），**每件按自己 bbox 定 WxH** | 不适用 | — | 按原比例缩放，比例差 >3% 直接失败（`accept_to_runtime.py` 现有判据） | 不参与退化判据 |

**18 件实测交付尺寸与量化体积（2026-10-08 现跑，`process_generated.py` → `accept_to_runtime.py` 全管线，产物落临时目录未进包）**：

| 件 | 档 | 交付尺寸 | 量化后 | 件 | 档 | 交付尺寸 | 量化后 |
|---|---|---|---|---|---|---|---|
| `panel-iron-v1` | A | 512×359 | 51.9KB | `crest-league-v1` | 装饰 | 178×256 | 12.9KB |
| `panel-parchment-v1` | A | 512×373 | 59.0KB | `crest-nation-v1` | 装饰 | 246×256 | **30.3KB** |
| `panel-warning-v1` | A | 512×348 | 49.2KB | `crest-battle-v1` | 装饰 | 223×256 | 18.4KB |
| `panel-gilt-v1` | A | 512×357 | 51.9KB | `crest-reward-v1` | 装饰 | 207×256 | 16.9KB |
| `plate-band-v1` | B | 512×52 | 8.9KB | `seal-wax-v1` | 装饰 | 256×256 | 19.8KB |
| `plate-band-active-v1` | B | 512×52 | 10.1KB | `divider-rope-v1` | 装饰 | 512×40 | 5.1KB |
| `button-iron-v1` | C | 256×65 | 5.4KB | `banner-crest-v1` | 装饰 | 512×234 | **27.2KB** |
| `button-iron-hover-v1` | C | 256×65 | 6.9KB | `chip-close-v1` | C | 52×52 | 2.0KB |
| `button-iron-disabled-v1` | C | 256×65 | 3.9KB | `plate-tooltip-v1` | C | 256×61 | 4.4KB |

⇒ 全套合计 **≈400KB**，在 §二 "新增全套 ≤450KB" 内；A/B/C 三档逐张都达标。
**唯一被实测否证的预算线**：装饰件 ≤20KB/张 —— `crest-nation` 30.3KB、`banner-crest` 27.2KB 都在 255 色量化后仍然超，
因为这两张是"满幅铜纹 + 龙纹浮雕"，细节密度天然高。⇒ **装饰件预算改 ≤32KB/张**（其余 5 张仍 ≤20KB），
不改量化参数也不减细节：为凑 20KB 去压色数，会在暗红丝绒上出明显色阶。

⚠️ **A 档 border 80·72 是本轮裁决，尚未在真机上复验**：`client/assets/scripts/game/art/ArtFamilies.ts:55`
的 `PANEL_IRON_INSET` 现值仍是 `{left:48, right:48, top:36, bottom:36}`，且已被 `MarchComposeOverlay.ts` 消费。
**本格不改那个文件**（V25-c 领地），交接项＝V25-c 把 meta 的 `border*` 与 `PANEL_IRON_INSET` **同批**改成 80·72
并用 `tools/verify-ui-v25-runtime.mjs` 回读 inset 才算落地（记忆已证：border 有两处可写、后写者赢）。

现跑最小消费尺寸套上去自检：A 460×300 用 **72** 顶底 ⇒ 144/300 = **0.48** ≤ 0.6 ✓（border 从 36 抬到 72 后这条仍成立，但只剩 0.12 的余量 —— 460×300 是最小的一档，V25-c 实拍必须专门看它）；
C 64×26 用 4 顶底 ⇒ 8/26 = 0.31 ≤ 0.6 ✓ 且 4/52 = 0.077 ≥ 0.07 ✓。
（A 档的 512×344 不是拍脑袋：它是 §4.6 首版实测的出图比例 1.487:1，改这个数是因为 `accept_to_runtime.py` 的 >3% 比例判据会拒 4:3。B 档 512×48 同理，实测出图 10.755:1，见 §4.7。）
⚠️ **96 高的卡片格（`HeroPanelView.ts:351` 的 680×96）另案**：它名义上落在 B 档区间里，但拿 48 高的条行图放大两倍去铺它会糊 ⇒ V25-d 现跑那一处消费点之后单独定尺寸，不许顺手复用。

**包体预算**（现跑基线：`resources/ui/generated/` 共 4.6MB，其中 `ui/` 子目录 224KB；小件已量化到 2.4~4.5KB 的 `P` 模式，`panel-kingdom-v1` 是 RGBA 170.9KB 的**存量例外**）：

- 新增全套目标 ≤ **450KB**（A 档 ≤80KB/张、B 档 ≤20KB/张、C 档 ≤8KB/张、装饰件 ≤20KB/张）；
- 单张 > 120KB 必须点名并说明为什么不能量化（软边/半透明是合法理由，"忘了跑 `accept_to_runtime.py`" 不是）；
- `resources` 已配微信小游戏分包，不进首包（`ArtFamilies.ts:47-52` 注释 + 收口清单 #196）。

---

## 三、素材清单（18 张 = AI 母版 11 + 派生 3 + 复用现有 4）

命名沿用现跑规律：`<件>-v1.png`，ArtKey 点分，注册在 `scene/ArtCatalog.ts` 的 `SPECS` 与 `game/art/ArtFamilies.ts`。

| # | ArtKey | 资源路径 | 档 | 产出方式 |
|---|---|---|---|---|
| 1 | `ui.panel.iron` | `ui/generated/ui/panel-iron-v1` | A | AI 母版（§四 P-01） |
| 2 | `ui.panel.parchment` | `ui/generated/ui/panel-parchment-v1` | A | AI 母版（P-02） |
| 3 | `ui.panel.warning` | `ui/generated/ui/panel-warning-v1` | A | AI 母版（P-03，与 P-01 同构图） |
| 4 | `ui.panel.gilt` | `ui/generated/ui/panel-gilt-v1` | A | AI 母版（P-04，奖励/礼包） |
| 5 | `ui.plate.band` | `ui/generated/ui/plate-band-v1` | B | AI 母版（P-05） |
| 6 | `ui.plate.band.active` | `ui/generated/ui/plate-band-active-v1` | B | 由 5 派生或 AI（P-06，二选一见 §七 Q3） |
| 7 | `ui.button.iron` | `ui/generated/ui/button-iron-v1` | C | AI 母版（P-07） |
| 8 | `ui.button.iron.hover` | `ui/generated/ui/button-iron-hover-v1` | C | `derive_button_states.py`（brightness 1.30 / color 1.18） |
| 9 | `ui.button.iron.disabled` | `ui/generated/ui/button-iron-disabled-v1` | C | `derive_button_states.py`（brightness 0.62 / color 0.25） |
| 10 | `ui.chip.close` | `ui/generated/ui/chip-close-v1` | C | AI 母版（P-08，**不含 × 符号**，× 仍由 Label 画） |
| 11 | `ui.plate.tooltip` | `ui/generated/ui/plate-tooltip-v1` | C | AI 母版（P-09） |
| 12 | `ui.banner.crest` | `ui/generated/ui/banner-crest-v1` | 装饰 | AI 母版（P-10，标题匾额） |
| 13 | `ui.crest.league` | `ui/generated/ui/crest-league-v1` | 装饰 | AI 母版（P-11） |
| 14 | `ui.crest.nation` | `ui/generated/ui/crest-nation-v1` | 装饰 | 与 P-11 同母版换纹 |
| 15 | `ui.crest.battle` | `ui/generated/ui/crest-battle-v1` | 装饰 | 同上 |
| 16 | `ui.crest.reward` | `ui/generated/ui/crest-reward-v1` | 装饰 | 同上 |
| 17 | `ui.seal.wax` | `ui/generated/ui/seal-wax-v1` | 装饰 | AI 母版（P-12，火漆印） |
| 18 | `ui.divider.rope` | `ui/generated/ui/divider-rope-v1` | 装饰 | AI 母版（P-13，横向整图） |
| — | `ui.panel.kingdom`（存量） | `ui/generated/ui/panel-kingdom-v1` | A | **保留**：`CityPanelView`/`MarchPanelView` 已在用，且 `PANEL_FRAME_BAND` 与 meta 由 `client/tests/ArtFamilies.test.ts` 对账；换它 = 另开一格（§七 Q1） |
| — | `ui.button.chip*` / `ui.nav.tab*`（存量 5 张） | 同目录 | C | 保留，本轮不动（避免碰导航与 chip 的既有探针读数） |

**生产进度（2026-10-08 07:1x 现跑）：18 / 18 件全部出齐**，逐件过 `art-src/check_nineslice_ready.py` 对应档（退 0）+ 逐件目视
（取证图由量具 `--crop-preview` 按 alpha bbox 定位生成）+ 逐件现量 bbox 定交付尺寸（尺寸与量化体积见 §二 表）。

| 状态 | 件 |
|---|---|
| 已出并过判据（14） | #1 `panel-iron`、#2 `panel-parchment`、#3 `panel-warning`（**v0 判红，v1 过**）、#4 `panel-gilt`、#5 `plate-band`、#7 `button-iron`（v0 斜切作废 → v1）、#10 `chip-close`（**v0 角饰过大、v1 无绿底，v2 才过**）、#11 `plate-tooltip`、#12 `banner-crest`、#13~16 `crest-league/nation/battle/reward`、#17 `seal-wax`、#18 `divider-rope` |
| 派生（3） | #6 `plate-band-active`（brightness 1.16 / color 1.22，**§七 Q3 已裁：走派生不再出第二张母版**）、#8/#9 `button-iron-hover/disabled`（`derive_button_states.py`） |
| 作废重出（3 版留证） | `panel-warning-v0`（边正中一块暗红绶带 = §4.6 修正 ② 那个缺陷在新素材上复发）、`chip-close-v0`（角部铜凸台占 25% 图宽，切分线必穿）、`chip-close-v1`（满幅无绿底，抠绿前提不成立） |

**P-07 的 `corners must be square` 已复验生效**（v0 斜切 0% → v1 直角 75%，见 §4.7）；本轮它在新出的 `chip-close-v2`（78%）
与 `plate-tooltip-v0`（81%）上继续成立 ⇒ **n 从 2 涨到 4，阈值 50% 未再被逼近**（最高 92%、最低 75%，中间仍是 0% 那类真斜切）。
⚠️ **交付尺寸不许套同一张表**：实测 bbox 已见 **1.371 / 1.433 / 1.470 / 1.425 / 9.827 / 3.938 / 0.992 / 4.189 / 2.188 /
0.697 / 0.963 / 0.871 / 0.808 / 1.016 / 12.700** 十五种，互不相同 ⇒ 每件按自己的 bbox 定尺寸，
否则会被 `accept_to_runtime.py` 的"比例差 >3% 直接失败"逐个拒掉。
⚠ **而且必须用 `accept_to_runtime.py` 同一把尺量**（PIL `alpha>0` 的 `getbbox()`）：量具的 `alpha_of>160` 会漏掉抠绿过渡沿
的半透明一圈，普通件差 0.001 无所谓，**10:1 以上的长条差 5px 就是 10% 比例误差** ⇒ 本轮四件（条行 / 按钮 / 提示条 / 绳结）
第一次收编全被拒，换成同一把尺后 14/14 退 0（取证见 §4.8 第 3 条）。

**接入纪律**（`game/art/ArtFamilies.ts:47-52` 原文要求）：只登记**已随包下发且已有消费面板**的族——不许先把 18 张全塞进 `FAMILY_ASSETS` 等消费点。V25-b 只生产，V25-c/d 接一个登记一个。

---

## 四、AI 生图规范（整段可复制）

### 4.1 共用风格锚（每件都带，一字不改地放在提示词开头）

```text
Use case: stylized-concept
Style anchor: medieval Chinese-inspired strategy game (iron, aged bronze, dark oxblood banners),
painterly hand-drawn game UI, NOT flat vector, NOT glossy mobile app styling
Lighting: single key light from upper-left, matching the city scene's light direction
Background: completely flat solid pure green (#00FF00), no cast shadow on it, crisp painted alpha edges
```

### 4.2 九宫格专属约束（**只有九宫格素材加，装饰件不加**——这是能否用起来的命门）

```text
Nine-slice constraints (mandatory):
- Ornamentation lives ONLY inside the outer band: corners carry the bronze caps and rivets,
  edge midpoints carry a plain repeating iron/leather texture with NO distinctive features at the seam
- The inner ~60% of width and height must be a clean, uniform, decoration-free surface
  (nothing that would tile or repeat visibly when the frame is stretched)
- Corners are mirror-symmetric across both axes; top and bottom bands are mirror images
- Do not draw any drop shadow or glow outside the frame edge (it would be sliced away)
- The frame band must read **uniform along its whole length except at the four corners**
  (no centred medallion, no ribbon block, no emblem sitting at the middle of an edge —
  a mid-edge feature gets stretched into a smeared blob on a wide panel)
```

### 4.3 逐件主体提示词

| 编号 | 主体（接在 4.1 + 4.2 之后） |
|---|---|
| **P-01** `panel-iron` | `Asset type: 2D mobile strategy game UI nine-slice panel, delivered at 512x344 (about 3:2)` · `Primary request: hammered dark iron panel plate with aged bronze corner caps and four rivets per cap, thin bronze inlay line running the inner edge` · `Color palette: charcoal brown, aged bronze, muted dark red accents` |
| **P-02** `panel-parchment` | 同上尺寸 · `aged parchment sheet as the inner face, iron-and-bronze frame band around it, deckle edges kept inside the band`（**约束**：纸面必须比正文暗一档，防止与浅字打架） |
| **P-03** `panel-warning` | 同 P-01 构图，**原文 `a dark oxblood silk ribbon folded across the top band and a bronze clasp at each end` 与 §4.2 直接冲突，已改**：`a dark oxblood silk stripe painted as a STRAIGHT, CONSTANT-WIDTH band spanning the ENTIRE top frame band edge to edge, terminating at the two top corner caps` + 硬禁项 `NO ribbon ends, NO tails, NO knot, NO rosette, NO fold crossing the middle, NO clasp sitting at the centre`（v0 按原文出图 ⇒ 顶边正中一块独立红绶带，正是 §4.6 修正 ② 那个缺陷在新素材上复发；v1 按新文案出图 ⇒ 绶带贯穿整条顶带，横向拉伸安全）· 另加 `the frame band is a little wider so the bronze inlay line stays well outside the middle of the plate`（见 §4.8 第 2 条：内线落在 20% 采样窗内会把 σ 判据推红） |
| **P-04** `panel-gilt` | 同 P-01 构图，**原文 `a short hanging gold chain and a small wax seal at the top centre` 与 §4.2 冲突，已改**：`ornate bronze-gilt frame band, gilt finish continuous and even along all four edges, the extra gilt scrollwork lives ONLY on the four corner caps` + 硬禁项 `do NOT place a chain / wax seal / medallion / crown / gem on the middle of any edge — reward chains and wax seals are separate decorative assets laid on top at runtime`（金链与火漆本来就是 §一 的**顶饰**，属于 #16 `crest-reward` 与 #17 `seal-wax`，不该烘进会被拉伸的底板边带） |
| **P-05** `plate-band` | `Asset type: nine-slice list-row plate, 512x128` · `narrow iron strip with a 4px bronze bevel along top and bottom edges, very shallow, flat, no corners ornament` |
| **P-06** `plate-band-active` | 同 P-05 形状，`bronze bevel lit up as if selected, faint dark red glow inside the bevel only` |
| **P-07** `button-iron` | `Asset type: nine-slice command button, 160x52`（实测出图 bbox **4.34:1**，与 3.08:1 不同 ⇒ 交付尺寸按实测定） · `forged iron button face, bronze bevel that occupies about 8% of the height, two small rivets near the left and right ends, completely flat front-facing` · **`corners must be square 90 degrees — no chamfer, no bevelled corner, no rounded corner`**（v0 给了 45° 斜切角，与 6·4 的薄 border 九宫格冲突，见 §4.7） · `Constraints: generous clear centre for a Chinese label, no text, no letters, no icons` |
| **P-08** `chip-close` | `Asset type: small square button sprite, 52x52` · `dark iron square button with bronze bevel and a single rivet at each corner, empty centre` · **必须写明** `no X glyph, no cross, no symbol`（符号由 Label 画，见 §八 禁止项 3）· **本轮补两条（三版才出一张能用的）**：① `every ornament must live inside the outer 8% of the sprite; NO large bronze corner plates, NO big round bosses` —— v0 给了四角大铜凸台（各占约 25% 图宽），C 档 border 只有 6·4 ⇒ 切分线直接穿过凸台；② **禁写** `filling the canvas nearly edge to edge` —— v1 照这句画成满幅、四角没有绿底，抠绿前提直接不成立（量具退 1，`bg-not-pure-green`）；改成正向表述 `floating at about 70% of the canvas, flat pure green reaching all four corners` 后 v2 过（残留绿 0.112%、直角填充率 78%、目视四角只有小铆钉点） |
| **P-09** `plate-tooltip` | 同 P-05，尺寸 `256x64` · `slightly warmer iron, thinner bevel, for tooltips and floating text` |
| **P-10** `banner-crest` | `Asset type: single non-sliced decorative title plaque, 512x128` · `horizontal bronze-framed war banner with two crossed short spears behind the top edge and a small crown relief at the centre top; the plaque face is completely blank` · **不加** 4.2，改加 `Constraints: keep the whole plaque on one piece, do not tile, transparent or pure green background outside the silhouette` |
| **P-11** `crest-league` | `Asset type: single non-sliced emblem, 256x256` · `alliance war banner emblem: forked dark-red silk flag on a bronze pole with iron fringe, blank shield face in the centre` |
| **P-12** `seal-wax` | `Asset type: single non-sliced emblem, 256x256` · `dark red wax seal with an impressed bronze ring and a blank centre (no letter, no rune)` |
| **P-13** `divider-rope` | `Asset type: single non-sliced horizontal divider, 512x32` · `braided leather cord with two small bronze beads and a dark red diamond knot at the centre, ends fade to nothing` |

（P-11 顶饰族：league / nation / battle / reward 四件同母版语言，一次生成四张，只换主体物件——**nation 用玉玺火漆纹、battle 用交叉长枪、reward 用金链与小冠**，不许改边框材质与光向。）

### 4.4 统一负面约束（每件 Avoid 段）

```text
Avoid: blue sci-fi accents, neon, glossy candy gradients, rounded app-style cards, photorealism,
any text, any letters, any numbers, any runes, any watermark, any UI control drawn inside the surface
(checkbox, close cross, arrow, progress bar), any baked-in drop shadow outside the frame
```

### 4.5 处理管线（现成脚本，不要另写一套）

```bash
# 1) 生成物入草稿（ImageGen 落盘带时间戳后缀，逐张对应到 batch json）
#    目录约定：art-src/generated/drafts/raw/<日期>/<件>-raw.png
# 2) 批次清单：顶层数组，条目字段只有 raw / out / size，可选 mode:"plain"，
#    母版拆格用 grid:{cols,rows,out_dir,names[]}   ← 现跑自 process_generated.py:2-5 与其读取逻辑
#    （没有 prompt 字段；提示词只写在 art-src/GENERATION_PROMPTS.md）
python art-src/process_generated.py art-src/generated/drafts/batch-<日期>-ui-style.json
#    抠绿常量 GREEN_HI 110 / GREEN_LO 30 / MARGIN 6，残留绿 > 0.5% 即 WARN 且整批退码 1
#    背景取样非绿或色相差 >60 直接 SystemExit；拆格投影分段数 ≠ grid 数即 FAIL（绝不静默等分）
# 3) 收编进运行时（非方形要带 WxH，比例差 >3% 直接失败）
python art-src/accept_to_runtime.py --size 512x344   # A 档（比例取自 §4.6 实测出图，勿写 4:3）
python art-src/accept_to_runtime.py --size 160x52    # C 档
# 4) 状态派生（不再生第二张母版，防造型漂移；不 resize）
python art-src/derive_button_states.py <母版.png> <输出前缀.png>
```

---

### 4.6 首版实测（P-01 已真跑出图，2026-10-08 00:0x，ImageGen 1024×768）

产物 `vibe_images/panel-iron-v0_1791388903866_9be107b8.png`（**1.05MB，不入库**：`.gitignore:25` 忽略 `vibe_images/`、`:23` 忽略 `art-src/generated/drafts/` ⇒ 本仓草稿图一律不入库，**可复现物是下面的提示词本身**，不是这张图）；量具 `nineslice-measure.py`（临时件，判据设计见下）。

| 判据 | 读数 | 结论 |
|---|---|---|
| 背景纯绿（抠绿前提） | 四角 `(15,245,1)` `(12,246,8)` `(13,244,2)` `(9,249,5)` | **过** ⇒ `process_generated.py` 不会在取样处 SystemExit |
| 残留绿占比 | **0.163%**（管线阈值 0.5%） | **过** |
| 装饰是否只在外圈 | 中心 60% 区 σ=**7.7**，四条环带 σ=**30~45**（11.6×） | **过** ⇒ 这是"能不能切"的核心判据，实测成立 |
| 出图比例 | alpha bbox 870×585 = **1.487:1**，距 4:3 偏 **11.5%** | **不过** ⇒ 见下方修正 1 |
| 角帽左右镜像 | TL/TR 差 **8.5%**、BL/BR 差 **3.1%** | **过** |
| 角帽上下镜像 | 上带比下带亮 **43%** | **不是缺陷**——是"单主光来自左上"的必然结果，见修正 3 |
| 目视（384 缩略） | 暗铁面 + 四角铜帽带铆钉 + 铜内线，中心无装饰 | 可用度高，直接进 V25-b 批量 |

**三条必须回写规格的结论**：

1. **尺寸契约已按本条改定**：§二 原先定 A 档 `512×384`（4:3），与 AI 实际出图 1.487:1 冲突，而 `art-src/accept_to_runtime.py` 的既有判据是"素材长宽比与目标差 >3% 直接失败（防止把图压扁收进包）"⇒ **照原契约收编会必然被拒**。两条出路：(a) 收编前按 alpha bbox 裁成正好目标比例（裁边不缩放，代价是丢两侧像素）；(b) 把 A 档素材尺寸改成 **512×344**（=实测 1.487）并把 border 改定为 **48·36**，自检仍走 §二 公式（460×300 消费 ⇒ 72/300 = 0.24 ≤ 0.6 ✓）。**已采纳 (b) 并写回 §二**——不丢像素、不跟模型的比例习惯对抗；(a) 留作"某件素材出图比例离谱"时的兜底手段。
2. **4.2 约束已加一句**：本张图的**边中段各有一块暗红皮饰**（上下左右四条边的正中），九宫格横向拉伸到 720 宽时会变成糊开的长条。⇒ 提示词已补 `edge band must read uniform along its whole length except at the four corners`。**这条是本轮实测换来的，不是先验写的。**
3. **对称判据只能比左右镜像，不能比上下**：谁写"四角亮度差 ≤12%"这种判据，就会把主光方向正常的素材误杀（本张上下差 43% 而左右差 8.5%）。V25-e 的量具与 V25-b 的目视清单按此口径执行。

**未做 / 未验证（照实写）**：

- **边厚占比没量出来**：脚本用"沿水平中线找第一个亮度跳变"估左边带，读数 4px——落在 alpha 过渡沿上，是**无效读数**（判据设计缺陷，不是素材缺陷）。边厚要用梯度累计法或目视标尺定，**当前未验证**。
- 本张只证"可切"，**未证"铺到 460×300 上好看"**——那要 V25-c 接上面板后 `shot-panel-sweep.mjs` 真截图才算，本轮无前台截图。
- 未跑 `process_generated.py` 全管线（要先建 batch json 并往 `art-src/generated/drafts/` 写产物，属 V25-b 的活）。

**修正 ② 已判伪（同日第二趟，`panel-iron-v1`）**：用加了"边带除四角外必须沿长度均匀"的提示词复出一张，
**目视确认四条边正中的独立饰块消失了**（v0 有、v1 无；v1 边带只剩贯穿的铜内线与皮革纹，横向拉伸安全）
⇒ 该约束有效，修正 ② 从"待判伪"转为"已生效"。bbox 比例 1.425（v0 是 1.490），仍在 §二 改定的 512×344 容差内。

⚠️ **同一趟换来两条判据教训，比那张图更值钱**——亮度标准差这类统计量**测不出"边中有独立饰块"这件事**：

| 判据写法 | v0（**真有红块**） | v1（无块） | 结论 |
|---|---|---|---|
| 四条边取**平均** σ ÷ 角部 σ | 0.60（判"过"） | 0.52（判"过"） | **假绿**：一条脏边被另三条干净边摊平 |
| 四条边取**最差** σ ÷ 角部 σ | 0.96 | 0.68（仍判"不过"） | **双向都错**：既误杀 v1，又测不准语义 |
| 同一条边内 正中 σ ÷ 旁侧 σ | 1.13（判"过"） | 1.03（判"过"） | **分不开两张** ⇒ 该写法无效 |

根因：那块皮与周围皮革**亮度接近、差别在色相**（红 vs 棕），而 σ 只看亮度离散度。
⇒ **V25-b 的门只放三条可机器判据**：背景纯绿（四角取样）、残留绿占比 ≤0.5%、中心 60% 区 σ 显著低于环带 σ
（这三条 v0/v1 都正确给出结论，且它们量的正是"能不能切"）；
**"边带是否混入独立饰块"只能目视**，且要逐张看四条边的正中——与本仓既有那条"机器指标全绿也不代表可用，
素材要再逐张目视"是同一条纪律，别再用统计量替眼睛签字。

### 4.7 量具就位（`art-src/check_nineslice_ready.py`）与已出的三件

```bash
python art-src/check_nineslice_ready.py <图...> --kind frame|plain|art   # 退 0 才允许进收编
```

三条可机器判据（背景四角纯绿 / 抠绿残留 ≤0.5% / **仅 frame 档**判「环带 σ ≥ 3× 中心 σ」），
外加一条**显式印成"未判"**的第四维（边带有无独立饰块，只能目视，理由见 §4.6 与 #796）。
判据能失败的证据（真退出码，不经过管道）：负向对照（把 v0 左上 8×8 涂成蓝底）**退 1**、
三份真素材按各自档**退 0**。

| 件 | 档 | 实测 | 结论 |
|---|---|---|---|
| `panel-iron-v0` | frame | 残留绿 0.163%，σ 中心 7.6 / 环带 29.5 ⇒ **rel 3.89**，bbox 1.490:1 | 过 |
| `panel-iron-v1` | frame | 残留绿 0.136%，σ 7.1 / 28.8 ⇒ **rel 4.04**，bbox 1.425:1 | 过（边中饰已去） |
| `banner-crest-v0` | art | 残留绿 0.181%，bbox **2.213:1**；目视：铜框 + 中央空面 + 顶冠 + 交叉短枪 + 两端绸尾 | 过，形状即 P-10 所求 |
| `plate-band-v0` | plain | bbox **10.755:1**（1710×159）；目视：上下两道铜边 + 暗铁面，**无铆钉无角饰无边中 medallion** | 素材合格 |

**这一趟又抓到两处，都是"契约/判据"层面的，不是素材层面的**：

1. **`plain` 档的 σ 判据被自己的合格样本否证**：那张完全合格的条行给出 rel=**2.77**，
   因为"上下两道铜边 + 中间暗面"是 B 档的**设计要求**，纵向亮度梯度天然存在 ⇒ 拿 σ 比它必然稳定假红。
   ⇒ σ 判据只对 frame 档有功能含义（A 档中心要能被任意拉伸），`plain` / `art` 一律不跑。
   这是本规格**第二次**犯"把一条统计量当跨素材类型的通用门"（第一次是 §4.6 的边中饰）。
2. **B 档的交付尺寸契约同样会被 `accept_to_runtime.py` 拒**：§二 原写 `512×128`（4:1），
   实测出图 10.755:1，差 **169%** ⇒ 与 A 档那次是同一颗雷，只是更极端。
   改定：**B 档按实测出图比例交付**（`512×48`，border 仍 12·8；自检 40 高消费点 ⇒ 16/40 = 0.40 ≤ 0.6 ✓），
   且**出图比例不许照抄本表**——每件素材都要跑一次本量具读自己的 bbox，再定交付尺寸。
3. **匾额待验一项**：2.213:1 的整图压在 460~720 宽面板的上沿，若按"占面板宽 55%"排布，
   720 宽时高约 195px，可能吃掉内容区 ⇒ **未验证**，判据要在 V25-c 用 `shot-panel-sweep.mjs` 实拍后定，
   不在这里拍数。

**C 档第一次实测（`button-iron-v0`，同日 01:5x）又抓到两条，都不是"再出一张"能糊过去的**：

1. **四角是 45° 斜切（chamfered corner），与"薄 border 九宫格"结构冲突**。C 档 border 只有 6·4，
   切分线落在斜切区内 ⇒ 横向拉伸时斜角会被切成两段错位，正是 §二 要防的那类"素材画不上去"。
   ⇒ P-07 提示词已补 `corners must be square 90° (no chamfer, no rounded corner)`；
   若模型仍给斜切，退路是**C 档不走九宫格**（像 G8 页签那样按格子比例整幅画满），两条路在 V25-b 后续二选一。
2. **边带厚度依旧没有有效量法（这是第三次）**。新写的剖面法先给出 0px 的离谱读数（假设亮带从最外沿连续，
   实际最外一圈是暗描边：顶行 luma 45.7、第 12 行才 175.4），改成"上下 1/4 区内数亮行数"后又与目视**反向**
   （目视铜边明显的条行印出 4.4%，铜边较细的按钮印出 10.2%）。
   ⇒ 该数值在 `check_nineslice_ready.py` 里**降级为"仅提示、不作判据"**并写明原因；
   §二 的 [0.07, 0.25] 目前只能靠**人工标尺 + 目视**核，不许拿这条当门（抓不住真值的判据会同时放过缺陷和误杀好素材）。

量具最终状态（复验读数）：两份真素材 `--kind plain` **退 0**、蓝底负向对照 **退 1**（`bg-not-pure-green`）
⇒ 三条可机器判据既不误杀也能失败；第四、第五维（边中饰、边带厚度）都显式标为未判/未验证。
**斜切角那条待判伪已当场关掉（同日 02:1x，`button-iron-v1`）**：加了 `corners must be square 90 degrees`
重出的第二版，**角点放大目视确认外轮廓是直角**——图里那条 45° 线是铜边内部的**拼角缝（miter joint）装饰**，
不是剪切角；v0 才是真斜切（角被切掉约 20px）。顺带把这件事做成了一条**能用的判据**：
量四角 6×6 的「主体填充率」（形状量，不是 σ）——**v0 = 0%、v1 = 75%、条行 = 92%**，
真斜切退 1、两张合格素材退 0 ⇒ 既不误杀也能失败。阈值取 50%（落在 0 与 75 之间），
**n=2 拍的、扩样本后要复校**，别当已知边界。
⇒ `plain` 档现在有两条判据：三条通用的 + 这条「直角剪影」；`frame` 档**不跑**它
（A 档角部本来就该有角帽造型，实测填充率只有 33%~67%，跑上去就是第三次犯「判据跨档复用」那个错）。


---

### 4.8 V25-b 生产轮实测（2026-10-08 06:5x~07:1x，新出 **11 张母版 + 3 张派生** ⇒ 18/18 件出齐）

命令：`python art-src/check_nineslice_ready.py <图> --kind frame|plain|art [--crop-preview DIR]`
→ `python art-src/process_generated.py art-src/generated/drafts/batch-2026-10-08-ui-style.json`
→ `python art-src/accept_to_runtime.py --drafts <单件目录> --out <临时目录> --size WxH`（**收编产物本轮不进 `client/`**，理由见第 7 条）。

1. **A 档 border 从 48·36 抬到 80·72（用户裁决，实测撑住）**。量法：在角帽高度上取一行、沿宽度分 16 段取平均亮度，
   铜帽段与铁带段亮度差 40~55 ⇒ 边界清楚。读数 `panel-iron-v1` 角帽占宽 **12.5%**（16 段里两端各亮 2 段）、
   `panel-gilt-v0` **约 19%**（各亮 3 段），目视大格（250px）标尺一致 ⇒ 交付 512 宽下角帽实际 64~97px，
   而契约 border 只有 48 ⇒ **切分线必然穿过角帽**，拉到 720 宽时角帽内沿会被拉成约 1.5 倍。
   代价照实写：460×300 那档上下 chrome 合计 144px = **48%**，自检 0.48 ≤ 0.6 只剩 0.12 余量 ⇒ **V25-c 必须实拍这一档**。
2. **σ 判据有第二个已知误红源：铜内线的位置**。`panel-warning-v0` 给 rel=**2.51**（判红），
   目视+裁切确认中心 60% 采样窗（20%~80%）里**穿过了那条铜内线**（顶行与左行各压到一条亮线）⇒ 中心"干净但被内线穿过"。
   这不是素材缺陷（内线平行于边，横向拉伸不会糊），是**判据把"亮度离散"当成了"有装饰"**。
   v1 用"把 frame 带画宽一点、内线留在窗外"重出 ⇒ rel **3.24** 过。
   ⇒ 记进边界清单：**σ 判据对"边带宽度 / 图幅"之比敏感**，窄边带素材会被稳定推红；下一次再撞不要先怀疑素材。
   ⚠ 同一张 v0 **另有真缺陷**（顶边正中一块独立红绶带，目视抓到）⇒ 这次判红与真缺陷**同源不同因**，
   不能拿"判据反正红了"当省事理由，两条要分开记（否则会把误红规律当成缺陷规律）。
3. **量具有两把 bbox 尺，收编只认其中一把**。量具用 `alpha_of>160`（绿底图没有真 alpha，只能按绿色度造 mask），
   而 `accept_to_runtime.py` 用 PIL `split()[3].getbbox()`（**alpha>0**，含抠绿过渡沿的半透明一圈）。
   普通件两者差 <0.2% 无所谓；**10:1 以上的长条差 5px 就是 10% 比例误差** ⇒ 本轮第一次收编有 **4 件**
   （条行 9.827 vs 目标 10.894、按钮 3.938 vs 4.129、提示条 4.189 vs 4.414、绳结 12.700 vs 14.222）
   被">3% 直接失败"拒。改成与收编同一把尺现量后 **14/14 退 0**，且比例差全部 ≤1.6%。
   ⇒ §三 那条"交付尺寸逐件现量"必须补一句：**量的必须是 keyed 草稿的 `alpha>0` bbox，不是 raw 绿底图的 >160 mask**。
4. **量具自己崩会被读成素材判红（本轮真实撞上一次）**：Windows 控制台默认 GBK，`plain` 档那行提示里有 `⇒`，
   `print` 抛 `UnicodeEncodeError` ⇒ **退码 1**，而素材本身三条判据全过。
   修法＝输出与判据解耦（`sys.stdout.reconfigure(encoding='utf-8', errors='replace')`），
   复验双向：合格件 `button-iron-v1 --kind plain` 退 **0**、负向对照（`panel-iron-v1` 左上 8×8 涂蓝）退 **1** 且报 `bg-not-pure-green`。
5. **新增目视取证器 `--crop-preview DIR`**（就在量具里，不另写第二份）：按 alpha bbox 定位裁出
   四角 + 四边正中 + 中心共 9 格拼一张 3×3，并把每格 box 坐标印出来。
   **为什么必须按 bbox**：第一版按"图片尺寸"取中，裁到了边带中段＝没有证据。
   自证生效：印出的 TL 起点 == 量具自己的 bbox 左/上，BR 终点 == 右+1/下+1（`panel-parchment` bbox=34..989×38..729 ⇒ TL=(34,38)、BR=(852,592)+格宽）。
   本轮 14 件逐件出取证图并目视，**四条边的正中有无独立饰块**这一维全部按档核过。
6. **`art` 档不该收到"边中饰未判"那句提示**：装饰件永不拉伸 ⇒ 没有切分线，那一维对它没有意义。
   已按档分开：`art` 现在印"目视只核两件事——有没有烘焙进去的文字/符号，以及中心空面够不够放字"。
7. **收编产物本轮不进 `client/assets/resources/**`**：V25-c 正被另一条会话写
   （`tools/verify-ui-v25-runtime.mjs` mtime 10-08 02:17、`PANEL_IRON_INSET` 已进 `ArtFamilies.ts:55` 并被 `MarchComposeOverlay.ts` 消费，
   runtime 里 `panel-iron-v1.png` / `button-iron-v1.png` / `banner-crest-v1.png` 三张是**它的未跟踪产物**）。
   ⇒ 本轮只出"尺寸 + 量化体积"两个读数（§二 表），落地交给 V25-c。**撞车判据**：动手前 `git status --porcelain -- <落点>` 逐路径核，
   别只看 `git status | head -30`（本轮就是被截断的输出误导过一次，漏看了 8 条 `?? client/assets/...`）。
8. **派生态"造型不漂移"是可证的，不用目视**：`derive_button_states.py` 与 active 派生都只动 RGB、alpha 原样带过去 ⇒
   断言"派生态与常态 alpha 逐像素差异 = **0** 像素"，并配**对照组**（条行 vs 按钮两张不同素材）差异 = **42960** 像素
   ⇒ 这条断言既能过也能失败。三张派生态（hover 6.9KB / disabled 3.9KB / active 10.1KB）因此**继承常态的退 0**，不必重跑判据。
   active 的系数 **brightness 1.16 / color 1.22** 写在这里而不是塞进 `STATES`：那个字典被 `ArtCatalog.applyCommandButton` 认，
   加一行就会让按钮族多出一个没人画的状态。
9. **复核上一轮已收的两件，结论没翻**：`plate-band-v0` 与 `button-iron-v1` 用新裁切器逐边看，
   四条边正中都只有贯穿的铜边/皮革纹，**无独立饰块**（上一轮只目视了角部，这一维当时其实没查过 ⇒ 现在是查过了）。
10. **`crest-battle` 一处待 V25-c 实拍定夺**：中心铜环里穿过去的是两根交叉枪杆，缩到 256 以下可能读成"×"
    （§八 禁止项 3 的_close symbol_ 形状风险）。本轮判**可用**（语义就是交叉长枪，且顶饰不会出现在关闭键的位置），
    但**未做视觉验证的边界要照实说**：没有前台截图。若 V25-c 实拍误读，改法＝提示词去掉中央铜环，只留交叉枪。

**本轮仍未做 / 未验证**：无任何前台截图（"铺到面板上好不好看"仍归 V25-c）；border 80·72 未在真机复验；
`check.sh` 全量与 `mvn test` 本会话未跑（主检出会换掉另一条会话活后端用的 jar）；
三条可机器判据**尚未接进任何门或探针**（V25-e）；角帽占宽用的是"单行分段亮度 + 目视标尺"，
**不是**可当门的自动判据（同 §4.7 那条"边带厚度无有效量法"，第四次尝试仍只做成了提示）。

## 五、任务卡（V25-a…V25-e，每格独立可验证可提交）



### V25-a · 风格锁定 + tokens 收口（不做就不许动视图）

```text
你在给《列王纪·铁誓》(ironoath) 做 V25-a：把弹窗的视觉语言收进一个 tokens 文件，不改任何素材。
铁律（违反即视为任务失败）：
1. 只建一个真源：新建 client/assets/scripts/game/ui/UiTokens.ts，导出遮罩/兜底色/铜金/暗红/圆角/框带六组常量；
2. 值必须来自现跑：兜底色取现跑主流值 Color(40,33,27,255)，铜金取 Color(184,134,11,255)，
   遮罩把 6 个并存值收敛成 1 个（Color(8,6,5,190)），不许凭感觉另起一个色；
3. 羊皮纸与暗红号色先留 TBD 常量并注释"待 V25-b 目视定案"，不许填感觉值；
4. 本格不删各视图的局部 COLOR_*（删在 V25-c/d 接线时逐文件删，防止半接线状态）；
5. 新增 .ts 在 client/assets/scripts 下必须连 .ts.meta 一起入库（check-ts-meta.sh 只扫这个目录），
   而 client/tests/ 下的用例**不需要** meta（现跑该目录 0 个 .meta）。
第一步：复述你的理解（≤20 行），并反问本卡末尾的 3 个开放问题。
```

- 必做：`game/ui/UiTokens.ts` + `.ts.meta`；把 §一 色族表写进文件头注释；`client/tests/UiTokens.test.ts` 钉住"遮罩只有 1 个值、铜金只有 1 个值"。
- 验收：`node --test`（**必须 Node 20**：`export PATH="/d/Java/nodejs/node20.13.0:$PATH"`）绿；`bash scripts/check-ts-meta.sh` 绿；`bash scripts/check.sh` 退 0。
- 产出文件：`client/assets/scripts/game/ui/UiTokens.ts{,.meta}`、`client/tests/UiTokens.test.ts`（用例目录不扫 meta）。
- **已完成（2026-10-08）**：三件产出文件落地，6 条用例绿（`client/tests/UiTokens.test.ts`，植入取证见台账 **#795**）。
  两处与本卡原稿不同的实测结论，**以现跑为准**：
  ① 原稿写"导出六组常量"含框带厚 —— 实际**不收**，因为 `ArtFamilies.PANEL_FRAME_BAND` 与 `.png.meta` 已由
  `client/tests/ArtFamilies.test.ts` 对账，放第三份就是 #213；用例把"本文件不得出现 FRAME/BAND/INSET/BORDER 键"钉成判据。
  ② 原稿没提引擎依赖 —— 实际第一版 `import { Color } from 'cc'` 会让这层用例在 import 阶段崩
  （`game/` 81 个模块现跑零引擎依赖），故颜色以 RGBA 元组下发、`new Color(...)` 留给 `scene/` 侧。
  **本格未删任何视图局部常量**（接线时机在 V25-c/d 的截图验收之后）⇒ 玩家可见效果目前为 0，这是刻意的。

### V25-b · 素材生产（11 张 AI 母版 + 3 张派生 + 逐张目视）

必做：按 §四 逐件出图 → `process_generated.py` → `accept_to_runtime.py` 量化 → **逐张 1:1 目视**（机器指标全绿不代表可用，这是本仓既有教训）→ 目视判据**四问**：中心净区真的净吗？边厚铺到 §二 的消费尺寸上还在吗？光向和主城一致吗？**四条边的正中有没有混进独立饰块？**
⚠️ 第四问**不许用统计量代签**：§4.6 实测三种 σ 写法（平均 / 最差 / 同边正中比旁侧）在"真有红块"的样本上分别给出 0.60 / 0.96 / 1.13，前两种判"过"、第三种与无块样本（1.03）分不开 —— 因为饰块与周围材质**差在色相不差在亮度**。可机器判的只有三条：背景纯绿、残留绿 ≤0.5%、中心 σ 显著低于环带 σ。
验收：① 18 张全部落到 `art-src/generated/drafts/`，残留绿读数逐张为 0.000%；② 逐张实测 `inset` ÷ 图高落在 [0.07, 0.6]；③ 新增总量 ≤ 450KB（`du` 实测）；④ **把 P-01 的 border 临时改成 200 重跑一次 `verify-art-runtime.mjs`，必须报"退化/吃内容"红**（证明判据能失败，才允许进 V25-c）。
产出文件：`art-src/generated/drafts/batch-<日期>-ui-style.json`、`art-src/GENERATION_PROMPTS.md`（新增 §12 本批）、`art-src/ATTRIBUTION.md`（AI 生成条目的署名口径）。

**状态（2026-10-08 07:1x 本格收口，台账 #807）**：18 / 18 件出齐（本轮新出 11 张母版 + 3 张派生；上一轮已出 4 张母版），逐件过判据 + 逐件目视 + 逐件现量交付尺寸，
尺寸与体积表见 §二，实测过程与换来的判据边界见 §4.8。四条验收逐条对账：
① **按字面未达、按意图过** —— 残留绿实测 0.004%~0.274%，"逐张为 0.000%"这个阈值写坏了（抗锯齿过渡沿必然留软边，
`process_generated.py` 的真判据是 ≤0.5%），14 件全在 0.5% 内且整批退 0；
② **未达且无法达** —— `inset ÷ 图高` 到本轮结束仍**没有有效量法**（第四次尝试：单行分段亮度只能定角帽范围，定不了边带厚度），
现用"目视标尺 + 量具仅印不判"顶替，[0.07, 0.6] 这一维**未进门**；
③ **过** —— 18 件合计 **424.2KB** ≤ 450KB（本批 14+3 件 372.3KB + 已收编的 `panel-iron-v1` 51.9KB，逐张 `os.path.getsize` 实测）；
④ **未做（领地原因，不是漏做）** —— 该判据要临时改 `client/assets/resources/**` 的 meta border 并重跑 `verify-art-runtime.mjs`，
而 V25-c 正被另一条会话写（`tools/verify-ui-v25-runtime.mjs` 02:17、`PANEL_IRON_INSET` 已在 `ArtFamilies.ts:55`）。
⇒ **这一条是 V25-c 的准入门，本格把它原样交接过去**，并附一条更狠的对照：border 改成 200 之外，
还应改 **80·72 → 48·36** 复跑一次，看运行时回读的 inset 是否真的跟着 meta 变（不变就说明代码侧 `insetLeft` 后写赢了，那是 §4.8 第 7 条那处双写口的病灶）。


### V25-c · A 档底板接线（4 处底板 + 待人工判档的表达式点）

必做：`ArtCatalog.ts` 的 `StaticArtKey` 与 `SPECS` 增 4 个 A 档键；`GiftPopupView`（**改掉 `fillRect` 直角矩形**，:145-147）、`ChoiceOverlay:63`、`MarchComposeOverlay:125`、`StaminaDetailOverlay:70` 换 `applySlicedSprite`；标题换 `banner-crest` 匾额；关闭键统一到一种形态（现跑两种并存，§〇）。
关键约束：`applySlicedSprite` 会自动 `addComponent(Sprite)`、把该 node 上的 `Graphics` `clear()+enabled=false`（`ArtCatalog.ts:469-485`）⇒ **底框节点与内容节点必须分离**，否则内容 Graphics 被一起关掉。这条要在验收里用一条能失败的判据钉住。
验收：逐屏截图 8 份（用 `tools/shot-panel-sweep.mjs`，1440×900 留帧）+ `verify-art-runtime.mjs` 全绿 + `verify-gift-popup.mjs`（弹窗文案与几何既有门）绿。
- **已完成（2026-10-08，台账 #803）**，四处与本卡原稿不同，都以现跑为准：
  ① 原稿列的 4 处底板只接了 **3 处**（`GiftPopupView` / `ChoiceOverlay` / `StaminaDetailOverlay`）——
  `MarchComposeOverlay` 动手时是脏的（另一会话在途）⇒ 按领地纪律跳过，不在别人文件上叠改动。
  ② **原稿漏写了一条硬要求：内容必须按框带厚内缩。** 首版只换底板、内容 y 沿用旧值，
  真截图上就是"标题压在铜边内线上、底部那句被下铜边切掉半截" ⇒ 新增 `PANEL_IRON_INSET`
  （与 `.png.meta` 由 `client/tests/ArtFamilies.test.ts` 对账）并把 y/宽度都从 inset 起算。
  **换材质不等于自动有安全区，这一圈要显式还给内容。**
  ③ 验收判据落在新的 `tools/verify-ui-v25-runtime.mjs`（15 条，回读运行时 inset 与 meta 逐值相等 +
  沿真实链路画出 + 不退化），比"跑一遍既有探针"更能钉手写 meta 的正确性。
  ④ 构建走 `outputName=ui-v25` 独立产物（`SWEEP_ROOT` 可覆盖），**不打断并行会话的探针**；
  `plate-band` 因无消费点被既有白名单守卫拦回草稿区（同 #216 那道门）。
  未做：礼包那一屏没有玩家路径截图（新号 `/gift/popup` 不返内容 ⇒ 进不去），
  其接线按**未做视觉验证**处理；「买 1 次」按钮仍是纯色块（带置灰逻辑，另格做）。

### V25-d · B/C 档接线（43 + 52 处，含按钮 chip 与条行）

必做：B 档 `plate-band` 接列表行（`BagPanelView:541`、`SocialPanelView:1213`、`BattleReportPanelView:504` 等）；C 档 `button-iron` 接 `ActionButton*` 一族；跨档复用**一律拒绝**。
⚠ 本格触碰共用件（行池 / 行序 / 按钮壳）——按 `AGENTS.md` 二节的批跑纪律：**单跑自己碰的不够**，必须带上「已有内容可见性」那一族（`verify-social-permission-runtime` · `verify-social-create-runtime` · `verify-rank-runtime`），并全量批跑 60 份（`bash scripts/run-batch-dual-backend.sh`，国家正链路那族要 `BOOST_BACKEND`）。
另：改行视觉会动 `plate-coverage`（文字压底板）与 `label-fit`（压字）两道既有判据的前提，**先现跑取基线再改**，不许改完直接宣称绿。
- **部分完成（2026-10-08，台账 #805）**：本卡的 ②「买 1 次那颗仍是纯色块」已做完 ——
  BuyButton 走 `ui.button.iron`（探针回读 `BuyButton:button-iron-v1@220x36`，未退化），
  置灰拆成两条路径各自生效（贴图态压 `Sprite.color`、兜底态保留 `fillColor` 换色），
  协议"置灰而不是隐藏"由三条静态判据钉住。**一处实测约束**：`Sprite.grayscale` 在本仓 headless 类型桩里不存在
  （`check-client-typecheck` 报 TS2339）⇒ 用 `color` 相乘，不去扩类型桩。
  **③ 已完成（同日 02:5x，台账 #806）**：`MarchComposeOverlay` 接 `ui.panel.iron`，并拆掉一颗结构性雷 ——
  该弹层原先把**整屏遮罩**与**底板**画在同一个 Graphics 上，而 `applySlicedSprite` 会清空所挂节点的整张画布，
  直接换贴图会**连遮罩一起删掉**（该文件注释记着那次字叠字的截图事故）⇒ 底板进独立 `plate` 子节点。
  内容安全区同 #803 再应用一次：标题原 `PANEL_HEIGHT/2-28=202` 已越过净区上沿 194（压进铜帽 8px），
  行宽 `PANEL_WIDTH-48` 也改成减掉左右两条铜帽带。判据用**三条结构断言**（遮罩在 background / 底板在 plate /
  底板不画回 background）+ 植入取证（改坏两处 → 两条点名红、退码 1；还原 → 23 条 / 0）。
  **未验证**：`verify-rally-runtime` 与 `verify-march-runtime` 硬编码吃 `web-mobile`，本轮只建独立产物 ⇒
  接线对这两道既有门的影响未跑；弹层像素级视觉也没拍（要造部队+目标且占集结名额）。
  本卡 ①（B 档接列表行）**未做**，挂在批跑独占产物这个真实外部条件上（见队列 `- [~]`）。

### V25-e · 防腐（把"边厚 ÷ 消费尺寸"做成机制，不靠人记）

必做：
1. `tools/verify-art-runtime.mjs` 在既有 `degenerateSlices`（:765-772）旁增一条 gate「九宫格边厚吃内容」：`insetTop+insetBottom > contentHeight*0.6` 或 `insetLeft+insetRight > contentWidth*0.6` 即红；
2. 新增静态门 `scripts/check-art-quantized.sh`：`resources/ui/generated/**` 下单张 > 120KB 且 PNG mode 非 `P` ⇒ 点名（现跑会命中存量 `panel-kingdom-v1` 170.9KB RGBA，**先决定是量化它还是进白名单**，§七 Q1）；
3. 素材键↔盘对账：现跑只在探针里判（`verify-art-runtime.mjs:1031`），把"A 档键不得登记进 C 档消费点"落成 `client/tests/ArtFamilies.test.ts` 的一条表驱动断言。
⚠ 加门会改计数：`AGENTS.md:22` 写着「静态门（46 道）」，且该计数由 `scripts/check-doc-counts.sh`（:29、:44-52）对账 ⇒ **同批改 AGENTS.md 与对账脚本**，否则门自己变红。

**状态（2026-10-08 三道判据全部落地并各自植入取证，台账 #808）**：

1. ✅ 进门为 `contentEatenSlices`（阈 0.6），另配两条自我约束：`judgedSliceCount === 0` 判红（反空转，
   量不到九宫格就不算绿）、豁免前缀在盘上对应不到 png 判红（名单自己会烂）。
   **作用域按裁决定过**：无条件判全部 SLICED 时打出 19 条命中，全是 `button-chip-*`（12·12 铺在 26~34 高的
   格子上，竖边 0.71~0.92），而实测那张 chip 的中心净区只有 8 种颜色 ⇒ 纯色平底、边带无独立装饰，
   "吃掉可读区"的前提对它不成立 ⇒ 裁决「全 SLICED 判 + `button-chip` 进豁免名单」，理由写进代码并被核着。
   取证：`panel-iron-v1.png.meta` 的 border 植入 200 重建产物 ⇒ 本条点名 5 处、旧退化判据只点名 1 处；还原后全绿。
2. ✅ `scripts/check-art-quantized.sh` 已落地为第 47 道门（`AGENTS.md` 的道数与 `check-doc-counts.sh` 同批改过）。
   现跑读数：扫 92 张，超 120KB 的 3 张里 2 张已是调色板、1 张在白名单内；两条自证（真彩色样本判红、
   调色板样本放过）每次跑都走同一支 `classify`。
3. ✅ 登记侧进 `client/tests/ArtFamilies.test.ts`：档位登记表（一键一档 + png 前缀 + meta border 逐值等于该档契约
   + 两张不同档的键不得指同一张图）与「每张在盘的 ui 图必须有 ArtKey 消费」。消费侧由第 1 条兜住
   （A 档 border 铺到小格子必然把比率顶过 0.6）。

⚠️ §二 的「消费尺寸下限」那一列**没做成判据**（2026-10-08 裁决）：现跑体力弹层是 360×260，低于 A 档写的
460×300，而那个数是从"现跑最小底板"倒推的观察值、不是裁过的规则 ⇒ 标待裁决（台账 #808），要进门得先裁决。

---

## 六、固定约定（本批新增/沿用）

- 沿用：素材源料进 `art-src/`，运行时产物只进 `client/assets/resources/ui/generated/**`；`resources` 走分包；不预接无人消费的资源。
- 沿用：切分几何的**唯一真源是那张图的 `.png.meta`**（`ArtFamilies.ts:36-42` 已因"两份数字"咬过一口，收口清单 #213）；代码里只留 `PANEL_FRAME_BAND` 这类给布局用的框带厚，且由测试对账。
- 新增：任何新素材必须先在 §二 表里登记档位与 border，**再**去生成；反过来做会产零消费素材。
- 新增：`UiTokens.ts` 是颜色唯一入口；视图里的局部 `COLOR_*` 随接线逐文件删除，删除顺序 = 该文件截图验收通过之后。

---

## 七、需要确认的开放问题（AI 第一步先反问，不许静默假设）

1. **存量 `panel-kingdom-v1`（RGBA 170.9KB 未量化）怎么办**——量化它（省 ~130KB，但软边可能出锯齿）、还是给 `check-art-quantized.sh` 开一条白名单并注明理由、还是随 V25-c 一起换成 `panel-iron`？
   ⇒ **已裁并执行（2026-10-08，用户选「现在就量化三张」，台账 #808）**：`icons-atlas` 169.2→32.9KB 与 `terrain-atlas-v1` 140.8→29.2KB 量化通过（图标按运行时截图逐像素对照 0.00% 变化；地形按游戏内 64 格实际显示尺寸目视无差）；`panel-kingdom-v1` **量化被目视否决**——255 色 FASTOCTREE 两档抖动都在皮革内衬出可见斑块，而机器读数只报 0.57% 像素变化 >24（又一次"指标全绿不代表可用"）⇒ 还原原图并进白名单，理由就是这条实测。附带新知识：量化会连带改 `.png.meta` 的自动裁边（icons-atlas 的 trim 从 9,9,1006,494 变 12,12,1000,488），子帧 rect 未漂移但下次要专门核。
2. **羊皮纸亮度定案**：浅内衬要不要配"深字 tokens"（现跑文字基本是金字/白字压在深底上）？若浅底 + 金字，对比度会掉，`verify-label-fit-runtime` 与导航对比度判据可能一起变红。推荐：纸面只提一档 + 正文改深墨色，顶饰标题保留金字。
3. ~~**B 档选中态**：由常态派生还是 AI 另出母版？~~ **已裁（2026-10-08）：走派生**，系数 brightness 1.16 / color 1.22，
   证据＝派生态与常态 alpha 逐像素差异 0（对照组两张不同素材差 42960 像素）⇒ 形状不可能漂。见 §4.8 第 8 条。
4. 关闭键统一成哪一种：`×` 字符（现有 2 处）还是 Graphics 圆角 + 文字「关闭」（现有 6 处）？推荐保留「关闭」文字（新手可读性），`×` 那两处并入。
5. 本轮要不要顺带把 `GuideView`/`Choices` 这类引导浮层一起换？（会加 ~10 个绘制点，且引导有独立探针族）
6. **【V25-c 必答】A 档 border 80·72 在 460×300 那档是否可接受**：自检 144/300 = 0.48 ≤ 0.6 成立但只剩 0.12 余量，
   而角帽实测占宽 12.5%~19%（512 交付 = 64~97px），48·36 会让切分线穿过角帽。
   判据＝`shot-panel-sweep.mjs` 实拍 460×300 与 720×600 两档，看铜帽有没有被拉长、内容区是否够用；
   若 460×300 太挤，退路不是把 border 调回去（那会重新切穿角帽），而是**把这一档面板抬高到 ≥520×360**（属布局改动，另开格）。
   ⚠ 改的时候 `borderTop/Bottom/Left/Right`（`.png.meta`）与 `ArtFamilies.ts:55` 的 `PANEL_IRON_INSET` **必须同批**，
   两处可写、后写者赢（记忆 `project-cocos-subpackage-and-trim-facts`），且要运行时回读 inset 才算落地。
7. **装饰件预算线**：`crest-nation` 30.3KB、`banner-crest` 27.2KB 超 §二 的 ≤20KB/张。本轮按"改预算线到 ≤32KB"处置
   （不动色数、不减细节），但这条线是不是该由 V25-e 做成门、超线要不要逐张点名，**待定**。
8. **`crest-battle` 中心铜环里的交叉枪杆在小尺寸下可能读成 `×`**（§八 禁止项 3 的形状风险）。本轮判可用，
   判伪方法＝V25-c 把顶饰缩到实际消费尺寸截图看是否误读；若误读，改法＝提示词去掉中央铜环。

---

## 八、禁止项（防自由发挥）

1. **不许把任何文字、数字、字母、符文、`×`、箭头烘焙进素材**——屏上的字一律 Label（`UiFont.ts` 是唯一字体入口，本批不动字体）。
2. **不许跨档复用素材**，也不许"先随便用一张凑合"（§二 是判据不是建议）。
3. **不许新增 `Graphics.roundRect` 当作最终视觉**——Graphics 只能作为素材未加载时的兜底路径，且兜底必须走 `UiTokens` 的颜色。
4. **不许手改生成物**：`client/assets/scripts/net/generated/**`、`**/config/cfg/**` 与 `*.png.meta` 的 border 改了要同批过 `client/tests/ArtFamilies.test.ts` 对账。
5. **不许发明数字**：包体、对比度、边厚凡未实测的写进 §七，不填感觉值。
6. 不许为了"看起来统一"一次性推翻 17 处 `COLOR_PANEL` 的主流值——那只保证 12 个面板不变，另外 5 个是有意区分还是漂移，**必须先逐一定位来源**。

---

## 九、验收与台账（做完往哪里写）

- 实施记录：`待完善收口_VibeCoding开发包.md` §四，一格一行（格 | 提交 | 验证读数 | 截图/证据 | 未做），**「未做」列照实写**。
- 台账：`收口清单.md` 一行一条、带行尾竖线、编号先让后取，插完跑 `bash scripts/check-checklist-table.sh`。
- 任务卡：`待完善收口_VibeCoding开发包.md` 的 V25（本规格的索引卡）。
- 接续：`.qoder-work-queue.md`（未跟踪，主检出与 worktree 两份同写）。
- 视觉验收的最终判据是**截图**，不是探针退 0：每格至少留 8 屏 1440×900 留帧，与改动前对照。
