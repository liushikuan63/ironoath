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

/** [gridX, gridY, district, x, y, 脚印宽, 脚印深, 道路]；行 0 是最北（远山），行 5 是城门一侧。 */
const TABLE: ReadonlyArray<readonly [number, number, SceneDistrict, number, number, number, number, string]> = [
  [0, 0, 'suburb', 132, 118, 96, 46, '北坡小道'],
  [1, 0, 'suburb', 268, 104, 104, 48, '北坡小道'],
  [2, 0, 'wall', 402, 92, 88, 40, '北垣步道'],
  [3, 0, 'wall', 556, 92, 88, 40, '北垣步道'],
  [4, 0, 'suburb', 700, 108, 112, 50, '东田埂'],
  [5, 0, 'suburb', 858, 122, 104, 48, '东田埂'],
  [0, 1, 'suburb', 118, 208, 100, 52, '北坡小道'],
  [1, 1, 'crown', 250, 196, 112, 58, '台基西阶'],
  [2, 1, 'crown', 400, 176, 150, 76, '主堡前庭'],
  [3, 1, 'crown', 566, 176, 150, 76, '主堡前庭'],
  [4, 1, 'crown', 716, 198, 112, 58, '台基东阶'],
  [5, 1, 'suburb', 868, 220, 100, 52, '东田埂'],
  [0, 2, 'wall', 112, 296, 92, 48, '西墙根'],
  [1, 2, 'civic', 244, 288, 116, 60, '学院支路'],
  [2, 2, 'crown', 392, 282, 128, 62, '台阶口'],
  [3, 2, 'crown', 556, 282, 128, 62, '台阶口'],
  [4, 2, 'civic', 712, 292, 116, 60, '使馆大道'],
  [5, 2, 'wall', 872, 302, 92, 48, '东墙根'],
  [0, 3, 'military', 116, 372, 100, 52, '校场便道'],
  [1, 3, 'civic', 250, 372, 118, 60, '医院小巷'],
  [2, 3, 'civic', 398, 366, 122, 62, '广场西廊'],
  [3, 3, 'civic', 560, 366, 122, 62, '广场东廊'],
  [4, 3, 'civic', 716, 372, 118, 60, '仓储车道'],
  [5, 3, 'wall', 876, 378, 92, 48, '东墙根'],
  [0, 4, 'military', 122, 444, 104, 54, '箭场长道'],
  [1, 4, 'military', 256, 444, 116, 58, '营区主道'],
  [2, 4, 'military', 404, 448, 120, 60, '营区主道'],
  [3, 4, 'military', 562, 448, 120, 60, '营区主道'],
  [4, 4, 'civic', 718, 444, 116, 58, '仓储车道'],
  [5, 4, 'wall', 878, 450, 92, 48, '东墙根'],
  [0, 5, 'suburb', 128, 512, 96, 44, '西坡矿道'],
  [1, 5, 'military', 262, 516, 104, 46, '马厩围场'],
  [2, 5, 'wall', 410, 528, 108, 40, '门洞西侧'],
  [3, 5, 'wall', 560, 528, 108, 40, '门洞东侧'],
  [4, 5, 'suburb', 716, 516, 104, 46, '东坡矿道'],
  [5, 5, 'suburb', 872, 512, 96, 44, '东坡矿道'],
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

/** 工作视图尺寸，供视图算统一缩放。 */
export const SCENE_VIEW_WIDTH = 1000
export const SCENE_VIEW_HEIGHT = 563

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

/**
 * 把 36 个锚点**等比**投影进面板的一块矩形内容区。
 *
 * <p>为什么放在引擎无关层：投影是"格位 → 落点"的唯一换算，视图只照抄。
 * 让它留在 `CityPanelView` 里的话，"退回均匀棋盘"这件事没有任何东西拦得住 ——
 * 而规格 §3.3 禁的正是那个。`tests/CitySceneProjection.test.ts` 判四件事：
 * 全部落在区内、互不重叠、行列间距不再全等、缩放是各向同性（不拉伸）。
 *
 * <p>坐标口径：锚点 `x/y` 是**落地中心**、`footprintDepth` 向画面下方延伸，
 * 所以脚印在源里占 `y-h/2 … y+h/2`（与 A16 热区图同源）；面板 y 轴朝上，故取负。
 *
 * @param padding 内容区内缩边距，给描边与选中框留位置
 */
export function projectSceneLayout(
  areaWidth: number, areaHeight: number, padding = 0,
): SceneLayout {
  const innerWidth = areaWidth - padding * 2
  const innerHeight = areaHeight - padding * 2
  const left = Math.min(...SCENE_ANCHORS.map((a) => a.x - a.footprintWidth / 2))
  const right = Math.max(...SCENE_ANCHORS.map((a) => a.x + a.footprintWidth / 2))
  const top = Math.min(...SCENE_ANCHORS.map((a) => a.y - a.footprintDepth / 2))
  const bottom = Math.max(...SCENE_ANCHORS.map((a) => a.y + a.footprintDepth / 2))
  // 各向同性：两个轴共用一个缩放，否则城会被拉扁——横看是"所有屋顶都变椭圆"
  const scale = Math.min(innerWidth / (right - left), innerHeight / (bottom - top))
  const centerX = (left + right) / 2
  const centerY = (top + bottom) / 2
  return {
    scale,
    plates: SCENE_ANCHORS.map((anchor) => ({
      gridX: anchor.gridX,
      gridY: anchor.gridY,
      district: anchor.district,
      road: anchor.road,
      x: (anchor.x - centerX) * scale,
      y: -(anchor.y - centerY) * scale,
      width: anchor.footprintWidth * scale,
      height: anchor.footprintDepth * scale,
      depth: anchor.y,
    })),
  }
}
