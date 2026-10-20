package com.ironoath.web.quest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.SkillCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.quest.QuestScope;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.dto.generated.QuestClaimResp;
import com.ironoath.web.dto.generated.QuestListResp;
import com.ironoath.web.dto.generated.QuestReward;
import com.ironoath.web.dto.generated.QuestView;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：任务面板（B12 §1）—— 进度视图、领取奖励、日切/周切。
 * 依赖：{@link QuestRulesAssembler}（表 → 定义与奖励）、{@link QuestProgressStore}（进度账本）、
 *       发放器（B04，奖励不得绕过它）、社交存储与玩家仓储（状态型目标的快照）、玩家锁与幂等。
 *
 * <p><b>进度由事件推动，本类只负责「看」与「领」</b>（B12 禁止项：任务进度不得轮询）：
 * 累加型目标的累加发生在 {@link QuestEventListener}，本类读的是那一刻记下来的账本。
 *
 * <p><b>两类目标各有各的推进方式，这里不能混</b>：
 * <ul>
 *   <li><b>累加型</b>（升级/训练/击杀/抽卡/采集/帮助/参战/通关…）：只能在事件发生的那一刻记下来，
 *       读的时候算不出来（兵可能已经死了）。本类只读不写</li>
 *   <li><b>状态型</b>（当前持有某资源、是否在小队/联盟、科技等级）：进度是<b>当前状态</b>，
 *       所以读取时顺手按存档刷一次快照 —— 这不是轮询（它由那一次读触发，服务端不跑定时器），
 *       而是状态型目标唯一自洽的口径：玩家花掉粮食，进度就该退回去</li>
 * </ul>
 *
 * <p><b>读任务面板会顺手做跨天/跨周清零</b>：每日/每周任务的重置挂在"有人读"的那一刻，
 * 与周税、赛季结算同一条惰性推进的先例（B00 陷阱 2：服务端不跑定时器）。
 *
 * <p><b>本轮没有做客户端面板</b>：协议与端点先落地服务端一半（见 quest.schema.json 的说明），
 * 面板是另一档客户端工作。
 */
@Service
public class QuestAppService {

    private static final Logger LOG = LoggerFactory.getLogger(QuestAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    /**
     * 有数据源的状态型目标 —— 到今天为止<b>四个状态型目标全部有承载</b>（个人科技的那一位是 B20 块① 接上的）。
     *
     * <p>写成集合而不是散在 switch 里，是为了让"新增了一个状态型目标却没接数据源"能被一条用例抓住
     * （见 {@code QuestEndpointTest} 的 {@code everyStateTargetHasASource}）：没接的目标不是"暂时为 0"，
     * 而是<b>永远为 0</b>，而那条任务就静默地不可完成。
     */
    public static final Set<GoalType> STATE_TYPES_WITH_SOURCE = Set.of(
            GoalType.REACH_RESOURCE, GoalType.JOIN_SQUAD, GoalType.JOIN_ALLIANCE, GoalType.RESEARCH_TECH);

    private final ConfigRegistry configs;
    private final QuestRulesAssembler assembler;
    private final QuestProgressStore store;
    private final TimeService timeService;
    private final RewardService rewardService;
    /** 战令积分（B24 S-d-c）。战令没有自己的任务体系：积分就长在任务领取这一下上。 */
    private final BattlePassService battlePass;
    private final IdempotencyStore idempotency;
    private final PlayerRepository players;
    private final SocialStore socialStore;
    private final PlayerLock playerLock;
    private final RewardNames names;

    public QuestAppService(ConfigRegistry configs, QuestRulesAssembler assembler,
                           QuestProgressStore store, TimeService timeService,
                           RewardService rewardService, BattlePassService battlePass,
                           IdempotencyStore idempotency,
                           PlayerRepository players, SocialStore socialStore,
                           PlayerLock playerLock, RewardNames names) {
        this.configs = configs;
        this.assembler = assembler;
        this.store = store;
        this.timeService = timeService;
        this.rewardService = rewardService;
        this.battlePass = battlePass;
        this.idempotency = idempotency;
        this.players = players;
        this.socialStore = socialStore;
        this.playerLock = playerLock;
        this.names = names;
    }

    /** 任务面板。顺带跨期清零与状态型快照刷新（两者都只在真变化时落库）。 */
    public QuestListResp list(String playerId) {
        return list(playerId, null);
    }

    /**
     * 任务列表（只给请求的那一族）。
     *
     * <p><b>为什么要分族</b>：一次全发 76 行 ⇒ 响应体 28513B ⇒ **超了全局响应预算
     * {@code PERF_PAYLOAD_MAX_BYTES}（20480B，探针 {@code verify-perf-runtime} 实测 28162B，超 37%）。
     * 客户端的任务面板本来就把任务分成三个页签（任务 / 成就 / 首杀图鉴），
     * <b>每族单独发都在预算内</b>（任务 17 行 6273B、成就 9 行 3453B、首杀 50 行 18877B）
     * ⇒ ⇒ <b>服务端按族下发，客户端体验不变</b>（面板本来就在页签内翻页，族内行数不变）。
     *
     * <p><b>口径与客户端的本地过滤逐一对应</b>：客户端原来按
     * {@code goalType == 'SOLO_MONGER_WIN' ? 'FIRST_KILL' : questType} 分族
     * ⇒ 这里用同一个判据（{@link #scopeOf}）⇒ 两边不会各分一次而分出两套。
     */
    public QuestListResp list(String playerId, QuestScope scope) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> runProgressLocked(playerId, () -> {
            QuestProgress progress = loadProgressLocked(playerId, now);
            Map<String, String> names = new LinkedHashMap<>();
            for (QuestRulesAssembler.QuestDef def : assembler.quests()) {
                names.put(def.def().questId(), def.name());
            }
            List<QuestView> views = new ArrayList<>();
            for (QuestProgress.Entry entry : progress.entries()) {
                if (scope != null && scopeOf(entry) != scope) {
                    continue;
                }
                views.add(view(progress, entry, names.getOrDefault(entry.questId(), "未知任务")));
            }
            // 可领数也按族算：客户端只在「任务」页签显示它（`list.claimableText`），
            // 而族外的行不该算进这一族的红点。
            int claimable = 0;
            for (QuestView v : views) {
                if (v.claimable()) {
                    claimable++;
                }
            }
            return new QuestListResp(List.copyOf(views), claimable, now);
        }));
    }

    /**
     * 这一行属于哪一族。
     *
     * <p><b>判据必须与客户端原来的本地过滤一模一样</b>：客户端是
     * {@code quest.goalType === 'SOLO_MONSTER_WIN' ? 'FIRST_KILL' : quest.type}
     * ⇒ 首杀那 50 行的 {@code questType} 也是 {@code ACHIEVEMENT}
     * ⇒ <b>FIRST_KILL 优先于 ACHIEVEMENT</b>，顺序反了就会把首杀行发进成就族。
     */
    private static QuestScope scopeOf(QuestProgress.Entry entry) {
        if (entry.goalType() == GoalType.SOLO_MONSTER_WIN) {
            return QuestScope.FIRST_KILL;
        }
        if (entry.type() == QuestProgress.QuestType.ACHIEVEMENT) {
            return QuestScope.ACHIEVEMENT;
        }
        return QuestScope.QUEST;
    }

    /**
     * 此刻可领的任务数（红点口径，B12 §4）。与 {@link #list} 同源 —— 都取
     * {@code QuestProgress.claimableCount()}，不在这里另算一遍"什么算可领"。
     *
     * <p>单独开这一口的理由是成本：Bot 运行时每个 tick 都要问一次"有没有可领的"
     * （见 {@code BotWorldAdapter.observe}），为这一个布尔把全部任务行连名字带进度渲染出来
     * 是白花的服务端预算。
     */
    public int claimableCount(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                () -> runProgressLocked(playerId, () -> loadProgressLocked(playerId, now).claimableCount()));
    }

    /** 领取奖励：普通任务先保存投影；永久金币先原子接受钱包收据，再保存可恢复的已领投影。 */
    public QuestClaimResp claim(String playerId, QuestClaimReq req) {
        long now = timeService.serverNow();
        String requestKey = acquire(playerId, req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> runProgressLocked(playerId, () -> {
                QuestProgress progress = loadProgressLocked(playerId, now);
                String questId = req.questId();
                if (questId == null || questId.isBlank()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "questId 不得为空");
                }
                // 三选一的校验放在 claim 之前：它决定这次要领的到底是什么。
                // 放到后面会让「已领」标记与「没选中」的失败顺序反转 —— 玩家点了按钮却发现要领的东西没定
                QuestProgress.Entry entry;
                try { entry = progress.entry(questId); }
                catch (IllegalArgumentException missing) {
                    throw new BizException(ErrorCode.QUEST_NOT_FOUND, "任务已更新，请刷新任务列表后再试。");
                }
                boolean permanent = permanentGold(entry);
                if (permanent && req.heroChoice() != null && !req.heroChoice().isBlank())
                    throw new BizException(ErrorCode.PARAM_INVALID, "该成就不需要选择武将。");
                List<RewardItem> choices = permanent ? List.of() : heroChoiceReward(questId, req.heroChoice());
                try {
                    progress.claim(questId);
                } catch (IllegalArgumentException e) {
                    throw new BizException(ErrorCode.QUEST_NOT_FOUND, e.getMessage());
                } catch (IllegalStateException e) {
                    throw new BizException(claimErrorOf(e.getMessage()), permanent
                            ? entry.claimed() ? "该成就奖励已经领取，请刷新查看。"
                            : "该成就尚未满足领取条件：" + entry.current() + "/" + entry.goalValue() : e.getMessage());
                }
                // 普通奖励沿用先投影后发奖；永久金币以钱包原子收据防重复，投影丢失由读取修复。
                if (!permanent) store.save(playerId, stateOf(progress));
                List<RewardItem> rewards = permanent ? new ArrayList<>(permanentRewards(entry))
                        : new ArrayList<>(assembler.rewardsByQuest().getOrDefault(questId, List.of()));
                // 选中的武将是「这次领奖」的一部分，追加在表里那批之后：
                // 顺序即客户端飘字顺序，把它放最后是为了先让玩家看到资源到账、再看到武将入手
                rewards.addAll(choices);
                List<RewardItem> granted = List.of();
                String message = null;
                if (!rewards.isEmpty()) {
                    var result = rewardService.grant(playerId, rewards,
                            permanent ? new RewardContext("free_gold", questId, achievementKey(questId), true,
                                    achievementReceiptFacts(entry))
                                    : RewardContext.toMail("quest", questId, questId + ":" + req.requestId()));
                    granted = result.granted();
                    if (permanent) {
                        long credited = granted.stream().mapToLong(RewardItem::count).sum();
                        long overflow = result.overflow().stream().mapToLong(RewardItem::count).sum();
                        message = "成就已领取，金币入账" + credited + (overflow > 0 ? "，另" + overflow + "金币已转入邮箱。" : "。");
                    }
                    if (result.hasCompensation()) {
                        LOG.error("【任务奖励未入账已进补偿队列】playerId={} questId={} 奖励={} compensationId={}",
                                playerId, questId, rewards, result.compensationId());
                    }
                }
                // 战令积分：**只在领取成功这一下加**，加多少由 quest 表那一列给。
                // 幂等不必在这里再做一次：重放请求在时 acquire(requestId) 就被挡掉了，走不到这里
                if (permanent) store.save(playerId, stateOf(progress));
                long points = permanent ? 0 : configs.get(com.ironoath.config.cfg.QuestCfg.class, questId).battlePassPoints();
                if (points > 0L) {
                    battlePass.addPoints(playerId, points, "quest:" + questId);
                }
                LOG.info("任务奖励已领取 playerId={} questId={} 奖励={} 战令积分+{}", playerId, questId, granted, points);
                return new QuestClaimResp(questId, rewardViews(granted), progress.claimableCount(), now, message);
            }));
        } catch (RuntimeException e) {
            idempotency.release(requestKey);
            throw e;
        }
    }

    // ---------- 进度账本的载入与推进 ----------

    /**
     * 读某一条任务此刻的状态（达标没有、奖领了没有）。给引导用（B18 §一.2：完成判定读服务端既有状态，
     * 而且必须是<b>任务的那一份</b>状态 —— 引导自己算一遍就会与主线分叉）。
     *
     * <p><b>为什么不把 {@link #loaded} 直接公开</b>：那等于对外交出整本账，调用方拿到的是
     * 「可以自己判、也可以顺手写」的一堆条目；引导只需要一位布尔，就只给它一位。
     * 内部照样走 {@code loaded} —— 跨期清零与状态型快照都在那一步，绕过它就等于读一份过期账本。
     *
     * <p>返回空 = 账本里没有这条任务（配置外键已由启动期校验，走到这里通常是表改了而进程没重载），
     * 调用方按「未达成」处理即可：引导停在当前步，不报错也不推进。
     */
    public java.util.Optional<QuestProgress.Entry> entryOf(String playerId, String questId) {
        if (questId == null || questId.isBlank()) {
            return java.util.Optional.empty();
        }
        for (QuestProgress.Entry entry : loaded(playerId, timeService.serverNow()).entries()) {
            if (entry.questId().equals(questId)) {
                return java.util.Optional.of(entry);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 载入（或新建）进度账本，并顺手推进两件随时间变化的事：跨期清零、状态型快照。
     *
     * <p><b>只有真变化才落库</b>：读面板是高频动作，每次都写一遍会让存储成为热点，
     * 而这里的两件事都只在跨天/跨周或状态真的变了时才发生。
     */
    private <T> T runProgressLocked(String playerId, java.util.function.Supplier<T> action) {
        return playerLock.runLocked(QuestProgressStore.lockKey(playerId), LOCK_TIMEOUT_MS, action);
    }

    QuestProgress loaded(String playerId, long now) {
        // entryOf也会触发刷新/跨日落盘；其读改写同样必须与异主人结算串行。
        return runProgressLocked(playerId, () -> loadProgressLocked(playerId, now));
    }

    /** 只供已持任务账本锁的调用；外部读取仍通过loaded取得同一把共享锁。 */
    private QuestProgress loadProgressLocked(String playerId, long now) {
        List<QuestRulesAssembler.QuestDef> defs = assembler.quests();
        String day = DayKey.of(now);
        String week = WeekKey.of(now);
        QuestProgressStore.State stored = store.load(playerId).orElse(null);
        QuestProgress progress = reconcile(playerId, defs, stored, day, week);
        boolean dirty = progress.rollover(day, week) > 0;
        if (refreshStateTargets(progress, now) > 0) {
            dirty = true;
        }
        if (dirty) {
            store.save(playerId, stateOf(progress));
        }
        return progress;
    }

    /**
     * 把账本与当前的表对上一次账：<b>定义字段取表、进度字段取账本</b>。
     *
     * <p>少这一步会有两种症状：表里新加的任务永远不出现（账本里没有它的条目），
     * 表里改过的 goalValue 对老玩家不生效（条目里存着旧值）。
     * 两者都不报错 —— 只是"改了表没反应"，正是本项目反复防的那一类。
     */
    static QuestProgress reconcile(String playerId, List<QuestRulesAssembler.QuestDef> defs,
                                           QuestProgressStore.State stored, String day, String week) {
        if (stored == null) {
            return QuestProgress.open(playerId, defs.stream()
                    .map(QuestRulesAssembler.QuestDef::def).toList(), day, week);
        }
        Map<String, QuestProgress.Entry> old = new LinkedHashMap<>();
        for (QuestProgress.Entry entry : stored.entries()) {
            old.put(entry.questId(), entry);
        }
        List<QuestProgress.Entry> merged = new ArrayList<>(defs.size());
        for (QuestRulesAssembler.QuestDef def : defs) {
            QuestProgress.Entry previous = old.remove(def.def().questId());
            // 已达标永久事实代表接受过的资格；改表不能倒退目标或改写当时奖励。
            if (previous != null && previous.requiresFact() && (previous.complete() || previous.claimed())) {
                merged.add(previous);
                continue;
            }
            merged.add(new QuestProgress.Entry(def.def().questId(), def.def().type(),
                    def.def().goalType(), def.def().goalTarget(), def.def().goalValue(),
                    def.def().preQuestId(),
                    previous == null ? 0L : previous.current(),
                    previous != null && previous.claimed(), def.def().requiresFact(),
                    previous == null ? java.util.Set.of() : previous.factIds(), def.def().goldReward(),
                    previous == null ? null : previous.firstVictoryAt()));
        }
        // 配置删除不能撤销已接受的永久金币资格；未达标旧定义不凭空获得奖励。
        for (QuestProgress.Entry previous : old.values()) {
            if (permanentGold(previous) && previous.type() == QuestProgress.QuestType.ACHIEVEMENT
                    && (previous.complete() || previous.claimed())) merged.add(previous);
        }
        return QuestProgress.restore(playerId, merged, stored.dayKey(), stored.weekKey());
    }

    /**
     * 状态型目标的快照刷新（见类注释：这不是轮询）。
     *
     * <p>没有数据源的目标（今天的个人科技）<b>保持 0 并静默跳过</b>：它对应的子系统还没实现，
     * 编一个值比留 0 更坏 —— 0 是"还没做"，编出来的值是"做了但不算数"。
     */
    private int refreshStateTargets(QuestProgress progress, long now) {
        int changed = 0;
        // 一次刷新内<b>只读一份本人存档</b>，供所有要档的状态型目标共用：改之前"攒资源"与
        // "研究科技"这两位各自去 findByPlayerId，等于同一份整档读两遍，而这条路径挂在面板打开
        // （list）与 Bot 每个 tick 的红点（claimableCount）上。判据是往返计数：
        // QuestStateSnapshotQueryCountTest。
        // 这里不做"首次用到才读"（#430 那一处需要）：任务表里恒有这两位，每次刷新都必然用到，
        // 加一个惰性标志只是多一处会写错的机械。
        // 顺带修掉的是另一个更难看见的问题：两次分开读之间若有人写了这个号，同一次刷新里的
        // 两位目标会看到两个版本的存档，而面板把这两行画在一起。
        PlayerSave owner = players.findByPlayerId(progress.playerId()).orElse(null);
        if (owner != null && owner.goldClaims().values().stream()
                .anyMatch(com.ironoath.core.reward.GoldClaimReceipt::pendingOverflow)) {
            rewardService.recoverGoldClaims(progress.playerId());
        }
        if (owner != null) for (QuestProgress.Entry entry : progress.entries()) {
            if (!permanentGold(entry)) continue;
            var receipt = owner.goldClaims().get(achievementKey(entry.questId()));
            if (receipt == null || !"free_gold".equals(receipt.source()) || !entry.questId().equals(receipt.sourceRef())) continue;
            long goal = receipt.facts().stream().filter(f -> f.startsWith("achievement-goal:"))
                    .mapToLong(f -> Long.parseLong(f.substring("achievement-goal:".length()))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("成就收据缺少资格目标"));
            Long firstAt = receipt.facts().stream().filter(f -> f.startsWith("first-victory-at:"))
                    .map(f -> Long.valueOf(f.substring("first-victory-at:".length()))).findFirst().orElse(null);
            if (progress.restoreClaimedFromGoldReceipt(entry.questId(), goal, receipt.requested(), firstAt)) changed++;
        }
        for (QuestProgress.Entry entry : progress.entries()) {
            if (entry.goalType().accumulates() || !STATE_TYPES_WITH_SOURCE.contains(entry.goalType())) {
                continue;
            }
            // A source row may be locked while its event still feeds an unlocked
            // wildcard/same-target row. Skip only when no row can consume it;
            // claimed state rows still refresh, just as in QuestProgress.onEvent.
            var signal = com.ironoath.core.event.GameEvent.state(progress.playerId(),
                    entry.goalType(), entry.goalTarget(), 0L, now);
            boolean hasConsumer = progress.entries().stream().anyMatch(candidate ->
                    candidate.goalType() == entry.goalType() && progress.unlocked(candidate.questId())
                            && signal.matchesTarget(candidate.goalTarget()));
            if (!hasConsumer) continue;
            long current = stateValue(progress.playerId(), entry, owner);
            if (current == entry.current()) {
                continue;
            }
            progress.onEvent(com.ironoath.core.event.GameEvent.state(progress.playerId(),
                    entry.goalType(), entry.goalTarget(), current, now));
            changed++;
        }
        return changed;
    }

    /**
     * 状态型目标的当前值。取不到（没有存档等）按 0 —— 与"没做过"同义。
     *
     * @param owner 本次刷新共享的那一份本人存档，<b>可能为 null</b>（删号后账本还在）；
     *              两个要档的分支都从它取，不许在这里再点查一次
     */
    private long stateValue(String playerId, QuestProgress.Entry entry, PlayerSave owner) {
        switch (entry.goalType()) {
            case REACH_RESOURCE -> {
                if (owner == null || entry.goalTarget() == null) {
                    return 0L;
                }
                var resource = owner.resources().get(entry.goalTarget());
                return resource == null ? 0L : resource.current();
            }
            case JOIN_SQUAD -> {
                return socialStore.squadOf(playerId).isPresent() ? 1L : 0L;
            }
            case JOIN_ALLIANCE -> {
                return socialStore.allianceOf(playerId).isPresent() ? 1L : 0L;
            }
            case RESEARCH_TECH -> {
                // 读的是科技账本（不是"研究过几次"）：状态型目标记的就是当前等级，赛季回落时它会跟着回落。
                // 没结算到期的研究不算数 —— 那位次在玩家下一次读取（含本路径经过的结算）之后才进账本。
                if (owner == null) {
                    return 0L;
                }
                return entry.goalTarget() == null || entry.goalTarget().isBlank()
                        ? owner.tech().levels().size()          // 「研究过科技」：按研究到的行数计
                        : owner.tech().levelOf(entry.goalTarget());
            }
            default -> {
                // 新增状态型目标时必须在这里接数据源，否则它的进度永远是 0
                // （QuestEndpointTest.everyStateTargetHasASource 会把这件事变红）
                return 0L;
            }
        }
    }

    private QuestProgressStore.State stateOf(QuestProgress progress) {
        return new QuestProgressStore.State(progress.playerId(), progress.entries(),
                progress.dayKey(), progress.weekKey());
    }

    private Set<String> achievementReceiptFacts(QuestProgress.Entry entry) {
        var facts = new java.util.HashSet<String>();
        facts.add("achievement-goal:" + entry.goalValue());
        facts.add("achievement-gold:" + entry.goldReward());
        if (entry.firstVictoryAt() != null) facts.add("first-victory-at:" + entry.firstVictoryAt());
        return Set.copyOf(facts);
    }

    // ---------- 视图 ----------

    private QuestView view(QuestProgress progress, QuestProgress.Entry entry, String name) {
        return new QuestView(entry.questId(), name,
                com.ironoath.web.dto.generated.QuestType.valueOf(entry.type().name()),
                com.ironoath.web.dto.generated.GoalType.valueOf(entry.goalType().name()),
                entry.goalTarget(), entry.goalValue(), entry.current(), entry.complete(),
                entry.claimed(), progress.claimable(entry.questId()),
                !progress.unlocked(entry.questId()), entry.preQuestId(),
                permanentGold(entry) ? List.of() : heroChoiceViews(entry.questId()),
                permanentGold(entry) ? rewardViews(permanentRewards(entry)) : List.of(), entry.firstVictoryAt());
    }

    /**
     * 某条任务的候选武将（空列表 = 这条任务没有「挑一名」那项奖励）。
     *
     * <p>找不到这条任务时返回空列表而不是抛：视图组装走在读路径上，
     * 而表被人手改坏（任务被删）时玩家的面板不该整页 500 —— 与 {@code names()} 的兜底同一条理由。
     */
    private List<String> heroChoicesOf(String questId) {
        for (QuestRulesAssembler.QuestDef def : assembler.quests()) {
            if (def.def().questId().equals(questId)) {
                return def.heroChoices();
            }
        }
        return List.of();
    }

    /**
     * 候选的展示形态：选择 id 与配置里的名字、基础三维、技能说明一起下发。
     *
     * <p><b>名字在这里解析，客户端不查表</b>：与 {@code QuestReward.name} 同一条口径 ——
     * 客户端自己翻译武将名，会在表改名之后与服务端日志、客服工单里的称呼对不上。
     * 展示工厂缺配置时只给玩家安全回退语，不打印内部 id；任务装配器与领取侧原有候选校验仍保留。
     */
    private List<com.ironoath.web.dto.generated.HeroChoice> heroChoiceViews(String questId) {
        List<String> ids = heroChoicesOf(questId);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<com.ironoath.web.dto.generated.HeroChoice> out = new ArrayList<>(ids.size());
        for (String heroId : ids) {
            out.add(heroChoiceView(heroId, questId));
        }
        return out;
    }

    private com.ironoath.web.dto.generated.HeroChoice heroChoiceView(String heroId, String questId) {
        HeroCfg hero;
        try {
            hero = configs.get(HeroCfg.class, heroId);
        } catch (ConfigException e) {
            LOG.warn("候选武将 {} 缺配置（任务 {}），显示安全回退语", heroId, questId);
            return new com.ironoath.web.dto.generated.HeroChoice(heroId, "未知武将", null,
                    null, null, null, "未知技能", "技能信息暂不可用",
                    "未知技能", "技能信息暂不可用", "技能信息暂不可用");
        }
        SkillChoiceInfo main = skillChoiceInfo(hero.mainSkill());
        SkillChoiceInfo sub = skillChoiceInfo(hero.subSkill());
        String feature = main.feature() == null && sub.feature() == null ? "技能信息暂不可用"
                : "主技能：" + featureOrUnavailable(main) + "；副技能：" + featureOrUnavailable(sub);
        return new com.ironoath.web.dto.generated.HeroChoice(heroId,
                playerConfigName(hero.name(), heroId, "未知武将"),
                hero.rarity() == null ? null : hero.rarity().name(), hero.might(), hero.command(), hero.wisdom(),
                main.name(), main.description(), sub.name(), sub.description(), feature);
    }

    /** 仅解释基础技能配置；不把设计 why、技能 id 或内部枚举印给玩家。 */
    private SkillChoiceInfo skillChoiceInfo(String skillId) {
        SkillCfg skill;
        try {
            if (skillId == null || skillId.isBlank()) return SkillChoiceInfo.unavailable();
            skill = configs.get(SkillCfg.class, skillId);
        } catch (ConfigException e) {
            LOG.warn("候选技能 {} 缺配置，显示安全回退语", skillId);
            return SkillChoiceInfo.unavailable();
        }
        String name = playerConfigName(skill.name(), skillId, "未知技能");
        // 战斗内核对 SKIP_TURN 刻意不施加效果，不能在选将时声称硬控已可用。
        if (skill.effect() == null || skill.effect() == SkillCfg.Effect.SKIP_TURN || skill.trigger() == null) {
            return new SkillChoiceInfo(name, "技能信息暂不可用", null);
        }
        String trigger = switch (skill.trigger()) {
            case ROUND_START -> "回合开始时";
            case EVERY_ROUND -> "每回合";
            case ON_HIT -> "受击时";
            case ON_DEATH -> "己方单位阵亡时";
        };
        String value = skillPercent(skill.value());
        String effect = switch (skill.effect()) {
            case DAMAGE -> "额外伤害系数" + value;
            case HEAL -> "回复己方损失兵力的" + value;
            case BUFF_ATK -> "提升己方攻击" + value;
            case BUFF_DEF -> "提升己方防御" + value;
            case DEBUFF_ATK -> "降低敌方攻击" + value;
            case DEBUFF_DEF -> "降低敌方防御" + value;
            case SKIP_TURN -> throw new IllegalStateException("未实现硬控不应进入技能说明");
        };
        String feature = switch (skill.effect()) {
            case DAMAGE -> "额外伤害";
            case HEAL -> "回复损失兵力";
            case BUFF_ATK -> "提升攻击";
            case BUFF_DEF -> "提升防御";
            case DEBUFF_ATK -> "降低敌方攻击";
            case DEBUFF_DEF -> "降低敌方防御";
            case SKIP_TURN -> throw new IllegalStateException("未实现硬控不应进入技能特点");
        };
        boolean sustained = switch (skill.effect()) {
            case BUFF_ATK, BUFF_DEF, DEBUFF_ATK, DEBUFF_DEF -> true;
            default -> false;
        };
        String duration = sustained && skill.durationRounds() > 0 ? "；持续" + skill.durationRounds() + "回合" : "";
        return new SkillChoiceInfo(name, trigger + "，" + skillPercent(skill.chance()) + "概率："
                + effect + duration + "。", feature);
    }

    private static String skillPercent(long fixed) {
        return BigDecimal.valueOf(fixed, 2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String playerConfigName(String name, String id, String fallback) {
        return name == null || name.isBlank() || name.equals(id) || name.startsWith("hero_") || name.startsWith("skill_")
                ? fallback : name;
    }

    private static String featureOrUnavailable(SkillChoiceInfo skill) {
        return skill.feature() == null ? "技能信息暂不可用" : skill.feature();
    }

    private record SkillChoiceInfo(String name, String description, String feature) {
        static SkillChoiceInfo unavailable() {
            return new SkillChoiceInfo("未知技能", "技能信息暂不可用", null);
        }
    }

    /**
     * 三选一的校验与折算（B06 §1「主线赠送：首日必得 1 名 SR」）。
     *
     * <p><b>三种情况都要响亮地失败，不许替玩家默认挑一个</b>：
     * <ul>
     *   <li>有候选但没传选择 ⇒ 拒，并回可用候选。替玩家挑一个等于「三选一」变成「系统选中一个」，
     *       而玩家永远不知道那次选择发生过；</li>
     *   <li>传的选择不在候选里 ⇒ 拒。放行等于候选列表形同虚设（客户端改一个字符串就换将）；</li>
     *   <li>没有候选却传了选择 ⇒ 也拒。静默忽略会让客户端以为自己选上了，
     *       而玩家点的是别的地方的奖励 —— 与「多传的东西一律忽略」那条宽松惯例相比，
     *       这里更需要让人发现调用写错了。</li>
     * </ul>
     * 三种都复用 {@code PARAM_INVALID}：它们都是「这次请求说错了」，
     * 玩家侧的正确动作都是重开选择界面，不是重试。
     *
     * @return 选中武将对应的奖励项；这条任务没有选择时返回空列表
     */
    private List<RewardItem> heroChoiceReward(String questId, String heroChoice) {
        List<String> candidates = heroChoicesOf(questId);
        boolean picked = heroChoice != null && !heroChoice.isBlank();
        if (candidates.isEmpty()) {
            if (picked) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "任务 " + questId + " 的奖励里没有可挑的武将，不该带 heroChoice=" + heroChoice);
            }
            return List.of();
        }
        if (!picked) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "任务 " + questId + " 需要从候选里挑一名武将（heroChoice），候选=" + candidates);
        }
        if (!candidates.contains(heroChoice)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "heroChoice=" + heroChoice + " 不在候选里，候选=" + candidates);
        }
        return List.of(new RewardItem(RewardType.HERO, heroChoice, 1L));
    }

    private List<QuestReward> rewardViews(List<RewardItem> items) {
        List<QuestReward> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new QuestReward(item.type().name(), item.id(), item.count(), rewardName(item)));
        }
        return List.copyOf(out);
    }

    /**
     * 奖励的展示名。<b>规则不住在本类</b>：同一张奖励在任务面板、背包与邮件附件里必须同名，
     * 而这条规则以前在 quest 与 bag 各有一份、且<b>两份不一致</b>（碎片一份回「SSR 武将碎片」
     * 一份回裸 id），前者的注释还自称「与 BagAppService 同一条口径」。现在唯一实现在
     * {@link RewardNames}，碎片的映射问的是发放侧 {@code HeroFragmentExtras.fragmentItemOf}。
     */
    private String rewardName(RewardItem reward) {
        return names.nameOf(reward);
    }

    // ---------- 内部 ----------

    private ErrorCode claimErrorOf(String message) {
        if (message.contains("已经领过")) {
            return ErrorCode.QUEST_ALREADY_CLAIMED;
        }
        if (message.contains("尚未解锁")) {
            return ErrorCode.QUEST_LOCKED;
        }
        return ErrorCode.QUEST_NOT_COMPLETE;
    }

    private static boolean permanentGold(QuestProgress.Entry entry) { return entry.requiresFact() && entry.goldReward() != null; }
    private static String achievementKey(String questId) { return "achievement:" + questId; }
    private static List<RewardItem> permanentRewards(QuestProgress.Entry entry) {
        return List.of(new RewardItem(RewardType.RESOURCE, "GOLD", entry.goldReward()));
    }

    private String acquire(String playerId, String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "领取任务奖励必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        String key = "quest:" + playerId + ":" + requestId;
        if (!idempotency.tryAcquire(key, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "请勿重复领取，请刷新查看结果。");
        }
        return key;
    }
}
