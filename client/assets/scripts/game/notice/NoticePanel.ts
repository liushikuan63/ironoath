/**
 * 职责：运营公告的**客户端视图模型**与「安全刷新点」的判定（收口清单 #2287 ⑤ / V26-2）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：哪些公告在生效、它们的顺序，全部由服务端算好
 * （`GET /notice/list` 的 `notices`）⇒ 客户端只把服务端的事实转成界面语言。
 * 客户端自己比时间会出「该显示时没显示 / 过期了还在显示」，而两边都觉得自己没错。
 *
 * <p><b>安全刷新点是什么意思</b>：公告是**按需打开**的（不是登录时拉一次然后一直显示）⇒
 * 玩家打开公告面板的那一刻去取一次，就是一个不会打断当前交互的刷新点。
 * 取回来的响应带 notice 表的版本号 ⇒ **版本没变就不重绘**：拉一次是真的，
 * 但没变就别让玩家看见界面闪一下（那是最容易被当成 bug 报上来的观感）。
 */

import type { NoticeItem, NoticeListResp } from '../../net/generated/NoticeProtocol'

/** 公告面板的一行（界面语言）。 */
export interface NoticeRow {
  readonly id: string
  readonly title: string
  readonly body: string
}

/** 公告面板的视图模型。 */
export interface NoticeView {
  readonly rows: readonly NoticeRow[]
  /** 面板顶部的说明行；没有公告时是一句「暂无公告」，不是空面板。 */
  readonly summary: string
  /**
   * 本次响应的**内容 hash**（服务端算，SHA-256 前 16 位十六进制）。
   *
   * <p>为什么是 hash 而不是人工维护的表版本：表版本在「内容变了却忘了升版本」时不动
   * ⇒ 客户端以为没变而继续显示旧文案（症状是「改了公告没反应」）；
   * 而 hash 由内容算出 ⇒ 内容变它就变。它还覆盖「服务端时刻跨过生效窗口」——
   * 那一刻表内容没变、返回的列表变了，hash 照样变。
   */
  readonly version: string
}

/**
 * 一次刷新的结果。
 *
 * <p>`changed = false` 表示「服务端说内容没变」⇒ 界面**不动**（不重绘、不闪）。
 */
export interface NoticeRefresh {
  readonly view: NoticeView
  readonly changed: boolean
}

/**
 * 把服务端的响应转成界面语言。
 *
 * @param resp `GET /notice/list` 的响应；null 表示这次没拿到（页面照常能用）
 */
export function buildNoticeView(resp: NoticeListResp | null): NoticeView {
  const notices: readonly NoticeItem[] = resp?.notices ?? []
  const rows = notices.map((item) => ({
    id: item.id,
    title: item.title,
    body: item.body,
  }))
  return {
    rows,
    // 摘要念「有几条 + 最新一条是谁」：玩家在列表上方一眼看到有没有要紧的事。
    summary: rows.length === 0
      ? '暂无公告'
      : `共 ${rows.length} 条 · 最新：${rows[0]?.title ?? ''}`,
    version: resp?.version ?? '',
  }
}

/**
 * 判定这次要不要重绘。
 *
 * <p><b>为什么版本没变就不重绘</b>：刷新点的意义是「拉一次」而不是「每次都重画一遍」——
 * 重画会让玩家正在看的那一屏闪一下，而公告内容没变时闪一下纯属自找投诉。
 *
 * <p><b>为什么用表版本而不是条数</b>：条数不变而文案改了（最常见的运营动作）时，
 * 条数版会让客户端以为没变而继续显示旧文案 ⇒ 症状是「改了公告没反应」。
 *
 * @param previous 上一次用过的内容 hash；null / undefined 表示「没记过 ⇒ 当作变了」
 */
export function refreshNoticeView(
  resp: NoticeListResp | null,
  previousVersion: string | null | undefined,
): NoticeRefresh {
  const view = buildNoticeView(resp)
  const known = typeof previousVersion === 'string' && previousVersion.length > 0
  const changed = !known || view.version !== previousVersion
  return { view, changed }
}
