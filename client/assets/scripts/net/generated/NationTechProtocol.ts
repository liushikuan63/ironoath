/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 学派。与 `tech.schema.json` 的同名 def 逐字段一致（个人科技与国家科技共用这一份词汇，不分叉）。
 */
export type TechSchool =
  | 'AGRICULTURE'
  | 'MILITARY'
  | 'COMMERCE'
  | 'FORTIFICATION'

/**
 * 这一行改的是哪一个数。与 `tech.schema.json` 同名 def 同形；`nation_tech.json` 只声明其中四个取值，
 * 但枚举本身不裁剪 —— 否则同一属性在两张表里会生成两个不同的枚举名，而它们进的是同一个乘区/同一条算式（§五④：同类相加）。
 */
export type TechEffectAttr =
  | 'WOOD_OUTPUT'
  | 'STONE_OUTPUT'
  | 'IRON_OUTPUT'
  | 'GRAIN_OUTPUT'
  | 'UNIT_ATTACK'
  | 'UNIT_DEFENSE'
  | 'MARCH_SPEED'
  | 'TRAIN_SPEED'
  | 'BUILD_SPEED'
  | 'HOSPITAL_CAPACITY'
  | 'LOAD_CAPACITY'

/**
 * 为什么现在不能研究这一行。`NONE` = 没拦着（此时 `canResearch=true`）。其余四种都是真会发生的：
 * `NATION_LOW` 国家等级不足（表列 `requireNationLevel`），`TREASURY_LOW` 国库余额不足，`MAX_LEVEL` 已满级，
 * `NOT_OFFICER` 操作者没有研究权限（读的是 `role_permission` 表，不在这里硬编码官职）。
 * 与个人科技那份 `TechBlockReason` **刻意不合并成一个 def**：个人那一份有 `QUEUE_BUSY`（一次一队列）而国家根本没有队列，
 * 合并会让其中一位永久无意义 —— 两个枚举各自的取值都必须能被触发，才是诚实的契约。
 */
export type NationTechBlockReason =
  | 'NONE'
  | 'NATION_LOW'
  | 'TREASURY_LOW'
  | 'MAX_LEVEL'
  | 'NOT_OFFICER'

/**
 * 国家科技的一行。与个人科技的 `TechView` 同形而**不共用 def**：国家没有研究队列与剩余时间，
 * 共用会把 `researching` / `nextTimeSec` 两位变成恒零字段（"有名字零读取点"的契约版本）。
 */
export interface NationTechView {
  /** `nation_tech.json` 的行 id，研究时原样回传（服务端按它查表，不认下标）。 */
  techId: string
  /** 表里的中文名，客户端不硬编码。 */
  name: string
  /** 学派，界面按它分组。 */
  school: TechSchool
  /** 改的是哪个数。国家这一份与个人科技的同属性**相加后作用一次**（§五④），所以这里给的是属性路标而不是一个通用百分比。 */
  effectAttr: TechEffectAttr
  /** 每级增益（定点万分比：400 = +4%/级）。表里的 `effectValue` 原样下发，不乘当前等级 —— 合计由服务端算。 */
  effectValuePerLevelFixed: number | null
  /** 当前等级。0 = 全国还没研究过这一行（国家账本里不存 0 占位，与 `PlayerTech.levelOf` / `Alliance#techLevel` 同一条读法）。 */
  level: number
  /** 等级上限（表列 `maxLevel`）。 */
  maxLevel: number
  /** 前置：国家要到几级（表列）。国家的当前等级在 `NationTechListView.nationLevel`，读的是 `Nation.level()`（由成员联盟数落到 `nation_config` 的三级规则），不是玩家报的数。 */
  requireNationLevel: number
  /** 研究下一级要花多少国库。曲线 `BUILDING_COST`（比率 1.22，复用）× 表列 `costBaseTreasury`。满级时为 0。 */
  nextCostTreasury: number
  /** 服务端算好的"现在点研究会不会成功"（含权限位判定）。客户端不许自己按等级与国库判第二遍 —— 那是第二个家。 */
  canResearch: boolean
  /** 拦着的原因；没拦着时是 `NONE`（与 `canResearch` 同一次算出）。 */
  blockedReason: NationTechBlockReason
}

/**
 * `GET /nation/tech` 的响应。四行全量下发（不分页）：行数由表决定，客户端不该知道有几行。
 * **玩家没有国家时不走这个视图**，直接是既有拒绝 `NATION_NOT_FOUND`（与 `/nation` 同一枚码、同一句理由），
 * 所以这里的 `nationId` 与 `treasury` 恒非空 —— 不声明"可选的国家"这种要让客户端猜的形状。
 */
export interface NationTechListView {
  nationId: string
  nationName: string
  /** 国家当前等级（`Nation.level()`）。所有 `requireNationLevel` 比较的唯一数字来源。 */
  nationLevel: number
  /** 国库**已结算税收之后**的余额。花费判定读的就是这一位。 */
  treasury: number
  /** 四行（顺序 = 表序，服务端不重排）。 */
  techs: NationTechView[]
  /** 服务端时刻（毫秒）。国库支出的**周限额**按这一位算周，客户端不许自己算（那是第二个家）。 */
  serverNow: number
}

/**
 * 研究一级国家科技。
 */
export interface NationTechResearchReq {
  /** 幂等键。**国家层面这条比个人更要紧**：国库是公共池，弱网重投若不去重，症状是同一级研究扣了两笔公共钱，而没有任何一个人的余额因此变少 —— 那类错账只能靠日志对。 */
  requestId: string
  /** 研究哪一行。服务端依次校验：行存在 → 有权限 → 未满级 → 国家等级够 → 国库够（先把"不该花的公共钱"挡住再扣）。 */
  techId: string
}

/**
 * 研究结果。花掉多少、剩下多少一并回：国库是公共账，界面若不回余额，就得再拉一次列表才能确认钱真的只扣了一次。
 */
export interface NationTechResearchResp {
  techId: string
  /** 研究完之后的等级（这一行是即时生效，没有队列与完成时刻）。 */
  level: number
  /** 本次从国库扣掉的数额（走 `Nation.Sink.NATIONAL_TECH` 核销，日志里那行 `sink:national_tech` 记的就是它）。 */
  costTreasury: number
  /** 扣完之后国库余额。 */
  treasuryAfter: number
}
