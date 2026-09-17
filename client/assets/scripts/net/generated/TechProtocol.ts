/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 学派。取值与 `tech.json` 的 `school` 列 ENUM 声明逐项一致（`TechContractParityTest` 会核这一点，改表枚举不改这里就会红）。
 */
export type TechSchool =
  | 'AGRICULTURE'
  | 'MILITARY'
  | 'COMMERCE'
  | 'FORTIFICATION'

/**
 * 这行科技改的是哪一个数。取值同样与 `tech.json` 的 `effectAttr` 列同源 —— 它是**乘区归属的路标**（B20 §四：不许为科技新开乘区），所以下发原样而不是压成一个通用百分比：客户端要按属性分组显示，而服务端要按属性决定它进产量算式还是进乘区 B。
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
 * 为什么现在不能研究这一行。`NONE` = 没有拦着的（此时 `canResearch=true`），其余四种都是真会发生的情况：
 * `ACADEMY_LOW` 学院等级不够（`requireAcademyLevel`），`QUEUE_BUSY` 队列被占（一次一队列），
 * `RESOURCE_LOW` 资源不够，`MAX_LEVEL` 已满级。
 * `canResearch` 与这一位是**同一次计算的两种读法**（布尔给按钮，枚举给提示文案），都由服务端一处产出，
 * 所以不存在两个家分叉的问题 —— 客户端不许自己按等级与资源再判一遍（那才是第二个家）。
 * 不声明「科技不存在」：那是请求参数错误，直接回错误码，不会出现在列表视图里。
 */
export type TechBlockReason =
  | 'NONE'
  | 'ACADEMY_LOW'
  | 'QUEUE_BUSY'
  | 'RESOURCE_LOW'
  | 'MAX_LEVEL'

/**
 * 资源类型枚举。取值必须与 contract/config/resource.json 的 rows[].id 完全一致（CI 校验）。
 *
 * 取值与配置表 resource 的 id 集合强制一致，由生成器在 CI 中校验。
 */
export type ResourceType =
  | 'WOOD'
  | 'STONE'
  | 'IRON'
  | 'GRAIN'
  | 'GOLD'
  | 'STAMINA'

/**
 * 资源数量条目。用数组而不是 Map 是为了让客户端能保持配置表顺序展示。
 */
export interface ResourceAmount {
  type: ResourceType
  amount: number
}

/**
 * 一行科技的完整状态：是什么、现在几级、下一级要什么、能不能点。客户端不需要读任何配置表。
 */
export interface TechView {
  /** `tech.json` 的行 id。开始研究时原样回传（服务端按它查表，不认下标）。 */
  techId: string
  /** 表里的中文名（客户端不硬编码科技名，否则改表不生效）。 */
  name: string
  /** 学派，界面按它分组。 */
  school: TechSchool
  /** 改的是哪个数。 */
  effectAttr: TechEffectAttr
  /** 每级增益（定点万分比：400 = +4%/级）。表里的 `effectValue` 原样下发，**不乘当前等级** —— 合计由服务端算并用在结算里，这里给的是「下一级会多快」的展示参数。 */
  effectValuePerLevelFixed: number | null
  /** 当前等级。0 = 从没研究过（**服务端账本里不存 0 占位**，读的时候缺失即 0，与联盟科技 `Alliance#techLevel` 同一条读法）。 */
  level: number
  /** 等级上限（表列 `maxLevel`）。 */
  maxLevel: number
  /** 前置：学院建筑要到几级（表列 `requireAcademyLevel`）。学院当前等级另在 `TechListView.academyLevel`，两者一比就是界面的「学院 5 级解锁」。 */
  requireAcademyLevel: number
  /** 研究**下一级**要多少秒（`curve.TECH_TIME`，含建造速度类加成为 0 —— 加速归口在 B20 验收 8 那一步）。已满级时为 0。 */
  nextTimeSec: number
  /** 研究下一级的消耗（只列该行为正的资源，四种全零的行不存在 —— `TechCostCurveTest` 守着）。已满级时为空数组。 */
  nextCost: ResourceAmount[]
  /** 这一行是不是当前队列里的那一项。单独给一位而不是靠 `queue.techId` 推：列表与队列同源于服务端，但界面要在行上直接标记，推一遍就是把判据搬到客户端。 */
  researching: boolean
  /** 服务端算好的「现在点研究会不会成功」。客户端不必（也不许）自己按等级与资源判 —— 那是第二个家，两个家分叉时玩家只信界面。 */
  canResearch: boolean
  /** 拦着的原因；没拦着时是 `NONE`（与 `canResearch=true` 同一次算出）。 */
  blockedReason: TechBlockReason
}

/**
 * 研究队列（一次一队列，所以它就是「那一项」而不是数组）。空闲时 `techId` 与 `finishAt` 为 null、两个时刻字段为 0 —— 与 `BuildingView` 的 `startedAt/totalSeconds` 同一条读法。
 */
export interface TechQueueView {
  /** 在研究哪一行；null = 队列空着。 */
  techId: string | null
  /** 完成时刻（服务端毫秒）。到点之后**不靠定时器**推进：下一次读取时才结算（全项目无 `@Scheduled`，与城建 `collectFinished` 同一套惰性结算）。 */
  finishAt: number | null
  /** 开始时刻（毫秒），配合 `totalSeconds` 让客户端能自己画进度条而不必反复请求。 */
  startedAt: number
  /** 本次研究总时长（秒）。 */
  totalSeconds: number
  /** 服务端算好的剩余秒数（`max(0, finishAt - serverNow)`）。客户端拿 `serverNow` 相减只能算个起点，真正的「还剩多久」以这里为准 —— 绝不为负。 */
  remainingSeconds: number
}

/**
 * `GET /tech/list` 的响应。读取有副作用：与 `/city/list` 一样顺带结算到期研究（否则完成时刻已到的研究在玩家眼里还是「进行中」）。
 */
export interface TechListView {
  /** 整棵树（11 行，顺序 = 表序）。服务端不重排，客户端不许自己排。 */
  techs: TechView[]
  /** 当前队列。 */
  queue: TechQueueView
  /** 学院建筑当前等级（0 = 还没建）。前置校验的唯一数字来源：读的是城建的真实等级，不是玩家说建了几级。 */
  academyLevel: number
  /** 服务端时刻（毫秒）。 */
  serverNow: number
}

/**
 * 开始研究一行科技（下一级）。
 */
export interface TechResearchReq {
  /** 幂等键，与城建/训练/任务同一套（弱网重投不该扣两次资源）。 */
  requestId: string
  /** 要研究哪一行。服务端依次校验：行存在 → 未满级 → 队列空着 → 学院等级够 → 资源够（顺序与城建 `validateUpgrade` 一致，先把「不该花的钱」挡住再扣资源）。 */
  techId: string
}

/**
 * 开始研究的结果。扣掉的资源与算出的时长一并回，界面就不必再拉一次列表才更新得准。
 */
export interface TechResearchResp {
  techId: string
  /** 本次研究的**目标**等级（完成后就是它的新等级）。 */
  level: number
  /** 完成时刻（毫秒）。 */
  finishAt: number
  cost: ResourceAmount[]
  /** 本次研究时长（秒）。必须 ≥ 1 —— 缩短时长的加成一律 `ceil`（§五④），0 秒完成等于没有队列。 */
  timeSec: number
}

/**
 * 取消当前研究。只有 `requestId`：一次一队列 ⇒ 队列里那一项就是被取消的那一项。
 */
export interface TechCancelReq {
  requestId: string
}

/**
 * 取消结果：取消了什么、退回来什么。
 */
export interface TechCancelResp {
  /** 被取消的那一行（客户端据此把行上的标记摘掉）。 */
  techId: string
  /** 返还的资源。比例与城建一致（`city_rule.city_rule_cancel_refund_ratio`，现值 0.60）—— B20 §一 明写「取消返还（比例与城建一致）」，两处各配一个数字迟早会让玩家问「为什么取消建造返 60% 取消研究返 40%」。 */
  refund: ResourceAmount[]
}
