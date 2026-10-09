/**
 * 职责：把 6×6 逻辑城格**投影**成内城场景锚点（显示落点、脚印、所属区、接的道路）。
 * 依赖：无（引擎无关，可在 node:test 里跑 —— B00 铁律 2）。
 *
 * <p><b>为什么单独成一个模块</b>：城景化最大的风险不是画得不好看，而是**顺手改了存档坐标**——
 * 服务端拥有 `gridX/gridY` 与建造校验，视图一旦为了构图去挪格子，就会做出"玩家建筑凭空搬家"。
 * 所以投影表放在引擎无关层：视图只能读它，改它要走单测与评审，而不是在绘制代码里就地算。
 * 规格出处：`art-src/内城场景美术_VibeCoding规格.md` §3.3 方案 A。
 *
 * <p><b>区不否决建筑</b>：`district` 描述这块地长什么样（地坪、路网、装饰），
 * **不描述它能建什么**。老号把农田建在王庭台基上，就照画农田 + 台基地坪；
 * 强制搬迁旧坐标要另立裁决，不由表现层顺手做掉。
 *
 * <p><b>坐标口径</b>：`x/y/w/h` 是 1000×563 工作视图里的显示值（与 A16 热区图同源），
 * 不是像素承诺；真机尺寸由 `view.getVisibleSize()` 缩放，见素材缺口清单 §十。
 */

/** 与 `CityPanel.ts` 的 CITY_GRID_WIDTH/HEIGHT 同源；改了那边要同步这里，单测会红。 */
export const SCENE_GRID_WIDTH = 6
export const SCENE_GRID_HEIGHT = 6

export type SceneDistrict = 'crown' | 'civic' | 'military' | 'suburb' | 'wall'

export const DISTRICT_NAMES: Readonly<Record<SceneDistrict, string>> = {
  crown: '王庭高地',
  civic: '行政与民生区',
  military: '城门与军事区',
  suburb: '城郊生产带',
  wall: '城郭与通道',
}

/**
 * 各区地皮的底色（RGB 0-255）。放在引擎无关层，是为了让"同区同色、不成棋盘"
 * 能被 `tests/CityGroundTint.test.ts` 真的判失败 —— 那是一条数据属性，
 * 写在视图里就只能靠人眼看截图。视图侧用 `new Color(...tint, 255)` 包一层。
 *
 * <p>色相按区的功能走，并且**每两区之间拉开到可辨**（单测判欧氏距离 ≥12）：
 * 王庭暖棕（石台 + 火光，R 明显压过 G/B）、行政区蓝灰（B 最高）、军事区暗红（R 压过 B 和 G）、
 * 城郊墨绿（G 双高）、城郭通道中性灰（三者接近）。第一版把王庭和军事区都写成暗棕，
 * 距离只有 6.6 —— 在地面上就是同一块泥，新加的判据当场把它判红了。
 */
export const DISTRICT_TINT_RGB: Readonly<Record<SceneDistrict, readonly [number, number, number]>> = {
  crown: [52, 38, 22],
  civic: [26, 38, 50],
  military: [46, 22, 26],
  suburb: [28, 44, 26],
  wall: [40, 38, 44],
}

/** 36 格地皮颜色，按 `row` 主序、`column` 次序铺开；越界格退回 `wall`。 */
export function groundTintGrid(): readonly (readonly [number, number, number])[] {
  const out: (readonly [number, number, number])[] = []
  for (let row = 0; row < SCENE_GRID_HEIGHT; row += 1) {
    for (let column = 0; column < SCENE_GRID_WIDTH; column += 1) {
      out.push(DISTRICT_TINT_RGB[sceneAnchorAt(column, row)?.district ?? 'wall'])
    }
  }
  return out
}

export interface SceneAnchor {
  /** 稳定 id：由格位算出，**不是数组下标** —— 加格删格不会让老建筑跳到别处。 */
  readonly anchorId: string
  readonly gridX: number
  readonly gridY: number
  readonly district: SceneDistrict
  /** 落地点（工作视图坐标，y 越大越靠画面下方/城门一侧）。 */
  readonly x: number
  readonly y: number
  /** 脚印宽深：决定贴图缩放与点击多边形外接盒。 */
  readonly footprintWidth: number
  readonly footprintDepth: number
  /** 接的道路名，只用于美术对齐与调试文案，不参与玩法。 */
  readonly road: string
}

/**
 * [gridX, gridY, district, x, y, 脚印宽, 脚印深, 道路]。
 *
 * <p>舞台只提供地形与道路，全部功能建筑从真实实例画到基座上。
 * p33 从旧底图塔楼上的热区移到王庭主路基座；其前庭三格避开真实主堡的主体，
 * 防止合法建成的学院等建筑和铭牌被主堡盖住。格位身份、建造校验与服务器存档均不变。
 *
 * <p>坐标口径：1000×625 工作视图，落点是**基座中心**。
 * 格位与显示位置解耦：`(gridX, gridY)` 是服务器坐标，`x/y` 只决定画在哪，
 * 所以重排显示不会动玩家存档。
 *
 * <p>`p33` 是服务器默认主城格；它的显示坐标是地面落点，不再依赖背景里预画的城堡。
 */
const TABLE: ReadonlyArray<readonly [number, number, SceneDistrict, number, number, number, number, string]> = [
  // ── 王庭高地：主堡基座接在地面主轴，主体由 main_city 实例绘制。
  [3, 3, 'crown', 495, 270, 138, 52, '王庭主轴'],
  [3, 1, 'crown', 525, 330, 74, 34, '王庭内阶'],
  [2, 2, 'crown', 430, 315, 72, 34, '主堡前庭西'],
  [4, 2, 'crown', 710, 345, 72, 34, '主堡前庭东'],
  [2, 1, 'crown', 375, 162, 70, 32, '王庭西阶'],
  [4, 1, 'crown', 615, 162, 70, 32, '王庭东阶'],

  // ── 城郊生产带：四缘空地（原伐木场/农田/采石/矿口位置）
  // y 不小于 ~17%：再往上就是底图的远山与天际线，建筑会"浮"在山脊上
  // （实测伐木场落在 y=12% 时悬在画面最顶端、接地阴影压在远山上，一眼假）
  [0, 0, 'suburb', 130, 131, 66, 32, '西北林场'],
  [1, 0, 'suburb', 240, 112, 62, 30, '北坡木场'],
  [2, 0, 'suburb', 340, 131, 62, 30, '北坡田埂'],
  [3, 0, 'suburb', 680, 131, 60, 30, '东北农田'],
  [4, 0, 'suburb', 780, 162, 68, 32, '东田埂'],
  [5, 0, 'suburb', 880, 144, 60, 30, '东北林缘'],
  [0, 1, 'suburb', 80, 250, 62, 30, '西坡矿道'],
  [1, 1, 'suburb', 130, 312, 68, 32, '西工坊场'],
  [5, 1, 'suburb', 930, 231, 62, 30, '东岩采石'],
  [5, 2, 'suburb', 920, 350, 60, 30, '东坡矿口'],
  [5, 3, 'suburb', 930, 275, 60, 30, '东岩台'],
  [5, 5, 'suburb', 940, 444, 60, 30, '东南林缘'],

  // ── 行政与民生区：中左（原学院）、中右（原使馆/医院）
  [1, 2, 'civic', 240, 188, 70, 32, '学院坡道'],
  [3, 2, 'civic', 660, 200, 70, 32, '使馆大道'],
  [1, 3, 'civic', 270, 294, 70, 32, '广场西廊'],
  [2, 3, 'civic', 350, 262, 72, 34, '市场西街'],
  [4, 3, 'civic', 685, 262, 70, 32, '医院小巷'],
  [4, 4, 'civic', 620, 362, 68, 32, '仓储东道'],

  // ── 城门与军事区：左下整片空地 + 城门两翼。y 上限 73%，再往下会被底部选择栏压住
  [0, 2, 'military', 150, 412, 70, 32, '西营外场'],
  [0, 4, 'military', 60, 450, 66, 30, '西营门道'],
  [1, 4, 'military', 280, 456, 66, 30, '营区南道'],
  [2, 4, 'military', 300, 412, 68, 32, '马厩南场'],
  [3, 4, 'military', 380, 375, 66, 32, '校场东道'],
  [5, 4, 'military', 840, 425, 66, 32, '东营道'],
  [0, 5, 'military', 170, 456, 62, 30, '门西外场'],
  [1, 5, 'military', 390, 456, 62, 30, '门西墙道'],
  [2, 5, 'military', 600, 456, 62, 30, '门东营道'],
  [4, 5, 'military', 720, 438, 66, 30, '门东外场'],

  // ── 城郭与通道：城门主轴与城墙沿线
  [0, 3, 'wall', 60, 325, 64, 30, '西墙根'],
  [3, 5, 'wall', 490, 444, 96, 42, '城门内侧'],
]

export const SCENE_ANCHORS: readonly SceneAnchor[] = TABLE.map(
  ([gridX, gridY, district, x, y, w, h, road]) => ({
    anchorId: `p${gridY}${gridX}`,
    gridX, gridY, district, x, y,
    footprintWidth: w, footprintDepth: h, road,
  }),
)

/** 按格位取锚点；越界返回 null（视图据此退回网格表现，不画半截场景）。 */
export function sceneAnchorAt(gridX: number, gridY: number): SceneAnchor | null {
  if (!Number.isInteger(gridX) || !Number.isInteger(gridY)
    || gridX < 0 || gridX >= SCENE_GRID_WIDTH
    || gridY < 0 || gridY >= SCENE_GRID_HEIGHT) {
    return null
  }
  return SCENE_ANCHORS.find((a) => a.gridX === gridX && a.gridY === gridY) ?? null
}

/**
 * 工作视图尺寸：**宽高比必须等于内容区（= 屏幕）的宽高比**，锚点才能与铺满全屏的底图同源。
 *
 * <p>2026-09-21 由 1000×563 改为 1000×625（1.6 = 设计分辨率 960×600 的比例）。
 * 旧的 1.776 与屏幕不同源，投影时只能靠留白找平，热区因此整体偏移。
 */
export const SCENE_VIEW_WIDTH = 1000
export const SCENE_VIEW_HEIGHT = 625

/** 程序化装饰（非参考图模式）的基准尺寸；视图与投影单测共用这一组值。 */
export const SCENE_STAGE_WIDTH = 760
export const SCENE_STAGE_HEIGHT = 440

/**
 * 城景内容区基准尺寸 = Cocos 设计分辨率（960×600）。
 *
 * <p>2026-09-21 起城景**铺满全屏**：规格 §1.1 要的就是满屏城景，而锚点只铺在卡片子矩形里
 * 时，热区永远对不上满屏底图上的建筑（审计 §2.3 的根因之一）。
 * 视图实际取 `view.getVisibleSize()`，这组常量给投影单测与取不到视口时兜底。
 */
export const SCENE_RUNTIME_WIDTH = 960
export const SCENE_RUNTIME_HEIGHT = 600

/** 一格地皮投影到面板之后的落点与尺寸（面板局部坐标：x 右正、y 上正，原点在内容区中心）。 */
export interface ProjectedPlate {
  readonly gridX: number
  readonly gridY: number
  readonly district: SceneDistrict
  readonly road: string
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
  /** 离城门的远近（工作视图 y，越大越靠画面下方）。建筑压叠时的绘制次序按它排。 */
  readonly depth: number
}

export interface SceneLayout {
  /** 工作视图 → 面板的统一缩放。 */
  readonly scale: number
  readonly plates: readonly ProjectedPlate[]
}

/** 地形舞台按原图比例铺满视口，超出的两侧由视口裁切，不能为了满屏压扁地貌。 */
export function coverSceneSize(areaWidth: number, areaHeight: number,
                               imageWidth: number, imageHeight: number): { width: number; height: number } {
  if (!Number.isFinite(imageWidth) || !Number.isFinite(imageHeight)
      || imageWidth <= 0 || imageHeight <= 0) {
    return { width: areaWidth, height: areaHeight }
  }
  const scale = Math.max(areaWidth / imageWidth, areaHeight / imageHeight)
  return { width: imageWidth * scale, height: imageHeight * scale }
}

/** 深度来自画面落地位置，不能用服务端 gridY 代替；锚点表刻意不按网格行布景。 */
export function scenePlatesBackToFront(plates: readonly ProjectedPlate[]): ProjectedPlate[] {
  return [...plates].sort((a, b) => a.depth - b.depth || a.x - b.x
    || (a.gridY * SCENE_GRID_WIDTH + a.gridX) - (b.gridY * SCENE_GRID_WIDTH + b.gridX))
}

/**
 * 把 36 个锚点**按工作视图直接线性映射**进内容区。
 *
 * <p>为什么放在引擎无关层：投影是"格位 → 落点"的唯一换算，视图只照抄。
 * 让它留在 `CityPanelView` 里的话，"退回均匀棋盘"这件事没有任何东西拦得住 ——
 * 而规格 §3.3 禁的正是那个。`tests/CitySceneProjection.test.ts` 判四件事：
 * 全部落在区内、互不重叠、行列间距不再全等、缩放是各向同性（不拉伸）。
 *
 * <p><b>2026-09-21 改算法</b>：旧版按**锚点包围盒**归一化（把 min/max 铺满内容区），
 * 于是 36 个锚点被整体缩放居中，而底图是铺满全屏的 —— 两者因此错开：
 * 实测 p33 偏了约 36 物理像素，底部几格还被选择栏压住。
 * 现在工作视图与内容区同比例，直接 `(锚点 - 视图中心) × scale` 即可，
 * 锚点坐标就是底图坐标，热区天然落在画面里那栋建筑上。
 *
 * <p>坐标口径：锚点 `x/y` 是**落地中心**、`footprintDepth` 向画面下方延伸，
 * 所以脚印在源里占 `y-h/2 … y+h/2`；面板 y 轴朝上，故取负。
 *
 * @param padding 内容区内缩边距，给描边与选中框留位置
 */
export function projectSceneLayout(
  areaWidth: number, areaHeight: number, padding = 0,
): SceneLayout {
  const innerWidth = areaWidth - padding * 2
  const innerHeight = areaHeight - padding * 2
  // 各向同性：两个轴共用一个缩放，否则城会被拉扁——横看是"所有屋顶都变椭圆"
  const scale = Math.min(innerWidth / SCENE_VIEW_WIDTH, innerHeight / SCENE_VIEW_HEIGHT)
  return {
    scale,
    plates: SCENE_ANCHORS.map((anchor) => ({
      gridX: anchor.gridX,
      gridY: anchor.gridY,
      district: anchor.district,
      road: anchor.road,
      x: (anchor.x - SCENE_VIEW_WIDTH / 2) * scale,
      y: (SCENE_VIEW_HEIGHT / 2 - anchor.y) * scale,
      width: anchor.footprintWidth * scale,
      height: anchor.footprintDepth * scale,
      depth: anchor.y,
    })),
  }
}
