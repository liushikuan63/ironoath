package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.DiplomacyRelation;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationDiplomacyReq;
import com.ironoath.web.dto.generated.NationDiplomacyResp;
import com.ironoath.web.dto.generated.NationDisbandReq;
import com.ironoath.web.dto.generated.NationDisbandResp;
import com.ironoath.web.dto.generated.NationRelationView;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationLeaveReq;
import com.ironoath.web.dto.generated.NationLeaveResp;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationResp;
import com.ironoath.web.dto.generated.NationTreasuryResp;
import com.ironoath.web.dto.generated.NationTreasurySpendReq;
import com.ironoath.web.dto.generated.NationTreasurySpendResp;
import com.ironoath.web.dto.generated.NationView;
import com.ironoath.web.dto.generated.TreasuryLogView;
import com.ironoath.web.dto.generated.TreasuryPayeeType;
import com.ironoath.web.dto.generated.TreasurySink;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：国家域应用服务 —— 建国、联盟入籍与退出国、解散国家、官职任命、查看与国库流水
 *       （B13 §1/§2/§3 与 §二冲突规则、验收 2/5）。
 * 依赖：{@link NationStore}、{@link NationRulesAssembler}、社交存储（联盟是国家的成员单位）、
 *       {@link BotRegistry}（合规红线）、玩家锁与幂等。
 *
 * <p><b>本类接通了哪一段</b>：建国、联盟入籍与退出国、解散国家、官职任命、周税结算、
 * 外交关系变更、查看本国与国库流水、<b>国库支出</b>（2026-09-11 的口径裁决：落点分"给玩家"与"给用途"两类）。
 * <b>还没有接通的</b>：国策投票、国战与领土。
 * （外交对战斗的影响已经接通：{@code Nation.mayAttackNation} 由 game-web 的统一攻击闸门
 * {@code AttackGuardService} 来问，见收口清单 #72 —— 改一次关系立刻改变谁能打谁。）
 * 刻意不把它们塞进来：一个半接通的国战比没有国战更危险，因为它会让玩家以为王城战能打到，
 * 而 B13 禁止项明写「不要在没有压测的情况下上线王城战」。
 *
 * <p><b>两条贯穿全类的口径</b>：
 * <ol>
 *   <li><b>联盟 ⊂ 国家</b>：成员表的最小单位是联盟，个人跟随联盟入籍。
 *       所以建国的发起单位是「发起人所在的联盟」，任命的对象必须属于某个成员联盟 ——
 *       「个人单独入籍」在本类里没有任何一条路径可以表达</li>
 *   <li><b>Bot 不得担任任何官职</b>（B13 §2 合规红线）。判定走
 *       {@link BotRegistry#requireMayHoldOffice}（本体是 {@code BotTuning.mayHoldOffice}）：
 *       调用方声明「这里是个国家官职」，由注册表决定怎么拒绝，
 *       于是身份这个问题只在一个地方被问、口径也只在一个地方被写</li>
 * </ol>
 */
@Service
public class NationAppService {

    private static final Logger LOG = LoggerFactory.getLogger(NationAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final NationStore nations;
    private final NationRulesAssembler assembler;
    private final SocialRulesAssembler socialRules;
    private final SocialStore socialStore;
    /** 议员席（派生席位）的注入器，与 {@code SocialAppService} 共用同一份实现。 */
    private final NationLeaders leaders;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    private final BotRegistry bots;
    /**
     * 俸禄发放走它，不直接改存档：容量上限、保护量、装不下转邮件补发都在发放器里
     * （B04 禁止项「奖励不得绕过发放器」）。与赛季结算注入的是同一个 bean。
     */
    private final RewardService rewardService;

    public NationAppService(NationStore nations, NationRulesAssembler assembler,
                            SocialRulesAssembler socialRules, SocialStore socialStore,
                            NationLeaders leaders,
                            PlayerRepository players, PlayerLock playerLock,
                            IdempotencyStore idempotency, TimeService timeService,
                            ConfigRegistry configs, BotRegistry bots,
                            RewardService rewardService) {
        this.nations = nations;
        this.assembler = assembler;
        this.socialRules = socialRules;
        this.socialStore = socialStore;
        this.leaders = leaders;
        this.players = players;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.configs = configs;
        this.bots = bots;
        this.rewardService = rewardService;
    }

    // ---------- 建国（B13 §1） ----------

    /**
     * 建国。发起人所在联盟整体入籍，发起人担任国王。
     *
     * <p>三条前置全部由服务端校验（主城 16 级 / 开服 D14 / 当前在联盟中），
     * 协议里没有任何「我已满足前置」的字段 —— 那会是客户端替服务端做决定。
     */
    public NationResp found(String playerId, NationFoundReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                PlayerSave save = requirePlayer(playerId);
                Alliance alliance = socialStore.allianceOf(playerId).orElseThrow(() -> new BizException(
                        ErrorCode.NATION_LOCKED,
                        "建国必须先在联盟中：国家成员的最小单位是联盟，个人不能单独入籍"));
                Nation.Rules rules = assembler.rules();
                // cooldownUntil 传 0：建国路径不涉及「联盟被开除后的 24h 入籍冷却」，
                // 那条冷却只在联盟整体退国/被开除时开始计，而这里是一个全新的国家
                String blocked = Nation.checkUnlock(save.cityLevel(), dayOffset(now), true,
                        0L, now, rules);
                if (blocked != null) {
                    throw new BizException(ErrorCode.NATION_LOCKED, blocked);
                }
                if (activeNations().size() >= rules.maxPerKingdom()) {
                    throw new BizException(ErrorCode.NATION_CREATE_LIMIT,
                            "本服最多 " + rules.maxPerKingdom() + " 个国家，当前已满");
                }
                String name = req.name() == null ? "" : req.name().trim();
                if (name.isEmpty()) {
                    throw new BizException(ErrorCode.NATION_NAME_TAKEN, "国名不得为空");
                }
                if (nations.findByName(name).isPresent()) {
                    throw new BizException(ErrorCode.NATION_NAME_TAKEN, "国名「" + name + "」已被占用");
                }
                if (nations.findByAlliance(alliance.id()).isPresent()) {
                    throw new BizException(ErrorCode.NATION_LOCKED, "你的联盟已经属于一个国家");
                }
                // bind 在 insert 之前：让存进去的第一份快照就与任何一次读出来的派生结果一致，
                // 否则"建国档里议员表为空、下次写入后非空"这种前后不一致只会被读日志的人当成两回事
                Nation nation = leaders.bind(Nation.found("nation_" + playerId, name, playerId, alliance.id(),
                        req.capitalX(), req.capitalY(), now, rules));
                if (!nations.insertIfAbsent(nation)) {
                    // 走到这里说明"按名字/按联盟查"与建档之间被插了一队
                    // （同一个玩家用两个不同 requestId 同时建国就是这种形状）。
                    // 复用 NATION_LOCKED（它的语义正是"建国条件未满足"），不新增一个只会多一处漏网码
                    throw new BizException(ErrorCode.NATION_LOCKED,
                            "建档时该国家已存在（并发建国）：nationId=" + nation.id());
                }
                // 结算必须排在 save 之后：settleWeeklyTax 改的是"已经在册"的那个对象，
                // 而建国时它还没进存储 —— 原先这里先结算再 save，那一笔第一周税静默为 0
                // （旧 settleTax 返回 void 所以没人发现），而建完国立刻看到的国库就是少一笔的
                Nation settled = settleTax(nation.id(), now);
                LOG.info("建国 nationId={} name={} 国王={} 发起联盟={} 都城=({},{}) 本服国家数={}",
                        nation.id(), name, playerId, alliance.id(), req.capitalX(), req.capitalY(),
                        activeNations().size());
                return new NationResp(toView(settled, playerId), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 入籍与退出国（B13 §二冲突规则、验收 2） ----------

    /**
     * 盟主代表全联盟加入一个国家。
     *
     * <p><b>这条路径存在的意义是"联盟 ⊂ 国家"真的能有多于一个联盟</b>：{@code Nation.admitAlliance}
     * 交付以来在生产里零调用点，于是国家永远只有建国的那一个联盟 —— §1 那句
     * 「200（2 联盟）→ 400（4 联盟）→ 800（8 联盟）」的容量梯度一次也没走通过，
     * 而 §2 的议员席（每盟主 1 席）恒为空。这条端点补上之前，那两条规则只是文档。
     *
     * <p><b>能不能加入、为什么不能，全部由领域层判</b>（{@code Nation.admitBlockFor}）：
     * 本方法只是把那份判定映射成错误码，不再自己算一遍冷却与名额（一个规则只能有一个家）。
     *
     * <p><b>顺序照 {@link #appoint}</b>：先 settleTax（它会推进版本）再重读、再改、再带版本 save。
     * 反过来就是"用手里那份旧副本盖掉刚入账的周税"。
     */
    public NationResp join(String playerId, NationJoinReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = socialStore.allianceOf(playerId).orElseThrow(() -> new BizException(
                        ErrorCode.NATION_LOCKED,
                        "你不在任何联盟中，而个人不能单独入籍（B13 禁止项）"));
                requireAllianceLeader(alliance, playerId);
                if (nations.findByAlliance(alliance.id()).isPresent()) {
                    // 与建国路径同一条事实、同一个码：这里如果另起一码，客户端要为同一句话写两份分支
                    throw new BizException(ErrorCode.NATION_LOCKED,
                            "你的联盟已经属于一个国家：要先退出，而退出国会带上入籍冷却");
                }
                requireNoJoinCooldownAnywhere(alliance.id(), now);
                Nation loaded = nations.findById(req.nationId())
                        .filter(n -> !n.isDisbanded()).orElseThrow(() -> new BizException(
                                ErrorCode.NATION_NOT_FOUND, "目标国家不存在或已解散: " + req.nationId()));
                Nation nation = settleTax(loaded.id(), now);
                Nation.AdmitBlock block = nation.admitBlockFor(alliance.id(), now).orElse(null);
                if (block != null) {
                    throw new BizException(toJoinFailure(block.reason()), block.message());
                }
                nation.admitAlliance(alliance.id(), now);
                nations.save(nation, nation.version());
                LOG.info("联盟入籍 nationId={} name={} allianceId={} 发起盟主={} 影响成员={} 成员联盟数={}",
                        nation.id(), nation.name(), alliance.id(), playerId,
                        alliance.memberIds().size(), nation.memberAllianceCount());
                return new NationResp(toView(nation, playerId), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 盟主代表全联盟退出所属国家：全盟失去国籍 + 该联盟进入入籍冷却（B13 验收 2）。
     *
     * <p><b>主动退出与被开除走的是同一个领域方法</b>（{@code Nation.removeAlliance}），
     * 差别只在 {@code expelled} 这个标志上 —— 它只用于日志与埋点区分两种离开方式，
     * 而<b>后果（冷却）完全一致</b>是刻意的：主动退出若无冷却，就可以「退出国 → 立刻加入敌国」，
     * 那会让国战变成一次换边游戏。
     *
     * <p><b>不回国家视图</b>：操作完成后这个人已经没有国籍，{@code GET /nation} 对他就是
     * NATION_NOT_FOUND。回一份他刚离开的那个国家的视图，等于让客户端刷新到一个它无权查询的对象上。
     */
    public NationLeaveResp leave(String playerId, NationLeaveReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = socialStore.allianceOf(playerId).orElseThrow(() -> new BizException(
                        ErrorCode.NATION_NOT_FOUND, "你不在任何联盟中，也就谈不上退出国家"));
                requireAllianceLeader(alliance, playerId);
                Nation loaded = nations.findByAlliance(alliance.id()).orElseThrow(() -> new BizException(
                        ErrorCode.NATION_NOT_FOUND, "你的联盟不属于任何国家"));
                Nation nation = settleTax(loaded.id(), now);
                nation.removeAlliance(alliance.id(), false, now);
                nations.save(nation, nation.version());
                long cooldownUntil = nation.joinCooldownUntil(alliance.id());
                LOG.info("联盟退出国 nationId={} allianceId={} 发起盟主={} 影响成员={} 可再入籍时刻={}（冷却来自 global.NATION_JOIN_COOLDOWN_HOURS）",
                        nation.id(), alliance.id(), playerId, alliance.memberIds().size(), cooldownUntil);
                return new NationLeaveResp(nation.id(), nation.name(), cooldownUntil, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 解散国家（B13 §1） ----------

    /**
     * 国王解散自己的国家：成员联盟全部进入入籍冷却、官职清空、国库余额核销并留日志。
     *
     * <p><b>按 kingId 找国，而不是按「他还不在那个国里」找</b>：解散权来自"你是这个国的国王"
     * （领域层判的就是 {@code operatorId == kingId}）。如果用 {@code requireNationOf}（按成员联盟反查），
     * 国王所在联盟先退出国的话，他会发现自己再也解散不了这个国 —— 那是把一条规则悄悄换成另一条。
     *
     * <p><b>亡国的记录留在库里</b>（{@code Snapshot.disbandedAt}）：国库与官职的变更历史是纠纷现场
     * （B13 §3「防贪污引发现实纠纷」），删档等于销毁证据。代价是本类所有「这个国还存在吗」的判断
     * 都要过 {@code isDisbanded}，这一处漏掉的表现不是报错而是"一个不存在的国家还占着名额/还在外交面板里"。
     */
    public NationDisbandResp disband(String playerId, NationDisbandReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation mine = activeNationOfKing(playerId).orElse(null);
                if (mine == null) {
                    // 两种「解散不了」要分开说：你压根没有国，和你的国有个别人是国王。
                    // 前者的下一步是去加入一个国家，后者的下一步是去找国王 —— 揉成一条提示两样都不指路。
                    boolean inAnyNation = socialStore.allianceOf(playerId)
                            .flatMap(alliance -> nations.findByAlliance(alliance.id())).isPresent();
                    throw new BizException(inAnyNation
                                    ? ErrorCode.SOCIAL_PERMISSION_DENIED : ErrorCode.NATION_NOT_FOUND,
                            inAnyNation ? "解散权只属于国王（B13 §2），你不是这个国的国王"
                                    : "没有以你为国王的国家：解散不是一个无国籍者能发起的操作");
                }
                // 与 appoint / join 同一条顺序：先结清周税再改，否则这一笔税会被手里那份旧副本盖掉
                Nation nation = settleTax(mine.id(), now);
                int memberCount = nation.memberAllianceCount();
                long writtenOff = nation.treasury();
                try {
                    nation.disband(playerId, now);
                } catch (IllegalStateException e) {
                    // 走到这里基本只可能是并发下国王已经换了人 —— 领域层那句原话直接进 detail
                    throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED, e.getMessage());
                }
                nations.save(nation, nation.version());
                LOG.info("解散国家 nationId={} name={} 国王={} 影响成员联盟={} 核销国库={}："
                                + "这些联盟全部进入入籍冷却（与主动退出、被开除同一条规则），官职一律清空",
                        nation.id(), nation.name(), playerId, memberCount, writtenOff);
                return new NationDisbandResp(nation.id(), nation.name(), memberCount, writtenOff, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 入籍冷却要<b>横着看所有国家</b>，这是服务层的职责而不是领域层的。
     *
     * <p>B13 验收 2 的原话是「联盟退出国家后，24h 内无法加入<b>任何</b>国家」，
     * 而 {@code Nation.joinCooldownUntil} 是<b>本国</b>的状态：谁记录的退出，冷却就记在谁身上。
     * 所以只看目标国那一格，就会出现「从东国退出来、当天就加入西国」——
     * 而国战的胜负恰恰取决于双方人数，这正是 global.NATION_JOIN_COOLDOWN_HOURS 那条 why 写明要防的换边套利。
     * 领域层把这件事交出来的原话记在 {@code NationSystemTest} 里：
     * 「冷却记录落在国家上，所以别的国家也要能查到它 —— 由 service 层统一裁决」。
     *
     * <p>判定与文案仍然取自领域层的 {@code admitBlockFor}（本方法只负责「查哪几国」这一件事，
     * 不重算冷却），所以剩余秒数不会与领域层算出两个值。国家数上限是
     * global.NATION_MAX_PER_KINGDOM（4），所以这不是一个大循环。
     *
     * <p><b>这里刻意不过滤已解散的国家</b>：{@code Nation.disband} 会把<b>所有</b>成员联盟写进
     * 入籍冷却，而亡国之后它的记录仍然留在库里。跳过亡国就等于给冷却开了一条绕道 ——
     * 「先把国灭掉、再立刻跳边」正是那条参数要防的形状。
     */
    private void requireNoJoinCooldownAnywhere(String allianceId, long now) {
        for (Nation other : nations.all()) {
            other.admitBlockFor(allianceId, now)
                    .filter(block -> block.reason() == Nation.AdmitRejection.COOLDOWN)
                    .ifPresent(block -> {
                        throw new BizException(ErrorCode.NATION_JOIN_COOLDOWN,
                                block.message() + "（冷却记在 nationId=" + other.id() + " 上，换国家没用）");
                    });
        }
    }

    /**
     * 「代表全盟」的身份判定。
     *
     * <p><b>这里不查 role_permission</b>：那张表里没有 JOIN_NATION / LEAVE_NATION 位，而且这份权限
     * 的来源也不是国家侧官职 —— 入籍那一刻他还不在那个国家里，任何该国官职都无从谈起。
     * 能改一个联盟整体国籍的人只有这个联盟的盟主（B13 §二：由盟主发起，全联盟加入）。
     * 哪天真要放开给干部代为申请，那是配置表加行的口径变更，不是在这里加一个 if。
     */
    private static void requireAllianceLeader(Alliance alliance, String playerId) {
        if (!alliance.leaderId().equals(playerId)) {
            throw new BizException(ErrorCode.ALLIANCE_NOT_LEADER,
                    "入籍与退出国改的是整个联盟的国籍，只有盟主能代表全盟做这个决定");
        }
    }

    /** 入籍被拒的原因 → 错误码。判定与文案都在 {@code Nation.admitBlockFor}，这里只做映射。 */
    private static ErrorCode toJoinFailure(Nation.AdmitRejection reason) {
        return switch (reason) {
            case COOLDOWN -> ErrorCode.NATION_JOIN_COOLDOWN;
            case FULL -> ErrorCode.NATION_ALLIANCE_FULL;
            // 已经是本国成员：与建国路径那句"你的联盟已经属于一个国家"是同一件事
            case ALREADY_MEMBER -> ErrorCode.NATION_LOCKED;
        };
    }

    // ---------- 官职任命（B13 §2，含合规红线） ----------

    /**
     * 任命官职。
     *
     * <p><b>权限走 role_permission 表</b>（{@code APPOINT_OFFICE}：只有国王档），
     * 不在代码里写「只有国王能任命」—— 那样新增一个能任命的官职就要改代码，
     * 而 B13 验收 3 要求「新增官职只改 role_permission.csv，代码零改动」。
     *
     * <p><b>Bot 合规红线在这里落地</b>：任命路径调
     * {@code bots.requireMayHoldOffice(targetId, "NATION", false, true, "国家官职")} ——
     * 判定本体只有一个家（{@code BotTuning.mayHoldOffice}，§六 的三级社交分布表），
     * 本行声明的只是"这是一个国家官职"（B13 §2、B16 上线清单 §七 4）。
     * 两个布尔恒为 {@code (false, true)}：国王与议员都<b>没有任命路径</b>
     * （{@code Nation.appoint} 各自当场拒绝），所以能走到这里的只有普通官职。
     *
     * <p><b>议员这一席不归本方法管</b>：它不是任命出来的，而是"每盟主 1 席"的派生席位，
     * 所以 Bot 盟主的合规判定长在 {@code NationLeaders} 注入的那份查询里（见那个类）。
     */
    public NationResp appoint(String playerId, NationAppointReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation loaded = requireNationOf(playerId);
                // 先结清周税、再在结算后的那份状态上继续：顺序反过来（改完再结算、然后 save
                // 手里那份旧的）会把刚入账的这周税整笔覆盖掉
                Nation nation = settleTax(loaded.id(), now);
                requirePermission(nation, playerId, "APPOINT_OFFICE");
                String targetId = req.playerId();
                // 合规红线：Bot 不得担任任何国家官职（判定住在 BotTuning.mayHoldOffice，只有一处）
                bots.requireMayHoldOffice(targetId, "NATION", false, true, "国家官职");
                Alliance targetAlliance = socialStore.allianceOf(targetId).orElseThrow(() -> new BizException(
                        ErrorCode.NATION_NOT_MEMBER, "被任命者不在任何联盟中，而个人不能脱离联盟单独入籍"));
                if (!nation.hasAlliance(targetAlliance.id())) {
                    throw new BizException(ErrorCode.NATION_NOT_MEMBER,
                            "被任命者的联盟「" + targetAlliance.name() + "」不属于本国");
                }
                Nation.Office office = toOffice(req.office());
                try {
                    nation.appoint(playerId, targetId, targetAlliance.id(), office);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.NATION_OFFICE_FULL, e.getMessage());
                }
                nations.save(nation, nation.version());
                LOG.info("任命官职 nationId={} 操作者={} 被任命者={} 官职={} 所属联盟={}",
                        nation.id(), playerId, targetId, office, targetAlliance.id());
                return new NationResp(toView(nation, playerId), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 外交（B13 §5） ----------

    /**
     * 变更与另一个国家的外交关系。
     *
     * <p><b>这不是一个装饰性的标签</b>：{@code Nation.mayAttackNation} 会读它，
     * 盟约之间不能互相攻击、敌对之间才可以，所以一次变更会立刻改变「谁能打谁」。
     * 因此日志必须留下完整审计（谁、何时、把与谁的关系从什么改成了什么）——
     * 一次误操作会变成一场无从追溯的战争。
     *
     * <p><b>单方面变更</b>：B13 §5 没有要求对方同意。要求双方确认会让「结盟」变成
     * 一次需要两人同时在线的操作，而那在小服里几乎凑不齐。
     */
    public NationDiplomacyResp diplomacy(String playerId, NationDiplomacyReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation nation = requireNationOf(playerId);
                requirePermission(nation, playerId, "MANAGE_DIPLOMACY");
                Nation target = nations.findById(req.targetNationId())
                        .filter(n -> !n.isDisbanded()).orElseThrow(() -> new BizException(
                                ErrorCode.NATION_NOT_FOUND, "目标国家不存在或已解散: " + req.targetNationId()));
                if (target.id().equals(nation.id())) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "不能与自己建立外交关系：与自己敌对会让 mayAttackNation 拒绝一切进攻，等于自废武功");
                }
                // 与 appoint 同一条顺序：先结清周税并在结算后的那份上继续，
                // 否则下面 save 会把刚入账的钱覆盖掉
                nation = settleTax(nation.id(), now);
                Nation.Diplomacy before = nation.diplomacyWith(target.id());
                Nation.Diplomacy relation = toDiplomacy(req.relation());
                nation.setDiplomacy(target.id(), relation);
                nations.save(nation, nation.version());
                LOG.info("外交关系变更 nationId={} 操作者={} 对方={}(id={}) {} => {}："
                                + "关系直接决定 mayAttackNation 与国战分组，这条日志是唯一的审计来源",
                        nation.id(), playerId, target.name(), target.id(), before, relation);
                return new NationDiplomacyResp(target.id(), req.relation(), relationsOf(nation), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 本国与其余全部国家的关系。
     *
     * <p>回整张表而不是只回变更的那一条：客户端的外交面板本来就要显示全部关系，
     * 只回一条会逼它再发一次查询 —— 而弱网下多一次往返就是多一次超时机会。
     */
    private List<NationRelationView> relationsOf(Nation nation) {
        List<NationRelationView> out = new ArrayList<>();
        for (Nation other : nations.all()) {
            if (other.id().equals(nation.id()) || other.isDisbanded()) {
                // 亡国的那份关系仍留在 map 里供审计，但不展示：列出来就是给玩家一个点了必然失败的条目
                continue;
            }
            out.add(new NationRelationView(other.id(), other.name(),
                    DiplomacyRelation.valueOf(nation.diplomacyWith(other.id()).name())));
        }
        return List.copyOf(out);
    }

    private static Nation.Diplomacy toDiplomacy(DiplomacyRelation relation) {
        if (relation == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "relation 不得为空");
        }
        try {
            return Nation.Diplomacy.valueOf(relation.name());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的外交关系: " + relation);
        }
    }

    // ---------- 查看 ----------

    /** 我的国家。不在任何国家里时回「不存在」—— 与战报同一条口径，不给探测用的语义。 */
    /**
     * 结算本周国库税，并<b>把结算之后的那份国家交回调用方</b>。
     *
     * <p>返回值得写成义务而不是可选项：存储层的 {@code settleWeeklyTax} 改的是在册对象，而读端口
     * 返回的是副本（两套实现都是），所以调用方手里那份<b>不会</b>跟着变。继续拿旧副本去
     * {@code save} 就等于把刚入账的这周税整笔覆盖掉 —— 国库少钱，且全链路一个错都不报。
     * 因此所有写路径的顺序必须是「{@code settleTax} → 用返回的那份改 → {@code save}」。
     */
    private Nation settleTax(String nationId, long now) {
        long credited = nations.settleWeeklyTax(nationId, now);
        if (credited > 0L) {
            LOG.info("国库周税入账 nationId={} 金额={} 本周键={}：税收是国战的经费来源，必须可追",
                    nationId, credited, com.ironoath.common.time.WeekKey.of(now));
        }
        return leaders.bind(nations.findById(nationId).orElseThrow(() -> new BizException(
                ErrorCode.NATION_NOT_FOUND, "国家不存在，无法结算周税: " + nationId)));
    }

    /**
     * 还活着的国家。
     *
     * <p><b>过滤长在服务层而不是存储层</b>：亡国的档是刻意留在库里的（{@code Snapshot.disbandedAt}，
     * 供国库与官职的争议复盘），存储层把它藏起来就等于把审计证据一起藏掉。
     * 代价是"这个国还算不算存在"这条判断在本类里必须显式出现 —— 名额计数、外交面板、
     * 入籍目标、按国王找国，四处都要过一遍，漏一处的表现不是报错而是一个亡国仍然占着位子。
     */
    private List<Nation> activeNations() {
        List<Nation> out = new ArrayList<>();
        for (Nation nation : nations.all()) {
            if (!nation.isDisbanded()) {
                out.add(nation);
            }
        }
        return out;
    }

    /** 以 {@code playerId} 为国王的、还活着的国家。解散权只认这个身份（B13 §2）。 */
    private java.util.Optional<Nation> activeNationOfKing(String playerId) {
        return activeNations().stream().filter(n -> playerId.equals(n.kingId())).findFirst();
    }

    public NationResp view(String playerId) {
        long now = timeService.serverNow();
        Nation mine = requireNationOf(playerId);
        // 看一眼国库就先把这一周的税结清：读到的数就该是当下的数，而不是"上次有人写操作时的数"。
        // 结算改的是在册对象，所以显示的那份必须是 settleTax 重读回来的那一份
        Nation settled = settleTax(mine.id(), now);
        return new NationResp(toView(settled, playerId), now);
    }

    /**
     * 国库流水（B13 §3、验收 5）。
     *
     * <p><b>「谁能读」就是 {@code requireNationOf} 那道成员关系判定，没有第二道门</b>：
     * role_permission 表里没有 VIEW_TREASURY 这一位，而这本账存在的理由恰恰是
     * 「公共资产的纠纷会溢出到现实」—— 只给国王看的日志等于把审计权交给被审计的那个人。
     * 所以门槛就是「你是这个国任一成员联盟的成员」；多加一道权限反而会让人以为
     * 「普通成员看不到国库」是设计意图。
     *
     * <p><b>读之前先结清周税</b>，与 {@link #view} 同一条理由：回给玩家的余额与流水必须互相自洽，
     * 而分两次查就会拿到两个时刻的数 ——「余额与流水对不上」正是这本账唯一要防的那种形状。
     */
    public NationTreasuryResp treasury(String playerId) {
        long now = timeService.serverNow();
        Nation mine = requireNationOf(playerId);
        Nation nation = settleTax(mine.id(), now);
        List<Nation.TreasuryLog> logs = nation.treasuryLogs();
        List<TreasuryLogView> rows = new ArrayList<>(logs.size());
        // 倒序：面板要的是最近几笔，而领域层按时间升序存（那样追加与"丢最旧的"都只动一头）
        for (int i = logs.size() - 1; i >= 0; i--) {
            Nation.TreasuryLog log = logs.get(i);
            rows.add(new TreasuryLogView(log.at(), log.operatorId(), log.payee(),
                    log.amount(), log.reason(), log.balanceAfter()));
        }
        return new NationTreasuryResp(nation.treasury(), List.copyOf(rows), now);
    }

    /**
     * 国库支出（B13 §3）。**落点分两类**（2026-09-11 的口径裁决）：
     * 发给某个玩家（俸禄，扣账之后走发放器发 GOLD），或由某个消耗性用途核销（国家科技 / 国战增益，
     * 不入任何个人账户）。一个自由字符串的支给对象被否掉了 —— 见 {@link Nation.Payee}。
     *
     * <p><b>权限走 role_permission 表的 WITHDRAW_TREASURY</b>：那张表 `allowLeader=true`、
     * 其余两档 `false`，why 写明「国库支取涉及全国资源，只给国主」。B13 §2 给首相写了
     * 「国库支出（限额）」，而那个限额**没有数值** —— 本轮以权限表为准（首相暂不可支取）；
     * 要放开就先给限额定数并改表，不在代码里编一个上限。
     *
     * <p><b>顺序：先扣账 + 写日志（领域层同一步完成），再发放</b>。失败方向选「记了没发出去」：
     * 那一笔有补偿队列与客服入口，反过来（发了没记）没有 —— 与赛季结算、支付域同一条取舍。
     *
     * <p><b>不做部分出账</b>：余额不足整笔拒绝（{@code NATION_TREASURY_NOT_ENOUGH}）。
     */
    public NationTreasurySpendResp spendTreasury(String playerId, NationTreasurySpendReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation loaded = requireNationOf(playerId);
                Nation nation = settleTax(loaded.id(), now);
                requirePermission(nation, playerId, "WITHDRAW_TREASURY");
                if (req.amount() <= 0L) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "支出额必须为正，实际=" + req.amount());
                }
                if (req.reason() == null || req.reason().isBlank()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "用途不得为空：没有「为什么」的日志等于没有日志");
                }
                Nation.Payee payee = toPayee(req);
                nation.setClock(now);
                try {
                    nation.spend(playerId, payee, req.amount(), req.reason());
                } catch (IllegalStateException e) {
                    // 余额不足：整笔拒绝而不是部分出账（见错误码的注释）
                    throw new BizException(ErrorCode.NATION_TREASURY_NOT_ENOUGH, e.getMessage());
                }
                nations.save(nation, nation.version());
                // 落库之后才发钱：记账是这笔支出"发生过"的凭据，而发放在它之后。
                // 发放失败进补偿队列（发放器自己保证），此时日志与余额都已落地 —— 那笔钱有据可查
                if (payee.kind() == Nation.Payee.Kind.PLAYER) {
                    grantSalary(payee.target(), req.amount(), nation.id(), req.requestId());
                }
                List<Nation.TreasuryLog> logs = nation.treasuryLogs();
                Nation.TreasuryLog written = logs.get(logs.size() - 1);
                LOG.info("国库支出 nationId={} 操作者={} 落点={} 金额={} 用途={} 余额={}",
                        nation.id(), playerId, payee.text(), req.amount(), req.reason(), nation.treasury());
                return new NationTreasurySpendResp(nation.id(), nation.treasury(), payee.text(),
                        req.amount(), req.reason(),
                        new TreasuryLogView(written.at(), written.operatorId(), written.payee(),
                                written.amount(), written.reason(), written.balanceAfter()),
                        now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 把请求翻译成落点。<b>payeeId 与 sink 只允许出现一个</b>：都给或都不给都拒绝 ——
     * 「含糊的支给对象」正是这张日志最怕的东西（写着一个玩家、用途里却记着科技）。
     */
    private Nation.Payee toPayee(NationTreasurySpendReq req) {
        TreasuryPayeeType type = req.payeeType();
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "payeeType 不得为空");
        }
        boolean hasPlayer = req.payeeId() != null && !req.payeeId().isBlank();
        boolean hasSink = req.sink() != null;
        if (hasPlayer == hasSink) {
            throw new BizException(ErrorCode.PARAM_INVALID, type == TreasuryPayeeType.PLAYER
                    ? "payeeType=PLAYER 时必须且只能给 payeeId"
                    : "payeeType=SINK 时必须且只能给 sink");
        }
        if (type == TreasuryPayeeType.PLAYER) {
            if (players.findByPlayerId(req.payeeId()).isEmpty()) {
                // 记在一个不存在的 id 上等于这笔钱没有收款人，而日志却写着有
                throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "收款玩家不存在：" + req.payeeId());
            }
            return Nation.Payee.toPlayer(req.payeeId());
        }
        return Nation.Payee.toSink(toSink(req.sink()));
    }

    /**
     * 协议枚举 → 领域枚举。<b>两种取值一一对应</b>（由 {@code NationPayEnumParityTest} 钉住）：
     * 两处各声明一份是协议与领域分层的必然结果，而"名字一样"这件事必须有守卫，
     * 否则某天改一个名字就会变成运行期的 500（那是玩家点一次俸禄看到的错误）。
     */
    private static Nation.Payee.Sink toSink(TreasurySink sink) {
        return Nation.Payee.Sink.valueOf(sink.name());
    }

    /**
     * 俸禄发放：走 {@link RewardService} 而不是直接改存档 —— 容量上限、保护量、
     * 装不下时转邮件补发都在发放器里（B04 禁止项「奖励不得绕过发放器」）。
     *
     * <p>金币与国库资金<b>1:1</b>：国库目前只有一个资金口径（周税按 {@code NATION_TAX_WEEKLY_PER_ALLIANCE}
     * 入账），与 GOLD 同单位划拨，不引入汇率 —— 要给国库资金单独定价就先在配置表里给它一个家。
     */
    private void grantSalary(String payeeId, long amount, String nationId, String requestId) {
        var grant = rewardService.grant(payeeId,
                List.of(new RewardItem(RewardType.RESOURCE, ResourceIds.GOLD, amount)),
                RewardContext.toMail("nation_treasury", nationId, nationId + ":" + requestId));
        if (grant.hasCompensation()) {
            LOG.error("【国库俸禄未入账已进补偿队列】playerId={} nationId={} 金额={} compensationId={}",
                    payeeId, nationId, amount, grant.compensationId());
        }
    }

    // ---------- 内部 ----------

    private Nation requireNationOf(String playerId) {
        Alliance alliance = socialStore.allianceOf(playerId).orElseThrow(() -> new BizException(
                ErrorCode.NATION_NOT_FOUND, "你不在任何联盟中，而国籍跟随联盟"));
        return leaders.bind(nations.findByAlliance(alliance.id()).orElseThrow(() -> new BizException(
                ErrorCode.NATION_NOT_FOUND, "你的联盟还没有加入任何国家")));
    }

    /** 官职 → 权限档位。这是<b>结构映射</b>（哪一档官职），具体能不能做由 role_permission 表决定。 */
    private void requirePermission(Nation nation, String playerId, String permission) {
        Nation.Office office = nation.officeOf(playerId);
        PermissionMatrix.Tier tier = office == null ? PermissionMatrix.Tier.MEMBER : switch (office) {
            case KING -> PermissionMatrix.Tier.LEADER;
            case PRIME_MINISTER, GENERAL, MINISTER, DIPLOMAT -> PermissionMatrix.Tier.OFFICER;
            case REPRESENTATIVE -> PermissionMatrix.Tier.MEMBER;
        };
        if (!socialRules.permissions().allows(PermissionMatrix.Scope.NATION, tier, permission)) {
            // msg 不写「需要什么官职」：那等于在代码里硬编码一份权限（B10/B13 禁止项）。
            // 缺哪个权限位放进 detail，那里是查表得出的
            throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED,
                    "scope=NATION office=" + office + " 缺少权限位 " + permission);
        }
    }

    private static Nation.Office toOffice(NationOffice office) {
        if (office == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "office 不得为空");
        }
        try {
            return Nation.Office.valueOf(office.name());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知官职: " + office);
        }
    }

    private NationView toView(Nation nation, String viewerId) {
        Nation.Office mine = nation.officeOf(viewerId);
        return new NationView(nation.id(), nation.name(), nation.kingId(), nation.level(),
                nation.memberAllianceCount(), nation.memberCap(), nation.capitalX(), nation.capitalY(),
                nation.treasury(), nation.treasuryCap(),
                mine == null ? null : mine.name(),
                timeService.serverNow());
    }

    /**
     * 开服天数。唯一实现在 {@link ServerCalendar}（本类与 {@code SocialAppService} 原先各有一份
     * 逐字拷贝，是记录在案的欠账）。未配置 {@code SERVER_OPEN_AT} 时放行，理由见 ServerCalendar。
     */
    private long dayOffset(long now) {
        return ServerCalendar.daysSinceOpen(configs, now);
    }

    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "国家域的写操作必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private PlayerSave requirePlayer(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow(() -> new BizException(
                ErrorCode.PLAYER_NOT_FOUND, "玩家存档不存在: " + playerId));
    }
}
