// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * `GET /nation/war` 的响应（B21 §二 点名的 `WarStatusResp`）。
 *
 * **全服一份、谁都能读**：国战是全服事件而不是某一国的内部事务，所以这一格没有成员关系门槛 —— 与 `/nation/treasury` 恰好相反，那本账是公共资产（要防贪污，成员必须看得见），这本账是公共进度（不打国战的人也在为全服目标做贡献，B13 §7 的设计意图就在这里）。
 *
 * **诚实边界（读代码的人必须先知道）**：到切片 2c 为止这条链路有**三跳写入路径**——宣战建板（2a）、击杀累计（2b，挂在 `BattleReportService.record` 那个唯一漏斗上）、到期结算（2c，由本端点与宣战各自顺带推进，服务端不许常驻定时器）。**仍然没有执行者**的是疲劳累积（`addFatigue` 无生产调用点）与占领分／建筑分（关卡和王城不是地图上的可占领物，`beginSiege` 进不去，所以一块板从宣战起就停在 `PREPARATION`）。数据源那一半未通，所以 `验收矩阵.md` 的 B13 疲劳值上限与国家集结门槛两条**仍是 ⬜**，不因这个端点存在而变。本切片交付的是承载（存储端口 + 内存/Mongo 两套实现 + 装配点 + 读写口），不是「国战能玩了」。另：**胜者不发奖** —— B13/B21 都没写「赢了给什么」（`WAR_SERVER_GOAL_GOLD` 是另一件事：全服目标奖励，且领取端点也未做），那属产品口径、已登记待裁决。
 *
 * **归属说明**：B21 §二 写的是另建 `contract/proto/war.schema.json`；本切片按端点归属落在本文件（`GET /nation/war` 挂在 `/nation` 之下，与国策同域）。若后续把国战拆成独立控制器，这份 def 应随之下迁移，不要在两个文件里各留一份 —— `$defs` 名是跨文件的全局命名空间，`check-contract-defs.sh` 会盯住同名不同形。
 */
public record WarStatusResp(
        boolean hasWar,   // 当前有没有一场可看的国战（内存/库里存在至少一场）。 **false 时 `phase`、`startedAt`、`capitalHolder`、`capitalHolderName` 一并缺席**，而不是填 0 或空串 —— 一个事实只允许一种表示：再加一个 `phase="NONE"` 会让协议枚举与内核枚举分家（内核的 `Phase` 只有三段），而「有没有仗」于是变成两处可以各说各话的地方。
        WarPhase phase,   // 处在哪一段。`hasWar=false` 时为 null。
        Long startedAt,   // 这一场开战的时刻（服务端时间，不是客户端时钟）。`hasWar=false` 时为 null。
        long remainingSec,   // 王城战剩余秒数。**非 SIEGE 阶段恒为 0，绝不为负**（内核 `remainingSeconds` 已 clamp）—— 负数会让界面显示「-37 秒」，而玩家会以为战斗还在跑。
        int gateCount,   // 王城周边的关卡**总数**（`global.WAR_GATE_COUNT`）。上下界必须下发，否则客户端只能自己抄一份「4」，那是「客户端不抄配置表」红线；面板要写的「已占 2 / 共 4 座」两个数一个来自行、一个来自这里。
        String capitalHolder,   // 当前占着王城的国家 id；无人占领时为 null。**不得直接上屏**（同 `WarNationScoreView.nationId` 那条）。
        String capitalHolderName,   // 占领者的国名，服务端下发；无人占领或该国已解散时为 null（客户端给回退语，不许回落到裸 id）。
        List<WarNationScoreView> scores,   // 各参战方的积分行，顺序即内核登记顺序（结算与平分判定用的就是这一顺序）。空数组 = 还没有参战方登记进来。
        long totalKills,   // 全服累计击杀数，**含不打国战的人的贡献**（打野、打关卡都算）。这是 B13 §7「让非参战玩家也有参与感」的唯一落点。
        long serverGoalKills,   // 全服目标的击杀目标值（`global.WAR_SERVER_GOAL_KILLS`）。与 `totalKills` 一起下发才能画出进度条 —— 只给分子就是让客户端抄分母。
        boolean serverGoalReached,   // 全服目标是否已达成（`totalKills >= serverGoalKills`）。**判定在服务端**：客户端自己比大小会让两侧的取整与口径各有两份。达成后的领取动作不在本端点里（领取端点与「每人只领一次」的落点仍待下一切片，见收口清单 §七 的 B13 承载条目）。
        long myFatigue,   // 请求者本人的疲劳值（`X-Player-Id` 那位）。没有这一场可看时为 0。
        long fatigueMax,   // 疲劳上限（`global.WAR_FATIGUE_MAX`）。同样是「上下界必须下发」那一条：面板要写「12 / 100」。
        boolean canMarch,   // 本人还能不能行军（B13 验收 7）。**由服务端一处判定**（内核 `WarScoreBoard.canMarch`），客户端不许按 `myFatigue < fatigueMax` 再算一遍 —— 那两个式子今天等价，但行军闸门后面还要接外交、窗口、集结等条件，届时自己算的那份会留在原地。
        long serverNow,   // 服务端时间戳（铁律 5：客户端不许自己读本地时钟算剩余时间，否则改手机时间就能把仗打完）。
        boolean myGoalClaimed)   // **请求者本人**领过这一场的全服奖励没有（`X-Player-Id` 那位）。`hasWar=false` 时为 false。 **为什么必须下发这一位**：面板要能区分三种状态 —— 还没达成 / 可以领 / 已经领过了。只有 `serverGoalReached` 与「已领人数」两个读数时第三种画不出来，玩家会对着一个点了就被拒的键反复点，而那正是验收 10「每人只领一次」想避免的形状。 **判定在服务端**：领取名单在战事档里（`goalClaimed`），客户端本地记一个「我刚领过」在换设备/重登之后就假了。
{
}
