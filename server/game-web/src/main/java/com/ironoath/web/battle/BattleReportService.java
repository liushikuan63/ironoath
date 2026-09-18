package com.ironoath.web.battle;

import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.RoundSnapshot;
import com.ironoath.battle.SkillTrigger;
import com.ironoath.battle.UnitType;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.SkillCfg;
import com.ironoath.web.dto.generated.BattlePlaybackParams;
import com.ironoath.web.dto.generated.BattleReportBrief;
import com.ironoath.web.dto.generated.BattleReportListResp;
import com.ironoath.web.dto.generated.BattleReportResp;
import com.ironoath.web.dto.generated.BattleResultView;
import com.ironoath.web.dto.generated.BattleSide;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.LootEntry;
import com.ironoath.web.dto.generated.ReportShareReq;
import com.ironoath.web.dto.generated.ReportShareResp;
import com.ironoath.web.dto.generated.RoundView;
import com.ironoath.web.dto.generated.SkillTriggerView;
import com.ironoath.web.dto.generated.UnitStack;
import com.ironoath.web.service.SocialAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 职责：战报的落库、查询与「内核战果 → 协议视图」的映射（B09 战报持久化）。
 * 依赖：game-battle 的战果类型、game-config（skill 表取中文名）、{@link BattleReportStore}。
 *
 * <p><b>为什么必须有战报，而不是让玩家看日志</b>：B05 已经交付了客户端的 BattlePlayback
 * （逐回合时间轴、1x/2x、跳过），它一直在等这份数据 —— 没有战报，
 * 玩家打完一场只看到「兵少了、资源多了」，中间发生了什么完全是黑箱。
 * 而战斗是这类游戏里最容易引发数值争议的地方：「我 3000 兵怎么打不过 2000 兵」
 * 若没有逐回合的减员系数与乘区可看，玩家的结论只会是「这游戏数值是假的」。
 *
 * <p><b>视图里刻意不含双方的完整属性</b>：{@code RoundView} 只有每回合的兵力、损失、
 * 有效攻击/防御与减员系数，没有对方的兵种属性表与武将明细。
 * 回放需要的是「发生了什么」，不是「对方有多强」—— 后者是侦查的职责（B07 §3），
 * 白送会让情报系统失去意义。
 *
 * <p><b>技能触发方靠武将归属推断</b>：内核的 {@link SkillTrigger} 不带 side 字段
 * （它只关心技能效果，不关心是谁的），所以这里用「heroId 在哪一方的上阵名单里」判定。
 * 推断不出来时<b>当场抛异常</b>而不是默认算攻方：默认值会让战报把守方的治疗
 * 显示成攻方的技能，而那种错误在回放里看起来完全合理，没人会发现。
 */
@Service
public class BattleReportService {

    private static final Logger LOG = LoggerFactory.getLogger(BattleReportService.class);

    private final ConfigRegistry configs;
    private final BattleReportStore store;
    private final TimeService timeService;
    /**
     * 分享要把消息写进组织频道，而频道的资格/限流/送检都在聊天域里 ——
     * 那条路只此一条（见 {@code SocialAppService.postSharedReport} 的注释）。
     */
    private final SocialAppService social;
    /** 击杀上报（B23 的 KILL 榜）。战报域是所有战斗的唯一漏斗，所以击杀累计挂在这里。 */
    private final com.ironoath.web.rank.RankBoardService ranks;

    public BattleReportService(ConfigRegistry configs, BattleReportStore store,
                               TimeService timeService, SocialAppService social,
                               com.ironoath.web.rank.RankBoardService ranks) {
        this.configs = configs;
        this.store = store;
        this.timeService = timeService;
        this.social = social;
        this.ranks = ranks;
    }

    /**
     * 记录一场战斗。
     *
     * <p>PVP 时攻守双方应当各记一份（除 ownerId 外内容相同），
     * 这样两个人都能在自己的列表里看到同一场 —— 只记攻方的话，
     * 被打的那个人下线回来完全不知道自己是怎么输的，而那是他最想知道的时刻。
     * 两份记录相同，但列表里的「对手」与「胜/败」是<b>按主人视角渲染</b>的
     * （见 {@code BattleReport.opponentId()} / {@code won()}），
     * 所以两个人看到的是同一场战斗的两个视角，而不是两条重复数据。
     *
     * @param attackerName 攻方昵称。<b>PVE 调用方传 null</b>：那种战报的主人就是攻方，
     *                     对手名永远取守方名（野怪名或关卡名），这个字段用不上。
     *                     <b>PVP 必须传</b>：守方那一份的对手显示名取的正是它，
     *                     缺了它守方点开战报会看到「对手：我自己」
     */
    public BattleReport record(String ownerId, String attackerId, String defenderId,
                               String defenderName, String attackerName, BattleType battleType,
                               List<String> attackerHeroIds, List<String> defenderHeroIds,
                               BattleResult result, long now) {
        long ttlSeconds = configs.longParam("BATTLE_REPORT_TTL_SECONDS");
        BattleReport report = new BattleReport(
                "battle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                ownerId, attackerId, defenderId, defenderName, attackerName, battleType,
                attackerHeroIds, defenderHeroIds, result, now, now + ttlSeconds * 1000L);
        store.save(report);
        // 击杀累计（B23 §一 1 的 KILL 榜）：**按主人视角**算 —— 战报里攻守两方各存一份，
        // 所以"对方死掉的那些"才是这份记录主人的击杀。只算阵亡（wounded 是伤兵，治得回来）
        long kills = ownerId.equals(attackerId) ? result.defDead() : result.atkDead();
        ranks.reportKills(ownerId, nicknameOf(ownerId), kills);
        LOG.info("战报已落库 reportId={} ownerId={} 类型={} 对手={} 结果={} 回合={} seed={} 过期={}",
                report.reportId(), ownerId, battleType, defenderId, result.winner(),
                result.totalRounds(), result.seed(), report.expiresAt());
        return report;
    }

    /** 上报击杀时用的昵称：战报域不认识玩家账户，从社交域借一个（它已经有这一处查询）。 */
    private String nicknameOf(String playerId) {
        return social.nicknameOf(playerId);
    }

    /** 我的战报列表（先惰性清理过期的，再返回）。 */
    public BattleReportListResp list(String playerId) {
        requirePlayer(playerId);
        long now = timeService.serverNow();
        purgeExpired(now);
        List<BattleReportBrief> briefs = new ArrayList<>();
        for (BattleReport report : store.reportsOf(playerId)) {
            BattleResult result = report.result();
            briefs.add(new BattleReportBrief(report.reportId(),
                    com.ironoath.web.dto.generated.BattleType.valueOf(report.battleType().name()),
                    report.opponentId(), report.opponentName(),
                    BattleSide.valueOf(result.winner().name()),
                    report.won(),
                    result.totalRounds(),
                    result.atkDead() + result.atkWounded(),
                    result.defDead() + result.defWounded(),
                    report.createdAt(), report.expiresAt()));
        }
        return new BattleReportListResp(briefs, now);
    }

    /**
     * 打开一份战报的完整回放数据。
     *
     * <p><b>不是自己的战报一律回「不存在」</b>，与 {@code MarchAppService.requireOwnMarch}
     * 同一条口径：回「不属于你」等于给了一个探测别人战报 id 的接口 ——
     * 而 PVP 战报里含对方的兵力构成，那是侦查才能拿到的情报。
     */
    public BattleReportResp open(String playerId, String reportId) {
        requirePlayer(playerId);
        if (reportId == null || reportId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "reportId 不得为空");
        }
        long now = timeService.serverNow();
        BattleReport report = store.findById(reportId).orElse(null);
        if (report == null || !visibleTo(playerId, report)) {
            // 不是你的、又没有分享给你 ⇒ 一律按"不存在"回。分开说"存在但不是你的"
            // 等于给了一条探测别人战报 id 的通道，而玩家从中得不到任何可操作的信息
            throw new BizException(ErrorCode.BATTLE_REPORT_NOT_FOUND, "战报不存在: " + reportId);
        }
        if (report.expired(now)) {
            // 过期但还没被清理到：明确说清楚是过期，而不是回一个空的回放。
            // 玩家看到「8 回合的战斗、0 个回合数据」会以为战报坏了
            throw new BizException(ErrorCode.BATTLE_REPORT_EXPIRED,
                    "战报已过期（保留 " + configs.longParam("BATTLE_REPORT_TTL_SECONDS") / 3600L
                            + " 小时），无法回放");
        }
        // 回放时间参数随详情下发（与 TrackPolicy 随 /ops/app/version 下发同一条理由）：
        // 这两个数住在 global 表里，客户端再写一份就是同一个事实两个家。
        // **只发表里真有的这两条** —— 开场/结算/技能三段的时长表里没有，客户端按
        // 「与回合时长同量级」推导（BattleReportPanel.playbackOptionsOf），在这里再造三个数就是发明。
        return new BattleReportResp(report.reportId(), toView(report, now),
                report.createdAt(), report.expiresAt(), now,
                new BattlePlaybackParams(configs.longParam("BATTLE_ROUND_DISPLAY_MS"),
                        configs.stringParam("BATTLE_PLAYBACK_SPEEDS")));
    }

    /**
     * 把一份自己的战报分享到小队 / 联盟频道（B22 §一 2）。
     *
     * <p><b>为什么只能分享自己的</b>：战报里有自己的兵力构成、坐标与上阵武将 ——
     * 替别人分享等于替别人公开。所以先按 `ownerId` 判归属，再谈频道。
     *
     * <p><b>分享不发奖励</b>（B15 禁止诱导分享）：这里没有、也不该有任何 reward 逻辑。
     * 分享本身只是"把这一场贴给战友看"。
     *
     * <p>落地顺序：先写频道（可能因资格/限流/送检失败），成功之后才记账 ——
     * 反过来的话，一次被限流拒绝的分享也会让这份战报对频道成员可见，
     * 而那件事没有任何人看得见（消息根本没发出去）。
     */
    public ReportShareResp share(String playerId, ReportShareReq req, long now) {
        requirePlayer(playerId);
        if (req == null || req.reportId() == null || req.reportId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "reportId 不得为空");
        }
        if (req.channel() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        BattleReport report = store.findById(req.reportId()).orElse(null);
        if (report == null) {
            throw new BizException(ErrorCode.BATTLE_REPORT_NOT_FOUND, "战报不存在: " + req.reportId());
        }
        if (!report.ownerId().equals(playerId)) {
            throw new BizException(ErrorCode.REPORT_NOT_OWNED, "只能分享自己的战报");
        }
        if (report.expired(now)) {
            throw new BizException(ErrorCode.BATTLE_REPORT_EXPIRED,
                    "战报已过期（保留 " + configs.longParam("BATTLE_REPORT_TTL_SECONDS") / 3600L
                            + " 小时），不能分享");
        }
        ChatChannel channel = ChatChannel.valueOf(req.channel().name());
        SocialAppService.SharedPost post =
                social.postSharedReport(playerId, channel, shareText(report), now);
        store.markShared(report.reportId(), post.channelKey());
        LOG.info("战报已分享 reportId={} 分享人={} 频道={} 消息={}",
                report.reportId(), playerId, channel, post.messageId());
        return new ReportShareResp(report.reportId(), req.channel(), post.messageId(), now);
    }

    /**
     * 分享消息的正文。
     *
     * <p><b>格式是契约</b>：客户端靠结尾的 {@code [report:<id>]} 认出"这条可以点开回放"
     * （`game/social/ChatPanel.parseSharedReport`），显示时会把这一段摘掉。
     * 前缀可以随便改文案（对手名在后文），**但那一段标记必须原样保留** ——
     * 它是结构化引用，不是文案。
     */
    public static String shareText(BattleReport report) {
        return "分享了战报：" + report.opponentName() + " [report:" + report.reportId() + "]";
    }

    /** 这份战报对这名玩家是否可见：本人的，或分享到了他此刻所在的某个频道。 */
    private boolean visibleTo(String playerId, BattleReport report) {
        if (report.ownerId().equals(playerId)) {
            return true;
        }
        List<String> shared = store.sharedChannels(report.reportId());
        if (shared.isEmpty()) {
            return false;
        }
        for (String key : social.channelKeysOf(playerId)) {
            if (shared.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** 清理过期战报。惰性调用（列表与详情入口都会触发），不跑定时器。 */
    public int purgeExpired(long now) {
        int removed = store.purgeExpired(now);
        if (removed > 0) {
            LOG.info("清理过期战报 数量={} 截止={}", removed, now);
        }
        return removed;
    }

    /** 内核战果 → 协议视图。 */
    private BattleResultView toView(BattleReport report, long now) {
        BattleResult result = report.result();
        List<RoundView> rounds = new ArrayList<>(result.rounds().size());
        for (RoundSnapshot round : result.rounds()) {
            List<SkillTriggerView> skills = new ArrayList<>(round.skills().size());
            for (SkillTrigger trigger : round.skills()) {
                skills.add(new SkillTriggerView(
                        com.ironoath.web.dto.generated.SkillPhase.valueOf(trigger.phase().name()),
                        trigger.skillId(),
                        skillName(trigger.skillId()),
                        sideOf(trigger, report),
                        trigger.heroId(),
                        // 用 appliedFixed 而不是 valueFixed：HEAL 的实际回复量受「已损失兵力」上限约束，
                        // DEBUFF 也可能被乘区上限截断。回放要显示的是「实际发生了什么」，
                        // 显示名义值会让玩家算不出血量变化，进而怀疑回放是假的
                        trigger.appliedFixed()));
            }
            rounds.add(new RoundView(round.round(),
                    stacks(round.atkUnits()), stacks(round.defUnits()),
                    round.atkLoss(), round.defLoss(),
                    round.atkAttack(), round.defDefense(), round.attritionFixed(),
                    skills));
        }
        return new BattleResultView(
                BattleSide.valueOf(result.winner().name()),
                com.ironoath.web.dto.generated.BattleType.valueOf(report.battleType().name()),
                result.totalRounds(),
                rounds,
                stacks(result.atkSurvivors()),
                stacks(result.defSurvivors()),
                result.atkDead(), result.atkWounded(), result.atkOverflowDead(),
                result.defDead(), result.defWounded(), result.defOverflowDead(),
                lootOf(result.loot()),
                result.lootCapacity(),
                result.seed(),
                now);
    }

    /** 技能是哪一方触发的：靠 heroId 在哪一方的上阵名单里判定。 */
    private static String sideOf(SkillTrigger trigger, BattleReport report) {
        String heroId = trigger.heroId();
        if (com.ironoath.battle.BossMechanic.TRIGGER_SOURCE.equals(heroId)) {
            // BOSS 机制不是武将发出的，它永远属于守方。
            // 必须在「查上阵名单」之前拦下来，否则会掉进下面那个 IllegalStateException ——
            // 而这个异常会在玩家点开 BOSS 战报的那一刻抛出，战斗其实早就打完了
            return BattleSide.DEFENDER.name();
        }
        if (heroId != null && report.attackerHeroIds().contains(heroId)) {
            return BattleSide.ATTACKER.name();
        }
        if (heroId != null && report.defenderHeroIds().contains(heroId)) {
            return BattleSide.DEFENDER.name();
        }
        // 默认算攻方是错的：那会把守方的治疗显示成攻方的技能，
        // 而这种错误在回放里看起来完全合理，没人会发现，只会觉得数值对不上
        // 走到这里意味着内核新增了一种「非武将来源」的触发却没在这里登记
        throw new IllegalStateException("技能触发的来源 " + heroId + " 不在任何一方的上阵名单里，"
                + "无法判定归属。reportId=" + report.reportId()
                + ", 攻方=" + report.attackerHeroIds() + ", 守方=" + report.defenderHeroIds());
    }

    private String skillName(String skillId) {
        try {
            return configs.get(SkillCfg.class, skillId).name();
        } catch (ConfigException e) {
            // 内核能触发这个技能，说明它是从 skill 表来的；读不到只可能是表被热更删掉了。
            // 回退成 id 而不是抛异常：战报是历史记录，不该因为今天的表变了就打不开
            LOG.warn("技能 {} 在 skill 表里已不存在，战报里用 id 代替中文名", skillId);
            return skillId;
        }
    }

    /** 按 UnitType 声明顺序展开成列表：顺序确定，客户端才能稳定复用节点。 */
    private static List<UnitStack> stacks(Map<UnitType, Long> units) {
        List<UnitStack> out = new ArrayList<>(UnitType.values().length);
        for (UnitType type : UnitType.values()) {
            Long count = units.get(type);
            if (count != null && count > 0L) {
                out.add(new UnitStack(
                        com.ironoath.web.dto.generated.UnitType.valueOf(type.name()), count));
            }
        }
        return out;
    }

    private static List<LootEntry> lootOf(Map<String, Long> loot) {
        List<LootEntry> out = new ArrayList<>(loot.size());
        loot.forEach((resourceType, amount) -> {
            if (amount > 0L) {
                out.add(new LootEntry(resourceType, amount));
            }
        });
        return out;
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
    }
}
