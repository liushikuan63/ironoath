/**
 * 职责：面板行区的分页算术（B26 S10）—— 纯逻辑，不碰引擎。
 * 依赖：无。
 *
 * <p>为什么单独一份：社交面板一屏只画得下 5 行（`rowCapacity` 按可视高现算，
 * 1280×720 实测），而"概况 + 退出 + 待审申请 + 科技 6 项 + 捐献 3 档 + 成员若干"明显放不下。
 * 原先的处理是"最后一格换成那句『另有 N 项未显示』"—— 话说诚实了，但**下面的东西永远拿不到**，
 * 于是"功能看不见"换了件衣服回来（收口清单 #307 就是被这件事撞红的）。
 * 分页把它变成"拿得到"，所以这里只算三件事：共几页、当前页能排第几行、越界了夹回哪一页。
 */

/** 共几页（`perPage` 至少 1，空列表算 1 页而不是 0 页 —— 0 页会让"上一页"除零）。 */
export function pageCount(total: number, perPage: number): number {
  if (total <= 0) {
    return 1
  }
  return Math.ceil(total / Math.max(1, perPage))
}

/**
 * 本页真正放得下几条**内容**。装不下时把最后一格让给页码行：内容行少一行，
 * 但每一行都够得着。
 *
 * <p>为什么单列：这条算式原先在社交面板里抄了三遍、又在目标搜索面板里抄了两遍。
 * 共几页、夹到哪一页、切哪一段必须用**同一个** perPage，抄一份就是留一个将来会分叉的口径。
 */
export function contentPerPage(total: number, capacity: number): number {
  const safe = Math.max(1, capacity)
  return Math.max(1, total > safe ? safe - 1 : safe)
}

/** 页码越界就夹回来：换页签、批完一条申请、科技少了一行之后，都不该停在空白页上。 */
export function clampPage(page: number, total: number, perPage: number): number {
  const last = pageCount(total, perPage) - 1
  return Math.min(Math.max(0, page), last)
}

/** 当前页要画的那一段下标区间（半开区间，直接给 `slice` 用）。 */
export function pageWindow(total: number, page: number, perPage: number): { start: number, end: number } {
  const safe = clampPage(page, total, perPage)
  const start = safe * Math.max(1, perPage)
  return { start, end: Math.min(total, start + Math.max(1, perPage)) }
}

/** 那一行上写给玩家看的话：只说"第几页/共几页"，不出现工程术语。 */
export function pageNotice(page: number, pages: number): string {
  return `第 ${page + 1}/${pages} 页`
}
