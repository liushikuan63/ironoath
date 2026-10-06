package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.web.dto.generated.WarCooldownView;
import com.ironoath.web.dto.generated.WarCooldownsResp;
import com.ironoath.web.dto.generated.WarDeclareReq;
import com.ironoath.web.dto.generated.WarGoalClaimReq;
import com.ironoath.web.dto.generated.WarGoalClaimResp;
import com.ironoath.web.dto.generated.WarNationScoreView;
import com.ironoath.web.dto.generated.WarPhase;
import com.ironoath.web.dto.generated.WarStatusResp;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.social.SocialRulesAssembler;

/**
 * 职责：国战状态的只读视图（B13 §一 §7、B21 §二 的 {@code WarStatusResp}）。
 * 依赖：{@link WarStore}、{@link NationStore}（取国名）、{@link WarRulesAssembler}、{@link TimeService}。
 *
 * <p><b>没有成员关系门槛，全服谁都能读</b>：国战是全服事件，不是某一国的内部事务 ——
 * 与 {@code /nation/treasury} 恰好相反，那本账是公共资产（要防贪污，成员必须看得见），
 * 这本账是公共进度（B13 §7 让不打国战的人也贡献击杀，读不到进度条那条设计就白写）。
 * 这里也不做权限位：{@code role_permission} 表里没有 VIEW_WAR 这一位，凭空加一道只会让人以为
 * 「普通玩家看不到国战进度」是设计意图。
 *
 * <p><b>本类是承载，不是玩法</b>：写侧今天通了半条 —— 开战（{@link #declare}）、击杀累计
 * （挂在 {@code BattleReportService.record} 那个唯一漏斗上）、到期结算（{@link #warStatus} 顺带推进，
 * 见 {@link WarStore#settleIfExpired}）三样都有执行者。仍<b>没有</b>执行者的是：疲劳累积（{@code addFatigue}
 * 无生产调用点）、占领分与建筑分（关卡和王城今天不是地图上的可占领物，{@code beginSiege} 进不去）。
 * 所以验收矩阵里 B13 的疲劳值上限与国家集结门槛两条继续挂 ⬜，<b>不因为这条读链路存在而变</b> ——
 * 把它当「国战通了」的证据是错的，这条边界在 {@code WarStatusResp} 的协议描述里也写了同一句。
 *
 * <p><b>数值一律现取，不缓存</b>：规则来自 {@link WarRulesAssembler}（每次装配一遍，热更立刻生效），
 * 剩余秒数由 {@code now} 现算（服务端禁常驻定时器，{@code check-no-scheduled.sh} 是门禁）。
 */
@Service
public class WarAppService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(WarAppService.class);

    /** 与 {@code NationAppService} 同一道锁超时：宣战是玩家点击触发的，等久了宁可响也不要挂住线程。 */
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final WarStore wars;
    private final NationStore nations;
    private final WarRulesAssembler assembler;
    private final TimeService timeService;
    /** 下面这几件是<b>写侧</b>（宣战）才需要的；读侧只用上面四件。 */
    private final NationMembership membership;
    private final SocialRulesAssembler socialRules;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final ConfigRegistry configs;
    /**
     * 只在「结算转换那一次」用到：把这一场的每人击杀换成国战赛季分（V18）。
     * 依赖方向是 war → rank，而 rank 不回读 war 服务，所以不会成环；
     * 之所以不自己写一份累加，是 {@code seasonId} 解析与 Bot 排除那两条已经长在榜侧了。
     */
    private final RankBoardService rankBoards;
    /** 全服目标奖励的金币走它发放（与赛季发奖同一个口：溢出进补偿队列，不由调用方自己写存档）。 */
    private final RewardService rewardService;

    public WarAppService(WarStore wars, NationStore nations, WarRulesAssembler assembler,
                         TimeService timeService, NationMembership membership,
                         SocialRulesAssembler socialRules, PlayerLock playerLock,
                         IdempotencyStore idempotency, ConfigRegistry configs,
                         RankBoardService rankBoards, RewardService rewardService) {
        this.wars = wars;
        this.nations = nations;
        this.assembler = assembler;
        this.timeService = timeService;
        this.membership = membership;
        this.socialRules = socialRules;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.configs = configs;
        this.rankBoards = rankBoards;
        this.rewardService = rewardService;
    }

    /**
     * 国战状态。
     *
     * @param playerId 请求者（{@code X-Player-Id}）。用于算<b>他本人</b>的疲劳与行军闸门 ——
     *                 这两项是按玩家分的，不是全服的，所以身份不是装饰：
     *                 漏掉它等于把甲的疲劳显示成乙的，而乙会以为自己还能再派一批
     */
    public WarStatusResp warStatus(String playerId) {
        long now = timeService.serverNow();
        WarScoreBoard.Rules rules = assembler.rules();
        // 读这一句顺带把时间推进一格：到点的那一场在这里结算并落盘（服务端禁常驻定时器）。
        // 用 settleIfExpired 而不是 findLatest —— 后者只看不动，一场打满 3 小时的仗会永远停在 SIEGE，
        // 而"什么时候算打完"这个问题没有别的执行者。
        Optional<WarStore.Settlement> progressed = wars.settleIfExpired(now);
        if (progressed.isEmpty()) {
            // 无战事：积分与击杀给 0（那是"没有任何事发生过"的真值），而 phase/startedAt/占领者给 null
            // （那三项没有真值可给，填 0 会被读成"1970 年开过一场仗"）。
            // canMarch 在这里是 true —— 疲劳闸门只在国战里生效，没有仗就没有那道闸；
            // "有没有仗"由 hasWar 单独说，一个事实只用一种表示。
            return new WarStatusResp(false, null, null, 0L, rules.gateCount(),
                    null, null, List.of(), 0L, rules.serverGoalKills(), false,
                    0L, rules.fatigueMax(), true, now,
                    // 没有仗就没有可领的：我的领取位恒 false（不填 0 冒充，宁可说清"没有"）
                    false);
        }
        WarStore.Settlement progressedOne = progressed.get();
        WarScoreBoard board = progressedOne.board();
        if (progressedOne.settledNow()) {
            // 挂在"这一句把它结掉了"，不挂在"它现在是 SETTLED"上 —— 后者每一读都成立，
            // 而 SeasonBoardStore.accumulate 是累加语义，那样会变成每读一次发一遍赛季分。
            // 这一跳是本类唯一的经济写入，所以留一行审计（金额与人数都能从这里回查）。
            // 带 result 进去：那是这一场唯一的胜负来源（内核 settle() 第二次调直接抛），
            // 而 WINNER 那一档只认它 —— 见 WarStore.Settlement#result。
            int awarded = rankBoards.reportWarSeasonPoints(board, progressedOne.result());
            LOG.info("国战赛季分进账 战事主键={} 进账人数={} 胜者={} 榜=WAR（由这一次读触发）",
                    WarStore.documentIdOf(board), awarded,
                    progressedOne.result() == null ? "无" : progressedOne.result().winnerId());
        }
        Map<String, WarScoreBoard.Score> scores = board.snapshot();
        List<WarNationScoreView> rows = new ArrayList<>(scores.size());
        for (Map.Entry<String, WarScoreBoard.Score> entry : scores.entrySet()) {
            String nationId = entry.getKey();
            WarScoreBoard.Score score = entry.getValue();
            rows.add(new WarNationScoreView(nationId, nationNameOrNull(nationId),
                    score.occupyScore(), score.killScore(), score.buildingScore(), score.total(),
                    board.gateCount(nationId), board.isQualified(nationId)));
        }
        String capitalHolder = board.capitalHolder();
        return new WarStatusResp(true, toContractPhase(board.phase()), board.startedAt(),
                board.remainingSeconds(now), rules.gateCount(),
                capitalHolder, nationNameOrNull(capitalHolder), List.copyOf(rows),
                board.totalKills(), rules.serverGoalKills(), board.serverGoalReached(),
                board.fatigueOf(playerId), rules.fatigueMax(), board.canMarch(playerId), now,
                // 本人领过没有：面板要区分"可以领/已经领过"，否则那颗键点了就被拒（验收 10 的形状）。
                // 注意它是**最后一个组件**（生成器按 schema 的 properties 顺序排，我在属性表里追加在末尾）
                board.goalClaimedBy(playerId));
    }

    /**
     * 我国对各目标还在冷却中的剩余秒数（`GET /nation/war/cooldowns`；#755 那一格）。
     *
     * <p><b>为什么单开这个口</b>：`WarStatusResp` 是全服一份的视图，而冷却是**按国家那一对**算的 ——
     * 一个全服标量装不下 N×N 个状态。所以这里按请求者下发一张只含「我国 × 各目标」的表。
     *
     * <p><b>只回还没解禁的</b>：面板的用法是"把候选目标里还在冷却的那些灰掉并写下还要等多久"，
     * 解禁的目标本来就该是亮的、不需要任何标注。
     *
     * <p><b>冷却是对称的</b>（判据读的是那一对两国之间最近那一场），所以同一个目标在两边都会出现：
     * 防守方看到的是「对方还在冷却」—— 这正是设计意图（同一对两国靠乒乓互宣在冷却期内刷击杀，
     * 是国战那一段刻意防的形状）。
     *
     * <p><b>口径与宣战那一枪共用同一个判据</b>（`WarStore#findLatestBetween` 与 `warCooldownMillis`）：
     * 面板上灰下去的那一刻，正是服务端会拒的那一刻 —— 两处各算一遍就会分叉，
     * 症状是"面板说能打、点下去被拒"（B13 那条红线：不许把内部状态印给玩家的反面）。
     */
    public WarCooldownsResp cooldowns(String playerId) {
        long now = timeService.serverNow();
        Nation mine = requireNationOf(playerId);
        long cooldownMillis = mine.warCooldownMillis();
        List<WarCooldownView> out = new ArrayList<>();
        for (Nation other : nations.all()) {
            if (other.id().equals(mine.id()) || other.isDisbanded()) {
                continue;
            }
            long remaining = wars.findLatestBetween(mine.id(), other.id())
                    .map(last -> last.startedAt() + cooldownMillis - now)
                    .orElse(0L);
            if (remaining > 0L) {
                // 向上取整到 1 秒：契约写明这一位**恒为正**，而"剩 0.4 秒"四舍五入成 0 会让
                // 面板既不灰它、点下去又被拒（差的那一下就是这一格要消灭的东西）
                out.add(new WarCooldownView(other.id(), other.name(), Math.max(1L, remaining / 1000L)));
            }
        }
        return new WarCooldownsResp(List.copyOf(out), now);
    }

    /**
     * 领取这一场的全服目标奖励（B13 §一 §7「全服累计击杀达标后每人可领一次」）。
     *
     * <p><b>为什么先标名单、后发钱</b>：发钱要动玩家存档（{@code RewardService}），
     * 而名单在战事档里 —— 两处不可能在同一个事务里。中间崩掉时，先标后发的后果是
     * 「玩家少领一次」（可补），反过来则是「同一份奖励发两次」（要回收）。
     *
     * <p><b>名单的判定与写入在存储层的临界区里</b>（{@link WarStore#claimServerGoal}）：
     * 服务层写"读板子 → 内核标名单 → 落盘"是一次没有保护的读-改-写，两个人同时点领取就会
     * 各自读到"没领过"、各自写回，后写的那份把前一份盖掉。
     *
     * <p><b>金币数现取配置</b>（{@code global.WAR_SERVER_GOAL_GOLD}）：改了表立刻生效，
     * 与 WAR 那族其余参数同一条（规则不进战事存档）。
     */
    public WarGoalClaimResp claimServerGoal(String playerId, WarGoalClaimReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                requireNationOf(playerId);
                long gold = configs.longParam("WAR_SERVER_GOAL_GOLD");
                WarStore.GoalClaimResult result = wars.claimServerGoal(playerId);
                switch (result) {
                    case NO_WAR, NOT_REACHED -> throw new BizException(ErrorCode.WAR_GOAL_NOT_REACHED,
                            "全服目标还没有达成：现在是 " + wars.findLatest()
                                    .map(b -> b.totalKills() + " / " + b.rules().serverGoalKills())
                                    .orElse("没有任何一场国战")
                                    + "（达成之后才能领）");
                    case ALREADY_CLAIMED -> throw new BizException(ErrorCode.WAR_GOAL_ALREADY_CLAIMED,
                            "这一场的全服奖励你已经领过了（每人每场只领一次）");
                    case CLAIMED -> {
                        // 名单已经标好了，这一跳是发钱；发放失败会进补偿队列（与赛季发奖同一条兜底）
                        var grant = rewardService.grant(playerId,
                                List.of(new RewardItem(RewardType.RESOURCE, ResourceIds.GOLD, gold)),
                                RewardContext.toMail("war_goal",
                                        WarStore.documentIdOf(wars.findLatest().orElseThrow()),
                                        req.requestId()));
                        if (grant.hasCompensation()) {
                            LOG.error("【国战全服奖励入账失败已进补偿队列】playerId={} 金币={} compensationId={}",
                                    playerId, gold, grant.compensationId());
                        }
                        LOG.info("国战全服奖励领取 playerId={} 金币={} 战事主键={}", playerId, gold,
                                wars.findLatest().map(WarStore::documentIdOf).orElse("-"));
                        return new WarGoalClaimResp(gold, now);
                    }
                    default -> throw new IllegalStateException("未处理的领取结果：" + result);
                }
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 宣战（B13 §一 §7 的开局那一步）：立一块积分板、把攻守两国登记成参战方，并把关系转成敌对。
     *
     * <p><b>前置的顺序是算过的</b>：本国存在 → 有 {@code DECLARE_WAR} 权限 → 不是打自己 →
     * 外交关系允许打 → 目标国存在且没解散 → 这一对两国不在冷却期 →
     * （推进上一场的时间）→ 当前没有未结束的仗。
     * 把「没有未结束的仗」放在最后不是疏忽：前面几条都是<b>不写任何东西就能否掉</b>的，
     * 而"有没有仗"这条必须和插入收在同一个临界区里才成立（见 {@link WarStore#insertIfNoneActive}）——
     * 提前查它就等于用一次带竞争的读去决定要不要走后面那条无竞争的路径，白多一个窗口。
     *
     * <p><b>为什么这一格里还发不了奖、也没有王城</b>：关卡与王城今天不是地图上的可占领实体
     * （{@code WorldEntityType} 只有城/野怪/资源/行军/建筑），所以这块板会停在
     * {@code PREPARATION}，{@code beginSiege} 进不去，占领分与建筑分永远为 0，
     * 会动的只有击杀（切片 2b）；打满 {@code WAR_DURATION_HOURS} 之后由读取惰性结算（切片 2c）。
     * <b>胜者不发奖</b>：B13/B21 都没写「赢了给什么」（{@code WAR_SERVER_GOAL_GOLD} 是另一件事 ——
     * 全服目标奖励，且领取端点也还没做），那是产品口径，已登记待裁决而不是在这里替产品决定。
     * 验收矩阵 B13 的验收 6/7 仍挂 ⬜。
     *
     * @return 宣战之后的完整国战视图（写操作回视图而不是只回 ok，与 {@code /nation/found} 那几条同一条理由：
     *         客户端据此刷新面板，少一次往返在弱网下就是少一次超时）
     */
    public WarStatusResp declare(String playerId, WarDeclareReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation attacker = requireNationOf(playerId);
                requirePermission(attacker, playerId, "DECLARE_WAR");
                String targetId = req.targetNationId();
                if (targetId == null || targetId.isBlank()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "targetNationId 不得为空");
                }
                if (targetId.equals(attacker.id())) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "不能对本国宣战：targetNationId 与发起国是同一个 " + attacker.id());
                }
                // 这条判定只长在 Nation.mayAttackNation 一处，这里只调它。在调用点重写一遍，
                // 就是"第二条攻击路径漏抄"那一族缺陷的起点（B13 冲突规则 4 的原文教训）
                if (!attacker.mayAttackNation(targetId)) {
                    throw new BizException(ErrorCode.WAR_TARGET_DIPLOMACY_BLOCKED,
                            "发起国=" + attacker.id() + " 目标=" + targetId
                                    + " 当前关系=" + attacker.diplomacyWith(targetId));
                }
                Nation target = nations.findById(targetId)
                        .filter(nation -> !nation.isDisbanded())
                        .orElseThrow(() -> new BizException(ErrorCode.WAR_TARGET_NATION_NOT_FOUND,
                                "targetNationId=" + targetId
                                        + "（查不到或已解散：解散记录仍然留在档里，所以必须过 isDisbanded）"));

                // 宣战冷却：从「这一对两国最近那一场」的<b>开场时刻</b>起算，不是从结算那一刻。
                // 表里那句「取 24 小时 = 每个国家每天最多宣战一次，配合 3 小时的战斗时长」
                // 说的就是开场时刻的间隔；从结束起算会把它变成 27 小时，那是设计者没写过的东西。
                // 毫秒口已经在 Nation 上（LevelRule.warCooldownHours() × 3600 × 1000），这里不乘第二遍。
                // 还有一条要说清的边界：那一档是<b>宣战方当前等级</b>给的。今天 nation_config 三档都是 24，
                // 所以攻守各算各的也得到同一个数，看不出分叉；真按等级分档那天要先定「用哪一方那一档」——
                // 现在这个写法意味着等级不同的两国会算出两个不同的解禁时刻，而不是一个。
                long cooldownMillis = attacker.warCooldownMillis();
                wars.findLatestBetween(attacker.id(), targetId)
                        .filter(last -> now < last.startedAt() + cooldownMillis)
                        .ifPresent(last -> {
                            throw new BizException(ErrorCode.WAR_DECLARE_COOLDOWN,
                                    "发起国=" + attacker.id() + " 目标=" + targetId
                                            + " 上一场开场于=" + last.startedAt()
                                            + " 冷却=" + cooldownMillis + "ms 剩余="
                                            + (last.startedAt() + cooldownMillis - now) / 1000L + "s");
                        });
                // 先推进上一场的时间，再判"有没有活着的仗"。不这么做的表现不是报错而是卡死式的：
                // insertIfNoneActive 读的是存储里的 phase 字面值，而一场打满 3 小时的仗在有人打开面板之前
                // phase 仍然是 SIEGE —— 于是"仗早打完了却再也宣不了战"，解锁条件落在别人的那一次读上。
                // 结算与插入各自收在自己的临界区里，这里没有把判断搬到服务层（见 WarStore#insertIfNoneActive）
                wars.settleIfExpired(now);
                // 发起国作为第三个参数交给板子：结算是另一次请求（面板那一次读），届时只能从档里读
                // "谁先动的手"，而 WAR_SEASON_INITIATOR_BONUS 是 V18 那节的主钩子。
                // 顺序仍然是 attacker 先 target 后 —— 内核 settle() 的平分判定按登记顺序遍历，
                // 但发奖读的是显式的 initiatorNationId，不是行序（见 WarScoreBoard 那一段注释）。
                WarScoreBoard board = new WarScoreBoard(assembler.rules(), now, attacker.id());
                board.registerNation(attacker.id());
                board.registerNation(target.id());
                if (!wars.insertIfNoneActive(board)) {
                    throw new BizException(ErrorCode.WAR_ALREADY_ACTIVE,
                            "发起国=" + attacker.id() + " 想开第二场；已有未结束的一场="
                                    + wars.findLatest().map(WarScoreBoard::startedAt).orElse(-1L));
                }
                // 宣战把关系转成敌对 —— Nation.mayAttackNation 的注释里「宣战后转为敌对」这一句
                // 从交付起就没有执行者，这一格是它第一次真的发生。单边记录即可：
                // C21 只要求 ALLIED/TRIBUTARY 两侧都记着才算成立，HOSTILE 不是条约
                attacker.setDiplomacy(target.id(), Nation.Diplomacy.HOSTILE);
                nations.save(attacker, attacker.version());
                LOG.info("宣战 发起国={} 目标={} 发起人={} 战事主键={} 开窗于={}",
                        attacker.id(), target.id(), playerId, WarStore.documentIdOf(board), now);
                return warStatus(playerId);
            });
        } catch (RuntimeException e) {
            // 失败要还回幂等键：否则玩家被一次网络抖动挡在"请求重复"里，永远重试不了（与国策那条同一条）
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 写侧的前置（与 NationAppService 同一套判定，不另起口径）----------

    /** 国籍跟随联盟（B13 冲突规则：联盟 ⊂ 国家）。两跳的口径收在 {@link NationMembership}，不在这里重写。 */
    private Nation requireNationOf(String playerId) {
        return membership.ofPlayer(playerId).orElseThrow(() -> new BizException(
                ErrorCode.NATION_NOT_FOUND,
                "你没有可宣战的国家：国籍跟随联盟，而「不在任何联盟」与「联盟还没入籍」对宣战是同一件事"));
    }

    /**
     * 权限位查表。<b>官职 → 档位的映射用的是 {@code core/nation/NationPermissions} 那一份</b>
     * （全项目唯一一份，读路径 {@code GET /social/permissions?scope=NATION} 也用它）——
     * 这里若自己写一遍"大将军才能宣战"，症状就是面板上那颗键亮着而点下去被拒。
     */
    private void requirePermission(Nation nation, String playerId, String permission) {
        PermissionMatrix.Tier tier =
                com.ironoath.core.nation.NationPermissions.tierOf(nation.officeOf(playerId));
        if (!socialRules.permissions().allows(PermissionMatrix.Scope.NATION, tier, permission)) {
            throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED,
                    "scope=NATION office=" + nation.officeOf(playerId) + " 缺少权限位 " + permission);
        }
    }

    /** 幂等键：宣战写的是"开一场仗"这种不可逆动作，重复提交必须挡在门口而不是靠存储层兜。 */
    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "国战域的写操作必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    /**
     * 国名一律现查、查不到给 null（客户端据此显示「未知国家」，<b>不许回落到裸 id</b>）。
     *
     * <p>为什么不在这里塞一句中文回退语：那份文案属于客户端的本地化表，服务端下发中文
     * 就成了「改一次文案要改服务端」；同 {@code GachaHistory} 的 {@code 未知武将} 一条。
     * 国家可能在战争进行中被解散，那时这一行仍然要出现在积分板上（仗是打过的事实），
     * 所以"查不到"是合法状态而不是 bug。
     */
    private String nationNameOrNull(String nationId) {
        if (nationId == null) {
            return null;
        }
        return nations.findById(nationId).map(Nation::name).orElse(null);
    }

    /**
     * 内核 {@code Phase} → 协议 {@code WarPhase}。
     *
     * <p>用 {@code valueOf(.name())} 而不是 switch：两份枚举必须同名同序，
     * 而这件事由 {@code WarEndpointTest.warPhaseMatchesTheDomainEnum} 断言钉住。
     * 真漂移时那条用例先红，而这里会抛 {@code IllegalArgumentException} —— 响亮，不会静默换个阶段。
     */
    private static WarPhase toContractPhase(WarScoreBoard.Phase phase) {
        return WarPhase.valueOf(phase.name());
    }
}
