/**
 * 职责：社交面板（小队 / 联盟 / 互助 / 事件 / 聊天 / 集结）的读接口夹具，两份量具共用一份：
 *       `tools/verify-label-fit-runtime.mjs`（排版横扫）与 `tools/verify-plate-plant.mjs`（植入正例）。
 * 为什么只有一份：这批字段是**逐条对着 `contract/proto/social.schema.json` 的 required 配的**
 *       （那些定义全是 `additionalProperties: false`，多一个键和少一个键是同一类错）——
 *       抄第二份就会少抄一条，而症状是"那一屏安静地退回空态"，判据照绿（台账 #397/#420）。
 * 用法：`const social = socialFixtures()` → 在 `page.goto` **之前** `await social.install(stubRead)`；
 *       要「已入盟」那一屏时置 `social.state.joinedAlliance = true`（摘要 / 权限 / 申请列表三份桩跟着变）。
 */
export function socialFixtures() {
  /** 已入盟开关：`AppRoot` 见摘要里 alliance 非 null 才会去问 `/alliance/sync`。 */
  const state = { joinedAlliance: false }

  /**
   * 社交夹具。这一格与邮件/战报不同：**空态本来就有 11 颗 Label**（页签条 + 那一行「创建小队」），
   * 光数 Label 挡不住"桩掉线"，所以另配一条正向断言 `STUB_MARKS`（夹具里的队名必须真画出来）。
   * 摘要给一支 6 人小队（`SquadView.required` 那 14 个字段一个不少），联盟留 null ——
   * 非 null 会让 `AppRoot.refresh('social')` 追加一次 `/alliance/sync`，那是另一条链路，不属这一格。
   */
  const SQUAD_MEMBERS = [
    { name: '铁砧·玛尔达', role: 'LEADER', power: 128400, mainCityLevel: 18 },
    { name: '石锤·乌尔', role: 'MEMBER', power: 74200, mainCityLevel: 14 },
    { name: '灰隼·雷恩', role: 'MEMBER', power: 69850, mainCityLevel: 13 },
    { name: '柳岸·希达', role: 'MEMBER', power: 51200, mainCityLevel: 12 },
    { name: '守誓者贝尔', role: 'MEMBER', power: 47600, mainCityLevel: 11 },
    { name: '麦田·奥登', role: 'MEMBER', power: 33100, mainCityLevel: 9 },
  ].map((m, i) => ({ id: `probe_squad_p${i}`, lastActiveAt: Date.now() - i * 600_000, ...m }))
  // 事件三条：带坐标的（被打）、不带坐标的（收到互助）、过期的一条 ——
  // `SocialEventView.required` 那 8 个字段一个不少，`body` / `coord` / `relatedId` 允许 null 但**必须出现**
  const SOCIAL_EVENTS = [
    { eventId: 'probe_ev_0', type: 'MEMBER_ATTACKED', title: '石锤·乌尔的城被攻打',
      body: '守军损失 1 240， attacker 已撤退。', coord: { x: 118, y: 64 }, relatedId: 'probe_battle_0',
      occurredAt: Date.now() - 600_000, expired: false },
    { eventId: 'probe_ev_1', type: 'HELP_RECEIVED', title: '你的兵营加速已获 3 次互助',
      body: null, coord: null, relatedId: 'probe_help_0',
      occurredAt: Date.now() - 1_800_000, expired: false },
    { eventId: 'probe_ev_2', type: 'RALLY_INVITED', title: '集结邀请：西关（已过期）',
      body: '没有在你手上响应，队伍已经出发了。', coord: { x: 203, y: 88 }, relatedId: 'probe_rally_1',
      occurredAt: Date.now() - 7_200_000, expired: true },
  ]
  /**
   * 「已入盟」那一相的三份夹具。字段逐条对着 `contract/proto/social.schema.json` 里
   * `AllianceView` / `AllianceTechView` / `AllianceMember` / `AllianceSyncResp` /
   * `AllianceApplicationListResp` 的 `required`（这些定义全是 `additionalProperties: false`，
   * 多一个键和少一个键是同一类错）。
   *
   * <p>**开关为什么是"摘要里的 alliance 给不给 null"**：`AppRoot` 见 null 就不发 `/alliance/sync`
   * （没入盟时那一问是白问，服务端回 10010，客户端还会把它画成"稍后会自动重试"），
   * 而 `SocialPanel.buildAllianceSection` 判 joined 也只看了这一个字段。造第二套状态标志
   * 只会造出一个客户端根本不读的分支。
   */
  const ALLIANCE_NOW = Date.now()
  const ALLIANCE_TECHS = [
    {
      techId: 'probe_tech_logistics', level: 3, levelCap: 10, effectFixed: 4500,
      name: '辎重道', nextLevelCost: 2400, canResearch: true, reason: null,
    },
    {
      // 到顶的那一项：灰态原因那句由服务端给（客户端不自己比 level 与 levelCap）
      techId: 'probe_tech_marshal', level: 8, levelCap: 8, effectFixed: 12000,
      name: '点将台', nextLevelCost: 9600, canResearch: false, reason: '本盟已研究到当前联盟等级的上限',
    },
  ]
  const ALLIANCE_VIEW = {
    id: 'probe_alliance_1', name: '黑石渡口', tag: '黑石', leaderId: 'probe_p_leader',
    level: 7, exp: 18_400, memberCap: 80, memberCount: 41, fund: 12_800,
    techs: ALLIANCE_TECHS, territoryCount: 6, territoryCap: 12,
    myRole: 'LEADER', myContribution: 3240, myDonateToday: 3,
    // 三档今天都捐过了 ⇒ 捐献那三行一行都不摆（"档数用完了就不摆按钮"那条分支也要有一相量到）：
    // 省下来的两格正好让成员行挤进第一屏 —— 成员行是这一屏最宽的那一类（名字 · 职位 + 四段 detail）
    donateTiersUsed: [0, 1, 2], donateDailyCap: 3,
    announcement: '晚八点集结打西关，迟到的自己交粮。', version: 4, serverNow: ALLIANCE_NOW,
  }
  // 四个职位各来一个人：`allianceRoleText` 那条映射只有全走一遍才量得到最长的那句。
  // **最长的那个排第一**：一屏只画得下六行，排在后面的成员行落在第二页，量不到就等于没量。
  const ALLIANCE_MEMBERS = [
    // 最后一行改用长名字：这一屏的行是"名字 · 职位"拼出来的，越界正好落在名字那一侧
    { id: 'probe_p_member', name: '断斧·罗德里戈·铁尾', power: 9100, role: 'MEMBER', contribution: 240, lastActiveAt: ALLIANCE_NOW - 1_800_000, squadId: null, squadName: null },
    { id: 'probe_p_leader', name: '铁砧·瓦拉', power: 48_200, role: 'LEADER', contribution: 9120, lastActiveAt: ALLIANCE_NOW - 60_000, squadId: null, squadName: null },
    { id: 'probe_p_officer', name: '灰隼·雷恩', power: 31_600, role: 'OFFICER', contribution: 6480, lastActiveAt: ALLIANCE_NOW - 5_400_000, squadId: 'probe_squad_1', squadName: '铁砧前哨' },
    { id: 'probe_p_elder', name: '石锤·乌尔', power: 27_400, role: 'ELDER', contribution: 5130, lastActiveAt: ALLIANCE_NOW - 432_000_000, squadId: null, squadName: null },
  ]
  // **只给一条**：一屏画得下六行内容，多一条申请就把成员行挤到第二页去了
  // （成员行是这一屏最宽的一类，量不到等于没量 —— 截图目视时发现的）
  const ALLIANCE_APPLICANTS = [
    { playerId: 'probe_p_apply_1', nickname: '铜锣·魏八', mainCityLevel: 9 },
  ]

  /** 挂桩必须在 `page.goto` 之前（深链进面板就发请求，晚挂等于这一格读到空态）。 */
  async function install(stubRead) {
    await stubRead('**/social/summary*', () => ({
      squad: {
        id: 'probe_squad_1', name: '铁砧前哨', leaderId: 'probe_squad_p0', members: SQUAD_MEMBERS,
        level: 6, exp: 1240, expToNext: 2000, memberCap: 10, shopLevel: 3, squadCoin: 4820,
        allianceId: null, isSubSquad: false, dailyQuestProgress: 3, dailyQuestTarget: 8,
        serverNow: Date.now(),
      },
      alliance: state.joinedAlliance ? ALLIANCE_VIEW : null, nationId: null,
      pendingInvites: 0, pendingHelps: 2, helpRemainingToday: 3,
      events: SOCIAL_EVENTS, serverNow: Date.now(),
    }))
    await stubRead('**/social/helpRequests*', {
      requests: [
        { requestId: 'probe_help_0', fromPlayerId: 'probe_squad_p1', fromPlayerName: '石锤·乌尔',
          kind: 'BUILDING', targetDesc: '兵营 Lv12 升级中', remainingSeconds: 1500, helpedCount: 2, alreadyHelped: false },
        { requestId: 'probe_help_1', fromPlayerId: 'probe_squad_p2', fromPlayerName: '灰隼·雷恩',
          kind: 'TRAINING', targetDesc: '重步兵 ×400', remainingSeconds: 600, helpedCount: 5, alreadyHelped: true },
      ],
      pendingHelps: 2, helpRemainingToday: 3, serverNow: Date.now(),
    })
    // 权限按 scope 各问一次：响应里的 `scope` 必须跟着请求走，写死一份等于让两页共用同一套权限位
    await stubRead('**/social/permissions*', (url) => ({
      scope: url.includes('ALLIANCE') ? 'ALLIANCE' : 'SQUAD',
      role: 'LEADER',
      // 已入盟那一相要把联盟侧的权限补齐（每一位都在服务端搜得到同名码，客户端只读这些结论）：
      // 缺哪一位，那一行按钮就整片置灰、"为什么不行"那句会盖上 detail —— 灰屏与亮屏是两屏不同的字。
      // 反过来也不能塞一个服务端没有的码（原来那份清单里的 `DISBAND` 就是：真码只有
      // `DISBAND_ALLIANCE` / `DISBAND_SQUAD`，客户端一处都不读裸的 `DISBAND`，留着是句假话）。
      permissions: url.includes('ALLIANCE') && state.joinedAlliance
        ? ['KICK_MEMBER', 'START_RALLY', 'DONATE', 'DISBAND_ALLIANCE',
          'APPROVE_APPLICATION', 'SET_ROLE', 'EXPAND_CAPACITY', 'RESEARCH_TECH', 'TRANSFER_LEADER']
        : ['KICK_MEMBER', 'START_RALLY', 'DONATE'],
      serverNow: Date.now(),
    }))
    // 成员列表只走 diff 通道（B10 验收 10）：摘要给了 alliance 之后，客户端会带着 version 来问一次。
    await stubRead('**/alliance/sync*', {
      version: 4, unchanged: false, changedMembers: ALLIANCE_MEMBERS, removedMemberIds: [],
      fund: ALLIANCE_VIEW.fund, level: ALLIANCE_VIEW.level, memberCount: ALLIANCE_VIEW.memberCount,
      announcement: ALLIANCE_VIEW.announcement, serverNow: ALLIANCE_NOW,
    })
    // 发现型列表与聊天：这一格不量它们（要点页签才画行），但桩住才不会让 dev 新号的真实空响应混进读数 ——
    // `/alliance/list` 对没入盟的号回 10010，客户端会把那句「稍后会自动重试」画到屏幕上，量具就读成了另一屏
    await stubRead('**/alliance/list*', { alliances: [], total: 0, limit: 20, serverNow: Date.now() })
    await stubRead('**/squad/list*', { squads: [], total: 0, limit: 20, serverNow: Date.now() })
    await stubRead('**/alliance/applications*', () => (state.joinedAlliance
      // 两条都下发（total 与条数相等 ⇒ 那句"只显示前 N 条"的说明不出现）：
      // 省下的那一格要给成员行挤进第一屏，见上面 `ALLIANCE_MEMBERS` 的排序理由
      ? { applicants: ALLIANCE_APPLICANTS, total: ALLIANCE_APPLICANTS.length, limit: 2, serverNow: Date.now() }
      : { applicants: [], total: 0, limit: 20, serverNow: Date.now() }))
    await stubRead('**/chat/list*', {
      messages: [
        { messageId: 'probe_chat_0', channel: 'SQUAD', senderId: 'probe_squad_p1',
          senderName: '石锤·乌尔', content: '集合点定在河谷渡口，我先过去了', sentAt: Date.now() - 240_000 },
        { messageId: 'probe_chat_1', channel: 'SQUAD', senderId: 'probe_squad_p0',
          senderName: '铁砧·玛尔达', content: '等你到整点，路上把侦察发一份过来', sentAt: Date.now() - 120_000 },
      ],
      hasMore: false, serverNow: Date.now(),
    })
    // 集结页签的数据也走 `refresh('rallies')` → `GET /rally/list`（`GameBootstrap` 把它转手给
    // `social.attachRallies`）。**三条全是 PREPARING**：服务端这个端点只回进行中的集结
    // （`RallyListResp` 文档原话「已出发或已取消的集结留在面板上没有意义」），
    // 给一条 DEPARTED 就会造出一个"产品缺陷"假象 —— 上一版正是这样量出「即将出发」配 DEPARTED，
    // 判真假读到服务端才结案（台账 #397）。三条各取 `remainTextOf` 的一个分支 + 一条满员。
    await stubRead('**/rally/list*', {
      rallies: [
        { rallyId: 'probe_rally_0', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p0',
          targetCoord: { x: 118, y: 64 }, targetType: 'MONSTER', maxMembers: 5, joinedCount: 3,
          totalTroops: 12400, prepareUntil: Date.now() + 20 * 60_000, departAt: Date.now() + 25 * 60_000,
          status: 'PREPARING', members: ['probe_squad_p0', 'probe_squad_p1', 'probe_squad_p2'],
          heroSlots: [], serverNow: Date.now() },
        // 不足一分钟那一支（「准备还剩 40 秒」）
        { rallyId: 'probe_rally_1', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p1',
          targetCoord: { x: 96, y: 141 }, targetType: 'RESOURCE', maxMembers: 4, joinedCount: 2,
          totalTroops: 28600, prepareUntil: Date.now() + 40_000, departAt: Date.now() + 45_000,
          status: 'PREPARING', members: ['probe_squad_p1', 'probe_squad_p2'],
          heroSlots: [], serverNow: Date.now() },
        // 已过准备时刻但服务端还没把它 tick 成 DEPARTED（「即将出发」）+ 满员（不给「加入」键）
        { rallyId: 'probe_rally_2', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p2',
          targetCoord: { x: 203, y: 88 }, targetType: 'PLAYER_CITY', maxMembers: 5, joinedCount: 5,
          totalTroops: 41200, prepareUntil: Date.now() - 60_000, departAt: Date.now() - 55_000,
          status: 'PREPARING', members: ['probe_squad_p1', 'probe_squad_p2', 'probe_squad_p3',
            'probe_squad_p4', 'probe_squad_p5'],
          heroSlots: [], serverNow: Date.now() },
      ],
      serverNow: Date.now(),
    })
  }

  return { state, install, members: ALLIANCE_MEMBERS }
}
