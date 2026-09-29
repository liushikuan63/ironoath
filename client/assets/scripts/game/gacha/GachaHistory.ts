/**
 * 职责：抽卡记录（最近 N 次）的数据组装 —— B15 §三「合规三件套」之一
 * （概率公示 + 最近 50 次可查 + 日志保留 90 天，三者缺一即构成公示不实；公示那半在 `GachaDisclosure.ts`）。
 * 依赖：生成的协议类型 + `game/ui/PanelPaging`（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>这一层不做任何计算，只做「响应 → 展示文本」的搬运</b>：条数窗口由服务端定
 * （`GACHA_HISTORY_LIMIT`，`:411` 的注释写明"查询窗口与保留窗口是两个口径"），保留天数由
 * `retentionDays` 随行下发。客户端再截一次就是给自己造第二个真相。
 *
 * <p><b>时间一律相对 `serverNow` 算</b>（铁律 5：客户端不引本机时钟）。记录里没有日期字符串、
 * 也没有时区，只有一个服务端毫秒 —— 拿本机时间格式化会在跨时区与改过系统时间的机器上对玩家说谎。
 *
 * <p><b>id 不许直接上屏</b>：记录里只有 `poolId` / `heroId`，名字必须从随行下发的那一份里取
 * （同 #255 建筑名 / #268 资源名 / #303 兵种名那一族）；取不到时给「未知卡池 / 未知武将」，
 * 绝不把 `pool_std`、`hero_guanyu` 印给玩家。
 */

import type { GachaHistoryResp } from '../../net/generated/PayProtocol'
import { elapsedText, JUST_NOW } from '../ui/ElapsedText'
import { clampPage, pageCount, pageNotice, pageWindow } from '../ui/PanelPaging'

/**
 * 「多久以前」那份算式住在 `game/ui/ElapsedText.ts`（国库流水要的是同一种话，
 * 同一个格式化函数只能有一个家）。这里转出去一份是**为了不打断既有调用方**
 * （单测与面板都从本模块取），不是"这里还留着一个实现"。
 */
export { elapsedText, JUST_NOW }

/**
 * 一页排几条。
 *
 * <p><b>这是版式常量，不是业务口径</b>：服务端给 50 条（`GACHA_HISTORY_LIMIT`），
 * 而最小可视高（960×600 那档）减去标题行与页码行之后，44px 的行只排得下 8 条。
 * 取大值会让最后一行压在页码上（#262/#273 那一族），取小值只是多翻一次。
 */
export const HISTORY_PAGE_SIZE = 8

/** 记录里没有中文名，名字必须另外喂进来。 */
export interface GachaNameTables {
  readonly poolNames: ReadonlyMap<string, string>
  readonly heroNames: ReadonlyMap<string, string>
}

/** 查不到名字时的回退语。**不是**把 id 印出来。 */
export const UNKNOWN_POOL = '未知卡池'
export const UNKNOWN_HERO = '未知武将'

/** 面板上的一行。 */
export interface GachaHistoryRow {
  /** 行的稳定键：同一时刻连抽会让 `time` 重复，不能拿它当 key。 */
  readonly key: string
  readonly heroName: string
  readonly poolName: string
  readonly timeText: string
  /** 保底标记。协议里 `isPity` 的注释写明「必须下发」：公示写了保底，玩家就要能在记录里验证它生效过。 */
  readonly pityText: string | null
}

/** 一份完整的抽卡记录视图。 */
export interface GachaHistoryView {
  readonly rows: readonly GachaHistoryRow[]
  /** 空态那一句（有记录时为 null）。 */
  readonly emptyText: string | null
  readonly retentionText: string
  readonly pageNotice: string
  readonly total: number
  readonly page: number
  readonly pages: number
}

/**
 * 由 `/gacha/history` 的响应组装记录页。
 *
 * @param resp 服务端响应（`records` 已按时间倒序，**客户端不得重排**）
 * @param names 卡池名与武将名的查找表（来自 `/gacha/pools` 与 `/hero/list` 随行下发的那一份）
 * @param page 请求的页码（越界会被夹回最后一页，不抛异常 —— 抽完一轮后记录变多，页码可能已经失效）
 */
export function buildGachaHistory(resp: GachaHistoryResp, names: GachaNameTables,
  page = 0): GachaHistoryView {
  const records = resp?.records ?? []
  const total = records.length
  const pages = pageCount(total, HISTORY_PAGE_SIZE)
  const safe = clampPage(page, total, HISTORY_PAGE_SIZE)
  const window = pageWindow(total, safe, HISTORY_PAGE_SIZE)
  const rows: GachaHistoryRow[] = records.slice(window.start, window.end).map((record, index) => ({
    key: `${window.start + index}`,
    heroName: names.heroNames.get(record.heroId) ?? UNKNOWN_HERO,
    poolName: names.poolNames.get(record.poolId) ?? UNKNOWN_POOL,
    timeText: elapsedText(resp.serverNow - record.time),
    pityText: record.isPity === true ? '保底' : null,
  }))
  return {
    rows,
    emptyText: total === 0
      ? '还没有抽取记录：抽过的每一次都会记在这里，供你核对公示概率'
      : null,
    retentionText: `记录保留 ${resp.retentionDays} 天`,
    pageNotice: pageNotice(safe, pages),
    total,
    page: safe,
    pages,
  }
}
