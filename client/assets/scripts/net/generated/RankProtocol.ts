/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 榜的类型（B23 §一 1）。与 SeasonSettlement.Board 的四个值一一对应 —— 刻意不复用那个枚举：那个是结算侧的领域类型，客户端不该依赖结算的内部形状。
 */
export type RankType =
  | 'POWER'
  | 'KILL'
  | 'ALLIANCE'
  | 'NATION'

/**
 * 榜上的一条。tag 只对组织榜有意义（联盟缩写），个人榜为 null —— 与联盟成员列表里的 tag 同一套来源。
 */
export interface RankEntryView {
  /** 名次，从 1 起。 */
  rank: number
  /** 主角 id：个人榜是 playerId，组织榜是 allianceId / nationId。 */
  id: string
  /** 显示名（服务端拼好下发）。 */
  name: string
  /** 榜值：POWER = MatchPower，KILL = 赛季击杀累计，组织榜 = 成员赛季分合计。 */
  value: number
  /** 组织缩写；个人榜为 null。 */
  tag: string | null
}

/**
 * GET /rank/list 与 /rank/me 的响应（同形：/rank/me 只带"我的那一行"与我的名次）。myRank 为 null 表示未上榜，不许用 0 冒充（B23 验收 2，与赛季 myRank 同一条纪律：0 会与"第 0 名"混淆，而名次从 1 起）。
 */
export interface RankListResp {
  /** 这一页是哪个榜。 */
  type: RankType
  /** 这一页的行，按名次升序。 */
  entries: RankEntryView[]
  /** 我的名次；未上榜为 null。 */
  myRank: number | null
  /** 我的榜值；未上榜为 null。 */
  myValue: number | null
  /** 请求的页码，原样回显（B23 验收 6 的体积判据要靠它复现同一页）。 */
  page: number
  /** 本次实际生效的每页条数（服务端夹过：上限来自 global.RANK_PAGE_SIZE_MAX）。 */
  pageSize: number
  /** 后面还有没有下一页。 */
  hasMore: boolean
}

/**
 * GET /rank/snapshot 的响应 —— 某一天的每日快照里**我的那一行**。刻意没有 entries：裁决③（可见性）定的是"玩家只能查自己"，全服历史名次是情报（与"不下发精确距离"同一条思路）。运营要全量走 /ops/rank/snapshot。
 */
export interface RankSnapshotResp {
  /** 查的是哪个榜。 */
  type: RankType
  /** 日期键，yyyyMMdd（UTC+8，与全项目同一个 DayKey —— 不许出现第二个日切轴）。 */
  dayKey: string
  /** 这一份快照的拍摄时刻（毫秒）。同一天重复读不会刷新它 —— 「同一天只拍一份」的唯一可证形态。 */
  snapshotAt: number
  /** 我在那一天的榜上名次；那天榜上没有我时为 null（不许用 0 冒充）。 */
  myRank: number | null
  /** 我在那一天的榜值；未上榜为 null。 */
  myValue: number | null
}

/**
 * GET /ops/rank/snapshot 的响应 —— 某一天某张榜的**全量**与分页（裁决③：运营侧走 ops 只读端点全量）。申诉时要能回答"那天第 37 名是多少分"，所以这里必须给出整榜而不是某一个人。
 */
export interface OpsRankSnapshotResp {
  /** 哪张榜。 */
  type: RankType
  /** 日期键，yyyyMMdd（UTC+8）。 */
  dayKey: string
  /** 这份快照的拍摄时刻（毫秒）。 */
  snapshotAt: number
  /** 这一页的行，按名次升序。 */
  entries: RankEntryView[]
  /** 这一天这张榜上共有多少人（分页之外的第二信息：运营要一眼看出那天有多热闹）。 */
  totalPeople: number
  /** 请求的页码，原样回显。 */
  page: number
  /** 本次实际生效的每页条数。 */
  pageSize: number
  /** 后面还有没有下一页。 */
  hasMore: boolean
}
