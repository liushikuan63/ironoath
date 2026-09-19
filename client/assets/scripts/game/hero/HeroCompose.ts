/**
 * 职责：武将碎片合成（V03-d 第六条养成线，B06 §1）的行组装与"够不够"判定。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>这一格没有一个是客户端算出来的数</b>：门槛取 `FragmentView.composeFragment`、
 * 余额取 `FragmentView.count`、"哪些武将能合成"取 `FragmentView.candidates`
 * （服务端已经把已拥有的排除了）。客户端既没有 hero 表也没有 hero_rarity 表 ——
 * 抄一份的后果是表一改就见人说"够了"，然后被服务端拒，玩家看到的是"我点坏了"。
 *
 * <p><b>"还差几片"要写在行上，而不是把不够的行藏掉</b>：藏掉的语义是"这个武将不存在"，
 * 真相是"你还差 38 片"。前者会让人以为养成线断了（觉醒弹层立的是同一条纪律）。
 *
 * <p><b>行上不重复报余额</b>：余额是**钱包**（各档碎片持有多少），由 `HeroPanel.fragmentTexts`
 * 那份真源画在弹层抬头（2026-09-20 并行会话的裁决：字段留着、落点是合成页）。
 * 行上只报"这一档要多少、还差多少"—— 同一个数在一屏出现两次就是噪音（#273 那张截图的教训）。
 *
 * <p><b>为什么没有"选哪一档碎片"这一步</b>：碎片是按稀有度的一档通用池，
 * 花哪一档由**武将的稀有度**决定，所以候选行自带自己那一档的门槛与差数。
 */

import type { FragmentView } from '../../net/generated/HeroProtocol'

export interface HeroComposeRow {
  readonly heroId: string
  /** 武将中文名（服务端 hero 表的 name 列，随 candidates 下发） */
  readonly name: string
  /** 按该档余额算出来的：够不够合成这一个 */
  readonly usable: boolean
  /** 行上第二行字。永远有字 —— 空着玩家会以为在加载 */
  readonly detailText: string
}

export interface HeroComposeView {
  readonly rows: readonly HeroComposeRow[]
  /** 选中的那个武将。灰掉的行不算选中（点了也发不出去） */
  readonly selectedHeroId: string | null
  /** 没选中可用的武将时为 false：视图据此把确认键置灰 */
  readonly canSend: boolean
  /** 确认键上的字 */
  readonly sendText: string
  /** 标题下面那行总览（几名可合成、几名已经凑够） */
  readonly summaryText: string
  /** 一个候选都没有时的说明行；有候选时为 null */
  readonly emptyText: string | null
}

/**
 * 把各档碎片行摊平成候选武将行：**能合成的排前面**，两组内部各自照服务端下发的顺序（档序 × hero 表行序）。
 *
 * <p><b>为什么要这一道排序</b>：一屏画不下全部候选时视图按可视高度截断（并写"另有 N 名未列出"），
 * 而截断切掉的是尾部 —— 不排序时"还差 80 片"的那几名会把"现在就能合成"的那一名挤到屏外，
 * 玩家看到一整屏灰行，唯一能点的那一个根本不在屏上。
 * 排序只决定**谁先出现**，不决定谁能合成（那由服务端下发的门槛与余额算，客户端不抄表）。
 */
export function composeRows(fragments: readonly FragmentView[]): readonly HeroComposeRow[] {
  const out: HeroComposeRow[] = []
  for (const purse of fragments ?? []) {
    const need = purse.composeFragment
    const missing = purse.count >= need ? 0 : need - purse.count
    for (const hero of purse.candidates ?? []) {
      out.push({
        heroId: hero.heroId,
        name: hero.name,
        usable: missing === 0,
        detailText: missing === 0
          ? `${purse.name} 需 ${need} 片 · 碎片已够`
          : `${purse.name} 需 ${need} 片 · 还差 ${missing} 片`,
      })
    }
  }
  // sort 是稳定的（ES2019 起规范保证）⇒ 两组内部仍是服务端那份顺序
  return out.sort((a, b) => Number(b.usable) - Number(a.usable))
}

/** 组装整个弹层的视图。选中项只在**可用**的行里成立。 */
export function buildComposeView(fragments: readonly FragmentView[],
  selectedHeroId: string | null = null): HeroComposeView {
  const rows = composeRows(fragments)
  const chosen = rows.find((row) => row.heroId === selectedHeroId && row.usable)
  const ready = rows.filter((row) => row.usable).length
  return {
    rows,
    selectedHeroId: chosen === undefined ? null : chosen.heroId,
    canSend: chosen !== undefined,
    sendText: chosen !== undefined ? '确认合成' : rows.length === 0 ? '暂无可合成武将' : '先选一名武将',
    summaryText: rows.length === 0
      ? '没有可以合成的武将'
      : `${rows.length} 名武将可以合成 · 其中 ${ready} 名碎片已凑够`,
    // 只说事实：为什么一个都没有由服务端那一行的口径回答（全已拥有 / 这一档没投放碎片），
    // 弹层再猜一遍就是第二份会过期的文案
    emptyText: rows.length === 0 ? '未拥有的武将里，没有可以用碎片合成的' : null,
  }
}
