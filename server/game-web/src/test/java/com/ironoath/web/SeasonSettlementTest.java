package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SeasonSettleReq;
import com.ironoath.web.dto.generated.SeasonSettleResp;
import com.ironoath.web.season.SeasonLedgerStore;
import com.ironoath.web.store.memory.InMemorySeasonLedger;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.season.SeasonSettlementService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：B14 §5 结算与 §4 归档的硬验收 —— 幂等、阶段门、按快照、赛季数据不混入主存档。
 * 依赖：Spring Boot Test（真实存档与发放器），但<b>不动上下文里那份 ConfigRegistry</b>。
 *
 * <p><b>为什么每个用例自己 new 一份 {@link SeasonLedgerStore} 与 registry</b>：结算器是有状态的
 * （实时榜、已结算表、归档表都挂在 {@code SeasonSettlement} 上）。共用一份的话，
 * 「重复触发只发一次」这条断言其实吃到的是<b>上一个用例</b>留下的幂等状态 —— 看着绿，什么都没测。
 *
 * <p><b>为什么不在 test profile 里配 SEASON_START_AT</b>：锚点是全服状态，配了「今天开季」
 * 会让同一上下文里所有攻击类用例都落进备战期被禁战拒掉。所以只在需要它的用例里单独装配。
 */
@SpringBootTest
@ActiveProfiles("test")
class SeasonSettlementTest {

    /** 结算里会触发战令的赛季末补发（B24）：这里的用例不关心补发，但构造要真的传进去 —— 传 null 会让"结算顺手补发"这条路径在测试里被静默跳过。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ironoath.web.battlepass.BattlePassService battlePass;

    private static final long DAY = 86_400_000L;
    private static final String SEASON_ID = "season_01";

    @Autowired private TimeService timeService;
    @Autowired private PlayerRepository players;
    @Autowired private RewardService rewardService;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private PlayerInitService playerInitService;
    /** Bot 合规红线的唯一权威（B11 §七 2：Bot 不占前 N 名奖励坑位）。 */
    @Autowired private com.ironoath.web.bot.BotRegistry bots;

    private SeasonLedgerStore ledger;
    /** 榜与快照的存储。与账本同一条纪律：每个用例自己一份，否则「换个实例」的用例吃到的是别人的榜。 */
    private com.ironoath.web.season.SeasonBoardStore boards;

    @BeforeEach
    void reset() {
        ((InMemoryPlayerStore) players).clear();
        // 注册表是进程内共享的（Spring 上下文），不清的话本类注册的 Bot 会飘到别的用例里 ——
        // 那条「世界上真的有 Bot 了」的孵化用例会数到它们
        bots.clear();
        // 幂等存储不清：本类的 requestId 都带 UUID，天然不会与历史条目相撞
        ledger = new InMemorySeasonLedger();
        boards = new com.ironoath.web.store.memory.InMemorySeasonBoardStore();
    }

    @Test
    @DisplayName("验收2：结算重复触发三次，奖励只发一次、钱只进一次")
    void repeatedSettlePaysOnce() {
        String leader = player("赛季甲");
        String mate = player("赛季乙");
        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(leader, "赛季甲", 10_000L);
        svc.report(mate, "赛季乙", 5_000L);
        long goldAfterFirstRound = -1L;

        SeasonSettleResp once = svc.settle(new SeasonSettleReq(req("once"), null));
        assertThat(once.settledPlayers()).as("两个人都在榜上 ⇒ 都是首次结算").isEqualTo(2);
        assertThat(once.distributedGold()).isPositive();
        assertThat(once.seasonId()).isEqualTo(SEASON_ID);
        goldAfterFirstRound = goldOf(leader);
        assertThat(goldAfterFirstRound).as("金币真的进了钱包，不是只回了个数字")
                .isGreaterThan(newPlayerGold());

        long afterSecond = svc.settle(new SeasonSettleReq(req("twice"), null)).settledPlayers();
        long goldAfterSecond = goldOf(leader);
        long afterThird = svc.settle(new SeasonSettleReq(req("thrice"), null)).settledPlayers();

        assertThat(afterSecond).as("幂等：第二次没有新结算的人").isZero();
        assertThat(afterThird).isZero();
        assertThat(goldAfterSecond)
                .as("重复触发不得二次入账 —— 这条才是「只发一次」的可信证据，前面那个 0 只是计数")
                .isEqualTo(goldAfterFirstRound);
        assertThat(goldOf(leader)).isEqualTo(goldAfterFirstRound);
        assertThat(ledger.seasonRecords(SEASON_ID)).as("账本仍然只有那两条").hasSize(2);
    }

    /**
     * 这一条打的是上面那条打不到的地方：换一个服务实例 = 重启，于是
     * {@code SeasonSettlement} 里那张进程内 {@code settled} 表也空了，幂等只剩账本。
     *
     * <p>用例里仍然重新 report 了一次，对应现实里"重启后玩家上线、战力重算再次上报"；
     * 但自从榜与快照落库（收口清单 #18），即使没人重报，榜也不会是空的 ——
     * 见 {@link #coldBoardSettleAfterRestartStillAwardsTheRightRanks()}
     * 那一条（它专门验"没有重报"的路径）。
     */
    @Test
    @DisplayName("重启后重跑结算：进程内幂等表空了，账本必须把这笔钱挡住")
    void settleAfterARestartDoesNotPayTwice() {
        String leader = player("复活甲");
        SeasonSettlementService before = serviceAtDay(43);
        before.report(leader, "复活甲", 10_000L);
        assertThat(before.settle(new SeasonSettleReq(req("pre-restart"), null)).settledPlayers())
                .as("前置条件：第一轮确实结算了一个人").isEqualTo(1);
        long paid = goldOf(leader);
        assertThat(paid).as("第一轮真的发到了钱（否则下面那条断言恒真）").isGreaterThan(newPlayerGold());

        SeasonSettlementService afterRestart = serviceAtDay(43);
        afterRestart.report(leader, "复活甲", 10_000L);
        SeasonSettleResp second = afterRestart.settle(new SeasonSettleReq(req("post-restart"), null));

        assertThat(second.settledPlayers()).as("重启后重跑不得再结算出任何人").isZero();
        assertThat(goldOf(leader)).as("金币没有二次入账 —— 发出去是收不回来的").isEqualTo(paid);
        assertThat(ledger.seasonRecords(SEASON_ID)).as("账本仍然只有那一条").hasSize(1);
    }

    @Test
    @DisplayName("可重放：重启后再结算用库里那份快照（同一时刻），且不重复发钱")
    void settleIsReplayableAcrossRestart() {
        String leader = player("可重放甲");
        SeasonSettlementService before = serviceAtDay(43);
        before.report(leader, "可重放甲", 10_000L);
        SeasonSettleResp first = before.settle(new SeasonSettleReq(req("replay-1"), null));
        long paid = goldOf(leader);

        // 重启之后**不重报**：若快照没落库，这里会重拍一张（snapshotAt 变成新的时刻）
        SeasonSettlementService afterRestart = serviceAtDay(43);
        SeasonSettleResp second = afterRestart.settle(new SeasonSettleReq(req("replay-2"), null));

        assertThat(second.snapshotAt())
                .as("重放必须用同一份快照 —— 重拍会让申诉依据有两个版本")
                .isEqualTo(first.snapshotAt());
        assertThat(second.settledPlayers()).as("账本挡住重复发钱").isZero();
        assertThat(goldOf(leader)).as("钱只进了一次").isEqualTo(paid);
        assertThat(ledger.seasonRecords(SEASON_ID)).as("账本仍然只有那一条").hasSize(1);
    }

    @Test
    @DisplayName("重启后在冷榜上结算：名次必须据落库的榜算（否则算错的名次会被账本永久固化）")
    void coldBoardSettleAfterRestartStillAwardsTheRightRanks() {
        String leader = player("榜首");
        String mate = player("次席");
        SeasonSettlementService before = serviceAtDay(43);
        before.report(leader, "榜首", 10_000L);
        before.report(mate, "次席", 5_000L);

        // 重启，且**没有人重新上报** —— 这正是原缺口：榜在内存里就没了，于是结算出「谁都没名次」，
        // 而账本会把这次发奖记成"已经付过"，本该拿奖的人从此拿不到
        SeasonSettlementService afterRestart = serviceAtDay(43);
        SeasonSettleResp resp = afterRestart.settle(new SeasonSettleReq(req("cold-board"), null));

        assertThat(resp.settledPlayers()).as("榜还在 ⇒ 两个人都在结算范围内").isEqualTo(2);
        SeasonLedgerStore.Record top = ledger.find(SEASON_ID, leader);
        SeasonLedgerStore.Record second = ledger.find(SEASON_ID, mate);
        assertThat(top).as("榜首必须有账本记录").isNotNull();
        assertThat(second).isNotNull();
        assertThat(top.rank()).as("冷榜结算时这里是 0（谁都没名次）").isEqualTo(1);
        assertThat(second.rank()).isEqualTo(2);
        assertThat(top.seasonCoin()).as("第 1 名的赛季币必须高于第 2 名：名次决定发多少")
                .isGreaterThan(second.seasonCoin());
    }

    @Test
    @DisplayName("验收4（服务层版）：库里已有快照时一律按它算 —— 最后一秒刷分的人挤不进名次")
    void settleUsesTheFrozenSnapshotNotTheLiveBoard() {
        String early = player("从头打到尾");
        String late = player("最后一秒刷分");
        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(early, "从头打到尾", 10_000L);
        svc.report(late, "最后一秒刷分", 1_000L);

        // 库里先冻一份快照：那一刻 early 第 1、late 第 2（模拟"结算期一到就拍了快照"，
        // 或者另一个实例先拍下了 —— 两种情况在服务层是同一条路径：库里那份说了算）
        assertThat(boards.saveSnapshotIfAbsent(SEASON_ID, new SeasonSettlement.Snapshot(
                SeasonSettlement.Board.POWER, 111L,
                java.util.List.of(
                        new SeasonSettlement.Entry(early, "从头打到尾", 10_000L),
                        new SeasonSettlement.Entry(late, "最后一秒刷分", 1_000L)))))
                .as("前置：快照冻在「early 领先」的那一刻").isTrue();
        // 最后一秒刷分：攒了一整季的资源在这一刻全换成战力，实时榜上反超榜首
        svc.report(late, "最后一秒刷分", 999_999L);

        SeasonSettlementService afterRestart = serviceAtDay(43);   // 换实例：走"库里那份"的路径
        afterRestart.settle(new SeasonSettleReq(req("frozen"), null));

        assertThat(ledger.find(SEASON_ID, early).rank()).as("从头打到尾的人仍是第 1 名").isEqualTo(1);
        assertThat(ledger.find(SEASON_ID, late).rank()).as("最后一秒刷分改了实时榜，但改不了快照")
                .isEqualTo(2);
        assertThat(ledger.find(SEASON_ID, early).seasonCoin())
                .as("名次没被刷分挤掉 ⇒ 奖励也没被挤掉").isGreaterThan(ledger.find(SEASON_ID, late).seasonCoin());
    }

    @Test
    @DisplayName("B14 §4：结算把荣耀三件套抄回主存档；缓存落后时读一次就对上账")
    void settlementWritesGloryCacheAndTheReadPathHealsIt() {
        String leader = player("荣耀王");
        SeasonSettlementService before = serviceAtDay(43);
        before.report(leader, "荣耀王", 10_000L);
        assertThat(before.settle(new SeasonSettleReq(req("glory-settle"), null)).settledPlayers())
                .as("前置：这一季确实结算了一个人").isEqualTo(1);

        PlayerSave settled = players.findByPlayerId(leader).orElseThrow();
        assertThat(settled.glory().gloryLevel()).as("结算过一季，荣耀等级就是 1").isEqualTo(1);
        assertThat(settled.glory().highestTier())
                .as("主存档那一份必须等于账本派生的段位（同一个写者，不留两套口径）")
                .isEqualTo(ledger.gloryOf(leader).highestTier());
        assertThat(settled.glory().badges()).containsExactly(SEASON_ID);

        // 模拟「上一次写回撞了乐观锁被跳过」，或者这就是个从没有过缓存的老号
        PlayerSave wiped = players.findByPlayerId(leader).orElseThrow();
        wiped.setGlory(com.ironoath.core.player.PlayerGlory.empty());
        players.save(wiped);
        assertThat(players.findByPlayerId(leader).orElseThrow().glory().isBlank())
                .as("前置：缓存确实被抹掉了").isTrue();

        assertThat(before.gloryOf(leader).gloryLevel())
                .as("读一次就以账本为准对上账").isEqualTo(1);
        assertThat(players.findByPlayerId(leader).orElseThrow().glory().gloryLevel())
                .as("自愈不能只改返回值 —— 缓存得真的落回去，否则每次读都要重算一遍")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同一 requestId 重复提交被幂等键挡下，不会跑到发奖那一步")
    void sameRequestIdIsRejected() {
        String leader = player("赛季甲");
        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(leader, "赛季甲", 10_000L);
        String requestId = req("same");
        long goldAfterFirst = 0L;

        svc.settle(new SeasonSettleReq(requestId, null));
        goldAfterFirst = goldOf(leader);

        assertThatThrownBy(() -> svc.settle(new SeasonSettleReq(requestId, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(goldOf(leader)).as("被挡下的那一次不能已经发过钱").isEqualTo(goldAfterFirst);
    }

    @Test
    @DisplayName("没到结算期就结算被拒：提前发奖收不回来，比漏发更难收拾")
    void settleBeforeTheSettlePhaseIsRejected() {
        String leader = player("赛季甲");
        SeasonSettlementService svc = serviceAtDay(1);   // 第 2 天 = 备战期
        svc.report(leader, "赛季甲", 10_000L);
        long before = goldOf(leader);

        assertThatThrownBy(() -> svc.settle(new SeasonSettleReq(req("too-early"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结算期")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(goldOf(leader)).as("被拒时一分钱都不该动").isEqualTo(before);
    }

    @Test
    @DisplayName("验收4：结算只认快照 —— 快照拍下之后再刷分也改不了已定的名次")
    void settleUsesTheFrozenSnapshot() {
        String leader = player("赛季甲");
        String chaser = player("赛季乙");
        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(leader, "赛季甲", 10_000L);

        SeasonSettleResp once = svc.settle(new SeasonSettleReq(req("first-go"), null));

        // 快照之后，一个原本不在榜的人刷到全服最高分 —— 这一季他必须仍然没有名次
        svc.report(chaser, "赛季乙", 999_999L);
        svc.report(leader, "赛季甲", 999_999L);
        SeasonSettleResp again = svc.settle(new SeasonSettleReq(req("second-go"), null));

        assertThat(again.snapshotAt())
                .as("第二次结算依据的还是第一次那份快照，不是最新实时榜")
                .isEqualTo(once.snapshotAt());
        assertThat(ledger.seasonRecords(SEASON_ID).get(leader).rank())
                .as("快照里他是唯一条目 ⇒ 第 1 名，事后刷分不追溯").isEqualTo(1);
        SeasonLedgerStore.Record late = ledger.seasonRecords(SEASON_ID).get(chaser);
        assertThat(late).as("快照之后才上榜的人也会被结算（幂等记账需要他）").isNotNull();
        assertThat(late.rank())
                .as("但他不在快照里 ⇒ 名次 0；他刷到 999999 也没用，这就是「按快照」的硬含义")
                .isZero();
        assertThat(late.seasonCoin() + late.gold())
                .as("没名次就没奖励，不会因为事后上榜而分到钱").isZero();
    }

    @Test
    @DisplayName("验收3/§4：赛季数据在独立账本里，玩家主存档不因此多出一个资源种类")
    void seasonDataStaysOutOfThePlayerSave() {
        String leader = player("赛季甲");
        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(leader, "赛季甲", 10_000L);

        svc.settle(new SeasonSettleReq(req("settle-1"), null));

        SeasonLedgerStore.Record record = ledger.seasonRecords(SEASON_ID).get(leader);
        assertThat(record).as("名次 / 段位 / 赛季币都在赛季账本里").isNotNull();
        assertThat(record.seasonCoin()).as("赛季币记在账本，不进玩家资源表").isPositive();
        assertThat(ledger.seasonCoinBalance(SEASON_ID, leader)).isEqualTo(record.seasonCoin());
        assertThat(ledger.gloryOf(leader).gloryLevel())
                .as("主存档该留的三项之一：荣耀等级 = 参与过的赛季数").isEqualTo(1);
        assertThat(ledger.gloryOf(leader).highestTier()).isEqualTo(record.tier());

        PlayerSave save = players.findByPlayerId(leader).orElseThrow();
        assertThat(save.resources().keySet())
                .as("结算只动了已有资源（金币），没有往主存档里塞 SEASON_COIN 这种新字段")
                .doesNotContain("SEASON_COIN")
                .contains("GOLD");
    }

    // ---------- Bot 合规红线（B11 §七 / B13 §四 / B16 上线清单 §七 2） ----------

    /**
     * 这一条是 B13 验收 4「随机检查榜单前 3 名全为真人」那个脚本的等价物。
     *
     * <p>夹具刻意做成最坏情况：<b>Bot 的战力插在真人之间</b>（这样它一定会挤占名次，
     * 而不是排在末尾看不出来）。奖励线临时挪到第 3 名（覆写 {@code SEASON_REWARDED_TOP_N}），
     * 于是"坑位让回给人"有可判定的边界：第 3 名那位真人必须拿到名次 3 与对应的赛季币，
     * 而不是被 Bot 推到第 4 名颗粒无收。
     */
    @Test
    @DisplayName("B16 §七 2 / 验收4：Bot 不进赛季榜 —— 前 3 名全为真人，奖励坑位让回给人")
    void botsNeverOccupyRewardSlotsOnTheSeasonBoard() {
        String h1 = player("真人一");
        String h2 = player("真人二");
        String h3 = player("真人三");
        String h4 = player("真人四");
        String h5 = player("真人五");
        String botHigh = "bot-redline-high";
        String botMid = "bot-redline-mid";
        String botLow = "bot-redline-low";
        bots.register(botProfile(botHigh));
        bots.register(botProfile(botMid));
        bots.register(botProfile(botLow));

        SeasonSettlementService svc = serviceAtDayWithTopN(43, 3);
        svc.report(h1, "真人一", 1_000L);
        svc.report(h2, "真人二", 900L);
        svc.report(h3, "真人三", 800L);
        svc.report(h4, "真人四", 700L);
        svc.report(h5, "真人五", 600L);
        // Bot 的战力刻意插在真人之间（950 会排第 2、850 会排第 4、650 会排第 6）
        svc.report(botHigh, "不该出现的名字", 950L);
        svc.report(botMid, "不该出现的名字", 850L);
        svc.report(botLow, "不该出现的名字", 650L);

        assertThat(svc.liveRank(botHigh)).as("Bot 上报不进榜 ⇒ 名次恒为 0").isZero();
        assertThat(svc.liveRank(botMid)).isZero();
        assertThat(svc.liveRank(botLow)).isZero();

        SeasonSettleResp resp = svc.settle(new SeasonSettleReq(req("bots"), null));

        assertThat(resp.settledPlayers()).as("榜上只有 5 个真人，Bot 不计入结算人数").isEqualTo(5);
        var records = ledger.seasonRecords(SEASON_ID);
        assertThat(records.keySet()).as("账本里任何一条记录都不能是 Bot").doesNotContain(botHigh, botMid, botLow);
        assertThat(records.get(h1).rank()).isEqualTo(1);
        assertThat(records.get(h2).rank()).isEqualTo(2);
        assertThat(records.get(h3).rank())
                .as("被 Bot 插队的话他会掉到第 4 名 —— 这条断言钉住的是「坑位让回给人」")
                .isEqualTo(3);
        assertThat(records.get(h3).seasonCoin()).as("第 3 名（奖励线内）必须拿到赛季币").isPositive();
        assertThat(records.get(h4).rank()).isEqualTo(4);
        assertThat(records.get(h4).seasonCoin()).as("奖励线外的第 4 名照旧没有奖励").isZero();
    }

    /**
     * 读侧兜底：写入侧（{@code report}）已经拦掉 Bot，但库里可能存着这条规则生效之前
     * 写进去的条目 —— 不摘掉的话，一次重启就能把 Bot 重新装回名次。
     */
    @Test
    @DisplayName("读侧兜底：库里存量的 Bot 条目在恢复榜时被摘掉（重启不该把 Bot 装回名次）")
    void storedBotEntriesAreDroppedWhenTheBoardIsRestored() {
        String human = player("真人甲");
        String staleBot = "bot-stale-entry";
        bots.register(botProfile(staleBot));

        SeasonSettlementService svc = serviceAtDay(43);
        svc.report(human, "真人甲", 1_000L);
        // 绕过 report 直接写存储，模拟"规则生效之前写进库里的条目"
        boards.report(SEASON_ID, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(staleBot, "存量Bot", 9_999L));

        // 换一个实例 = 重启：新实例从存储恢复榜
        SeasonSettlementService restarted = serviceAtDay(43);
        assertThat(restarted.liveRank(staleBot)).as("恢复时被摘掉，而不是装回第 1 名").isZero();
        assertThat(restarted.liveRank(human)).as("真人因此前进一名，而不是被存量 Bot 压在下面").isEqualTo(1);
    }

    @Test
    @DisplayName("C17：结算会把超出保留期的旧档真删掉，而保留期内的一季与当前季都不许碰")
    void settlePurgesArchivesBeyondRetention() {
        // 当前配置下的赛季就是 season_01（season.json 的第一行），真实数据里还不存在比它更老的季，
        // 所以"已经攒了 4 季存档"这个前提只能用合成季号构造。清理判定只看季号先后，不看号是谁发的。
        for (String stale : List.of("aa", "ab", "ac")) {
            assertThat(ledger.recordIfAbsent(stale, new SeasonLedgerStore.Record(
                    stale + "-p", 1, SeasonTier.Tier.GOLD, 10L, 100L))).as("先记上 %s", stale).isTrue();
            boards.report(stale, SeasonSettlement.Board.POWER,
                    new SeasonSettlement.Entry(stale + "-p", "旧季第一", 9_999L));
        }

        String human = player("现役第一");
        boards.report(SEASON_ID, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(human, "现役第一", 12_345L));

        serviceAtDay(43).settle(new SeasonSettleReq(req("purge"), null));

        // 保留 3 个赛季 = 当前季 + 更老的里面最新的 2 个（ac、ab）；aa 已经出窗
        assertThat(ledger.seasonIds()).as("出窗的旧季从账本里删掉").doesNotContain("aa");
        assertThat(ledger.seasonIds()).as("保留期内的两季原样在").contains("ab", "ac");
        assertThat(boards.board("aa", SeasonSettlement.Board.POWER))
                .as("榜也要一起删，只删账本等于旧季一半还在库里").isEmpty();
        assertThat(boards.board("ab", SeasonSettlement.Board.POWER))
                .as("不许顺手把还在保留期内的季删掉").isNotEmpty();
        assertThat(boards.board(SEASON_ID, SeasonSettlement.Board.POWER))
                .as("正在结算的这一季永远不进删除候选").isNotEmpty();
        assertThat(ledger.find(SEASON_ID, human))
                .as("当前季的结算记录更不能被自己刚写的清理删掉").isNotNull();
    }

    // ---------- 夹具 ----------

    /** 把赛季锚点摆在「N 天前」，于是「现在」正好落在赛季第 N+1 天。 */
    private SeasonSettlementService serviceAtDay(long day) {
        return serviceAtDay(day, null);
    }

    /** 同上，但可以顺手覆写奖励线（{@code SEASON_REWARDED_TOP_N}），让奖励边界可判定。 */
    private SeasonSettlementService serviceAtDayWithTopN(long day, int rewardedTopN) {
        return serviceAtDay(day, rewardedTopN);
    }

    private SeasonSettlementService serviceAtDay(long day, Integer rewardedTopN) {
        ConfigRegistry reloaded = ConfigRegistry.loadFromDirectory(locateConfigDir());
        reloaded.reload("global", GlobalCfg.class,
                withSeasonStart(timeService.serverNow() - day * DAY, rewardedTopN));
        return new SeasonSettlementService(reloaded, timeService, new SeasonRulesAssembler(reloaded),
                players, rewardService, ledger, boards, idempotency, bots, battlePass);
    }

    /**
     * 一个最小可用的 Bot 画像。
     *
     * <p><b>为什么参数照抄 {@code NationEndpointTest} 的形状而不是各写一份</b>：这份画像
     * 只需要"是个合法的 Bot"（红线判定只看注册表里的身份），具体数值不影响任何断言，
     * 所以与那条用例保持同一组数字，将来画像结构变了只改一处能照出来。
     */
    private static com.ironoath.core.bot.BotProfile botProfile(String botId) {
        return new com.ironoath.core.bot.BotProfile(botId, "bot_linju",
                new com.ironoath.core.bot.BotProfile.AiProfile(
                        com.ironoath.common.num.FixedPoint.parse("0.50"),
                        com.ironoath.common.num.FixedPoint.parse("0.50"),
                        com.ironoath.common.num.FixedPoint.parse("0.50"),
                        com.ironoath.common.num.FixedPoint.parse("0.60")),
                new com.ironoath.core.bot.BotProfile.Persona(42L, 7L, 99L,
                        java.util.List.of(12, 13, 20, 21, 22), 3L, 30L,
                        com.ironoath.common.num.FixedPoint.parse("0.10")),
                com.ironoath.common.num.FixedPoint.parse("1.0"));
    }

    /**
     * 往 global 表追加一行 {@code SEASON_START_AT}（部署参数，平时不在表里）。
     *
     * @param rewardedTopN 非空则同时覆写 {@code SEASON_REWARDED_TOP_N} 的值为它 ——
     *                     奖励线只有挪到夹具的名次边界上，"坑位有没有让回给人"才可判定；
     *                     找不到那一行会抛（夹具静默不生效是最坏的一种测试）
     */
    private static String withSeasonStart(long seasonStartAt, Integer rewardedTopN) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(Files.readString(
                    locateConfigDir().resolve("global.json"), StandardCharsets.UTF_8));
            ArrayNode rows = (ArrayNode) table.get("rows");
            if (rewardedTopN != null) {
                boolean replaced = false;
                for (int i = 0; i < rows.size(); i++) {
                    ObjectNode existing = (ObjectNode) rows.get(i);
                    if ("SEASON_REWARDED_TOP_N".equals(existing.path("id").asText())) {
                        existing.put("value", rewardedTopN);
                        replaced = true;
                    }
                }
                if (!replaced) {
                    throw new IllegalStateException(
                            "global 表里没有 SEASON_REWARDED_TOP_N，覆写夹具失效");
                }
            }
            ObjectNode row = mapper.createObjectNode();
            row.put("id", "SEASON_START_AT");
            row.put("valueType", "LONG");
            row.put("value", seasonStartAt);
            row.put("unit", "毫秒时间戳");
            row.put("source", "B14 §一（部署参数，不进表）");
            row.put("why", "测试注入的赛季锚点");
            rows.add(row);
            return mapper.writeValueAsString(table);
        } catch (Exception e) {
            throw new IllegalStateException("无法构造带 SEASON_START_AT 的 global 表", e);
        }
    }

    private String player(String nickName) {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                nickName, 1_700_000_000_000L, "")).playerId();
    }

    private long goldOf(String playerId) {
        return players.findByPlayerId(playerId).map(save -> save.resources().get("GOLD"))
                .map(state -> state.current()).orElse(0L);
    }

    /** 新号的金币初始值：另开一个号来读，而不是把 resource 表的数字抄进测试。 */
    private long newPlayerGold() {
        return goldOf(player("金币基准"));
    }

    private static String req(String tag) {
        return "req-season-" + tag + "-" + UUID.randomUUID();
    }

    /** surefire 的 cwd 是被测模块目录，所以向上找仓库根。 */
    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }
}