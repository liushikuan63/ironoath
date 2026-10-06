/**
 * 职责：排行榜面板的展示组装用例（B23 §一 3 / 验收 6 的客户端半边）。
 * 依赖：node:test + game/power/RankBoard（纯逻辑，不碰 cc）。
 *
 * <p><b>这些用例盯的是三件事</b>：① 客户端<b>不重算名次</b>（B23 禁止项）—— 名次/页号/有没有下一页
 * 全部照搬服务端；② 「我的名次」是**独立摘要行**，不是把我插进列表（插行会让页面上出现两个第 N 名）；
 * ③ 四张榜的量纲标签各不同（战力/击杀/联盟赛季分/国家赛季分），不能被同一个「值」字糊过去。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildRankBoard, buildRankSnapshotView, boardHintOf, isBoardTab, isPersonalBoard, valueLabelOf, RANK_TABS,
} from '../assets/scripts/game/power/RankBoard'
import type { RankEntryView, RankListResp } from '../assets/scripts/net/generated/RankProtocol'

function entry(rank: number, id: string, name: string, value: number,
               tag: string | null = null): RankEntryView {
  return { rank, id, name, value, tag }
}

function list(overrides: Partial<RankListResp> = {}): RankListResp {
  return {
    type: 'POWER',
    entries: [entry(1, 'P-1', '老王', 12345), entry(2, 'P-2', '小李', 900)],
    myRank: 2,
    myValue: 900,
    page: 1,
    pageSize: 20,
    hasMore: false,
    // 今天由**服务端**下发（`/rank/list` 带 dayKey）：客户端拿它去查 /rank/snapshot，
    // 自己算日期就是契约禁止的"第二条日切轴"。夹具里写死一天，让这条契约在测试里也看得见。
    dayKey: '20260922',
    ...overrides,
  }
}

test('名次、页号、有没有下一页全部照搬服务端（客户端不重算名次）', () => {
  const view = buildRankBoard(list({ page: 3, hasMore: true, myRank: 57, myValue: 42 }), 'POWER', 'P-9')
  assert.equal(view.pageText, '第 3 页')
  assert.equal(view.canPrev, true, '第 3 页当然能往前')
  assert.equal(view.canNext, true, '服务端说 hasMore=true，客户端不许自己猜')
  assert.equal(view.rows[0]?.rankText, '第 1 名')
  assert.equal(view.rows[0]?.valueText, '12,345', '大数字带千分位')
  assert.equal(view.mine?.rankText, '第 57 名', '我的名次是服务端下发的 57，不是我在这页里的位置')
})

test('我的名次是独立摘要行：即使我不在这一页里，它也在（且列表里不会多出第二个第 N 名）', () => {
  const view = buildRankBoard(list({ myRank: 57, myValue: 42, page: 3 }), 'POWER', 'P-9')
  assert.equal(view.rows.length, 2, '列表只有服务端给的两行 —— 我没有被插进去')
  assert.deepEqual(view.rows.map(r => r.rank), [1, 2])
  assert.equal(view.mine?.valueText, '42')
  assert.equal(view.mine?.valueLabel, '匹配战力')
  assert.equal(view.notRankedText, null, '上榜了就不该有"你没上榜"的说明')
})

test('未上榜：摘要行是 null，说明文字给出下一步（不是一句"暂无数据"）', () => {
  const view = buildRankBoard(list({ myRank: null, myValue: null }), 'POWER', 'P-9')
  assert.equal(view.mine, null)
  assert.match(view.notRankedText ?? '', /还没有上榜/)
  assert.equal(view.rows.length, 2, '我自己没上榜不影响别人在榜上')
})

test('只有个人榜才可能"这一行是我"：组织榜的行是联盟/国家，不许标成我', () => {
  const alliance = list({
    type: 'ALLIANCE', myRank: 3, myValue: 500,
    entries: [entry(1, 'A-1', '铁血盟[TTX]', 900, 'TTX'), entry(2, 'A-2', '夜枭[YX]', 700, 'YX')],
  })
  // 联盟 id 恰好等于我的 playerId 也不行 —— 两个 id 空间不同，比 id 相等是错的
  const view = buildRankBoard(alliance, 'ALLIANCE', 'A-1')
  assert.deepEqual(view.rows.map(r => r.mine), [false, false])
  assert.equal(view.rows[0]?.tag, 'TTX', '联盟缩写照搬')
  assert.equal(view.mine?.valueLabel, '联盟赛季分')
})

test('个人榜里我自己那一行会被标出来，且是按 id 比不是按名字比', () => {
  const view = buildRankBoard(list(), 'POWER', 'P-2')
  assert.deepEqual(view.rows.map(r => r.mine), [false, true])
  // 同名不同 id 的人不该被误标
  const sameName = buildRankBoard(
    list({ entries: [entry(1, 'P-9', '小李', 100)] }), 'POWER', 'P-2')
  assert.equal(sameName.rows[0]?.mine, false, '名字一样不代表是我')
})

test('空榜与未加载：各有各的说明，且空榜会告诉玩家这个榜记的是什么', () => {
  const empty = buildRankBoard(list({ type: 'KILL', entries: [], myRank: null, myValue: null }), 'KILL', 'P-1')
  assert.match(empty.emptyText ?? '', /这个榜还没有人/)
  assert.match(empty.emptyText ?? '', /本赛季累计击杀/)
  const loading = buildRankBoard(null, 'KILL', 'P-1')
  assert.equal(loading.emptyText, '正在载入…')
  assert.equal(loading.rows.length, 0)
  assert.deepEqual(loading.tabs.map(t => t.active),
    [false, false, true, false, false, false, false], '七个页签里只有击杀榜高亮')
})

test('明细页签不画榜：那是 /player/power 的地盘，串台会让玩家以为榜值是自己的战力明细', () => {
  const detail = buildRankBoard(list(), 'DETAIL', 'P-2')
  assert.equal(detail.activeKey, 'DETAIL')
  assert.equal(detail.rows.length, 0, '明细页一行榜行都不画')
  assert.equal(detail.mine, null)
  assert.equal(detail.emptyText, null, '明细页不该显示这个榜还没有人')
  assert.equal(detail.noticeText, null)
})

test('响应与页签不是同一张榜时按载入中处理：绝不在 KILL 页签下画 POWER 的行', () => {
  // 切页签的那一刻手里还是上一张榜的响应 —— 这段数据长得和一页 KILL 一模一样，
  // 画出来玩家看不出自己看错了榜（而页签高亮与说明文字都在说 KILL）
  const stale = buildRankBoard(list({ type: 'POWER' }), 'KILL', 'P-1')
  assert.equal(stale.rows.length, 0)
  assert.equal(stale.emptyText, '正在载入…')
  assert.equal(stale.mine, null, '上一张榜的我的名次也不许带过来')
  assert.deepEqual(stale.tabs.map(t => t.active),
    [false, false, true, false, false, false, false], '串台时高亮也跟着 active 走，而不是留在上一张榜')
})

test('页签顺序固定（明细 + 五张榜 + 赛季），标签与量纲一一对应 —— 顺序变了玩家的肌肉记忆就废了', () => {
  assert.deepEqual(RANK_TABS.map(t => t.key),
    ['DETAIL', 'POWER', 'KILL', 'WAR', 'ALLIANCE', 'NATION', 'SEASON'])
  assert.equal(isBoardTab('DETAIL'), false)
  assert.equal(isBoardTab('SEASON'), false, '赛季页不是一张榜：它由 /season/status 供数')
  assert.equal(isBoardTab('KILL'), true)
  assert.equal(isBoardTab('WAR'), true, '国战榜是一张榜：它不许被当成赛季页那种非榜页签')
  const labels = (['POWER', 'KILL', 'WAR', 'ALLIANCE', 'NATION'] as const).map(k => valueLabelOf(k))
  assert.deepEqual(labels, ['匹配战力', '赛季击杀', '国战赛季分', '联盟赛季分', '国家赛季分'])
  assert.equal(new Set(labels).size, labels.length, '五个量纲不许共用同一个标签')
  assert.match(boardHintOf('POWER'), /匹配战力/)
  assert.match(boardHintOf('KILL'), /击杀/)
  // 国战榜不是实时累计的榜（一场仗打完那一刻才发一次），提示里少了这一句，空榜会被读成"功能没生效"
  assert.match(boardHintOf('WAR'), /一场仗打完那一刻/)
  assert.throws(() => valueLabelOf('NOPE' as never), /不认识的榜类型/)
})

test('国战榜那一页屏上不出现裸枚举原文（红线：WAR 是内部标识，玩家读的只有中文）', () => {
  const war = buildRankBoard(list({ type: 'WAR', entries: [], myRank: null, myValue: null }),
    'WAR', 'P-1')
  // 空榜那句是这一页唯一会印出来的长文案，所以直接量它，而不是量"拼起来的字符串里有没有中文"
  assert.match(war.emptyText ?? '', /国战/)
  assert.match(war.emptyText ?? '', /一场仗打完那一刻/,
    '少这一句，刚宣完战就点开的人会把它读成"这功能没生效"')
  const onScreen = [
    ...war.tabs.map(t => t.label),
    war.emptyText ?? '',
    war.mine?.valueLabel ?? '',
  ].join('|')
  assert.equal(/(^|[^A-Za-z])WAR([^A-Za-z]|$)/.test(onScreen), false,
    `榜页上出现了内部标识原文：${onScreen}`)
})

test('国战榜是**玩家榜**：未上榜那句不许提联盟/国家，而我自己那一行必须被标出来', () => {
  // 这两条是 2026-10-06 真跑截图抓出来的那一族：`personal` 原先写成 POWER/KILL 白名单，
  // 第五张榜静默掉到组织那一支 —— 文案串台 + mine 高亮永远不亮，而机器读数全绿。
  const notRanked = buildRankBoard(list({ type: 'WAR', myRank: null, myValue: null }), 'WAR', 'P-1')
  assert.equal(notRanked.notRankedText?.includes('联盟'), false,
    `国战榜不是联盟/国家的账，那句提示串台了：${notRanked.notRankedText}`)
  assert.equal(notRanked.notRankedText?.includes('国家'), false,
    `同上：${notRanked.notRankedText}`)
  assert.match(notRanked.notRankedText ?? '', /你还没有上榜/)
  const ranked = buildRankBoard(list({ type: 'WAR' }), 'WAR', 'P-2')
  assert.equal(ranked.rows.find(r => r.mine)?.rank, 2,
    '我自己那一行要标出来：personal 判错时这一格永远不亮，而榜上其它行看着完全正常')
  assert.equal(isPersonalBoard('WAR'), true)
  assert.equal(isPersonalBoard('NATION'), false)
  assert.throws(() => isPersonalBoard('NOPE' as never), /不认识的榜类型/,
    '加一张榜必须在这里登记一次，不许静默归到某一支')
})

test('赛季页签不画榜：手里那张榜的响应绝不会漏到赛季页签下面', () => {
  const season = buildRankBoard(list({ type: 'POWER' }), 'SEASON', 'P-1')
  assert.equal(season.rows.length, 0)
  assert.equal(season.mine, null, '别把上一张榜的"我的名次"带到赛季页')
  assert.equal(season.emptyText, null, '赛季页的正文由赛季视图画，榜这边连说明都不留')
  // 六个页签变七个（V18 的国战榜）：这一条数的是"页签总数"，加榜时必须跟着改 —— 它拦的是
  // "改了 RANK_TABS 却忘了回写这两行下标"（tabs[6] 写成 tabs[5] 的话，页签少一个也照样绿）
  assert.equal(season.tabs.length, 7)
  assert.equal(season.tabs[6]?.key, 'SEASON')
  assert.equal(season.tabs[6]?.active, true)
})

test('拉榜失败时那一行提示由编排层给，原样透传（客户端不重写服务端的理由）', () => {
  const failed = buildRankBoard(null, 'KILL', 'P-1', '请求太频繁，稍后再试')
  assert.equal(failed.noticeText, '请求太频繁，稍后再试')
  const ok = buildRankBoard(list({ type: 'KILL' }), 'KILL', 'P-1')
  assert.equal(ok.noticeText, null, '正常时这一行不占地方')
})

test('快照那一块：日期键排成人读的形状，名次为 null 时说的是"那天你不在榜上"', () => {
  const view = buildRankSnapshotView({
    type: 'POWER', dayKey: '20260919', snapshotAt: 1_789_000_000_000,
    myRank: 7, myValue: 12345,
  })
  assert.equal(view.dayText, '2026-09-19')
  assert.match(view.snapshotText, /^快照时刻：20/)
  assert.equal(view.rankText, '那天你排第 7 名')
  assert.equal(view.valueText, '12,345')
  const missing = buildRankSnapshotView({
    type: 'POWER', dayKey: '20260919', snapshotAt: 1_789_000_000_000,
    myRank: null, myValue: null,
  })
  assert.match(missing.rankText, /那天你不在榜上/)
  assert.equal(missing.valueText, '', '没有值就不画一个 0')
})

test('快照那一块会跟着榜视图一起下发（2026-09-22 接上入口之前它只是"有口没读"）', () => {
  const snapshot = buildRankSnapshotView({
    type: 'POWER', dayKey: '20260922', snapshotAt: 1_790_000_000_000, myRank: null, myValue: null,
  })
  // 默认不带（既有调用者不受影响）；传了就要原样带上，不能在合成视图时丢掉
  assert.equal(buildRankBoard(list(), 'POWER', 'P-2').snapshot, null)
  const withSnapshot = buildRankBoard(list(), 'POWER', 'P-2', null, snapshot)
  assert.equal(withSnapshot.snapshot?.dayText, '2026-09-22')
  assert.match(withSnapshot.snapshot?.rankText ?? '', /那天你不在榜上/)
  // 空榜（拉不到榜）那条分支也要带上，否则"榜空但快照有"时那一块会莫名消失
  const blank = buildRankBoard(null, 'POWER', 'P-2', '限流了', snapshot)
  assert.equal(blank.snapshot?.dayText, '2026-09-22')
})
