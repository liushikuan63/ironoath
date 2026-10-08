package com.ironoath.web.service;

import com.ironoath.core.resource.ResourceIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.AllianceRole;
import com.ironoath.core.social.ChatRateLimiter;
import com.ironoath.core.social.HelpLedger;
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.core.social.Squad;
import com.ironoath.core.social.SquadRole;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceDonateResp;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceMember;
import com.ironoath.web.dto.generated.AllianceApplicationListResp;
import com.ironoath.web.dto.generated.AllianceDiscoveryView;
import com.ironoath.web.dto.generated.ApplicantView;
import com.ironoath.web.dto.generated.AllianceListResp;
import com.ironoath.web.dto.generated.AllianceMemberReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceRoleReq;
import com.ironoath.web.dto.generated.AllianceSelfReq;
import com.ironoath.web.dto.generated.AllianceTechReq;
import com.ironoath.web.dto.generated.AllianceTechResp;
import com.ironoath.web.dto.generated.AllianceView;
import com.ironoath.web.dto.generated.BlockListView;
import com.ironoath.web.dto.generated.BlockedPlayerView;
import com.ironoath.web.dto.generated.FollowReq;
import com.ironoath.web.dto.generated.FriendListView;
import com.ironoath.web.dto.generated.FriendView;
import com.ironoath.web.dto.generated.OpsReportRecentResp;
import com.ironoath.web.dto.generated.OpsReportRow;
import com.ironoath.web.dto.generated.ReportReason;
import com.ironoath.web.dto.generated.BlockReq;
import com.ironoath.web.dto.generated.ReportReq;
import com.ironoath.web.dto.generated.ReportResp;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.HelpRequestView;
import com.ironoath.web.dto.generated.HelpTargetKind;
import com.ironoath.web.dto.generated.SocialCreatePolicy;
import com.ironoath.web.dto.generated.SocialCreatePolicyResp;
import com.ironoath.web.dto.generated.SocialHelpListResp;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatListResp;
import com.ironoath.web.dto.generated.ChatMessageView;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.ChatSendResp;
import com.ironoath.web.dto.generated.HelpResp;
import com.ironoath.web.dto.generated.HelpTargetKind;
import com.ironoath.web.dto.generated.PermissionListResp;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.core.social.Rally;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ironoath.web.dto.generated.AllianceRallyReq;
import com.ironoath.web.dto.generated.NationRallyReq;
import com.ironoath.web.dto.generated.RallyHeroSlotState;
import com.ironoath.web.dto.generated.RallyHeroSlotView;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyListResp;
import com.ironoath.web.dto.generated.RallyResp;
import com.ironoath.web.dto.generated.RallyScope;
import com.ironoath.web.dto.generated.RallyStatus;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.RallyView;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.dto.generated.SquadRallyReq;
import com.ironoath.web.dto.generated.SocialEventView;
import com.ironoath.web.dto.generated.SocialSummaryResp;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.RallyPolicyResp;
import com.ironoath.web.dto.generated.RallyPolicyView;
import com.ironoath.web.dto.generated.SquadDiscoveryView;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadListResp;
import com.ironoath.web.dto.generated.SquadMember;
import com.ironoath.web.dto.generated.SquadMemberReq;
import com.ironoath.web.dto.generated.SquadSelfReq;
import com.ironoath.web.dto.generated.SquadView;
import com.ironoath.web.social.SocialEventIds;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B10 社交域的应用服务 —— 小队与联盟的创建/加入/审核/捐献、互助帮助、聊天、事件补偿。
 * 依赖：{@link SocialStore}、{@link SocialRulesAssembler}、玩家仓储、玩家锁、幂等、时间服务。
 *
 * <p><b>本类不持有任何数值</b>（铁律 1）：人数上限、捐献档位、帮助额度、限流窗口全部来自
 * {@link SocialRulesAssembler}，而它只读配置表。本类做的是编排：加锁、幂等、
 * 调 game-core 的领域对象、把领域状态翻译成 DTO、写事件与聊天。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li><b>跨玩家的操作要在锁内串行</b>。踢人、审核、帮助都会同时改两个人的状态，
 *       玩家级锁挡不住这种竞争 —— 内存存储自己用一把粗锁兜住（见 SocialStore 的说明）</li>
 *   <li><b>幂等键先占后做</b>。创建联盟会扣金币、捐献会扣资源、帮助会扣额度，
 *       没有幂等就等于允许重放刷奖励（B00 陷阱 3）</li>
 *   <li><b>失败要把「差什么」说出来</b>。人数满了就说上限是多少，权限不够就带上缺的权限位 ——
 *       笼统的「条件不足」会让玩家去猜，而他猜不到就会认为游戏在骗他（B03 §2 的同一条纪律）</li>
 * </ol>
 *
 * <p><b>ChatRateLimiter 与 HelpLedger 是单例状态，不随配置热更</b>：
 * 它们持有滑动窗口与每日额度的累计值，重建就等于把所有人的限流记录与帮助次数清零。
 * 所以这两个对象在构造期装配一次；改了 global 里的限流参数需要重启才生效。
 * 这是有意的取舍 —— 社交规则的改动频率远低于战斗数值，而「重启才生效」比「限流记录凭空消失」安全。
 */
@Service
public class SocialAppService {

    private static final Logger LOG = LoggerFactory.getLogger(SocialAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;
    /** 事件的可响应窗口。唯一来源在存储上（求助登记器也要用同一个数）。 */
    private static final long EVENT_TTL_MILLIS = SocialStore.EVENT_TTL_MILLIS;

    private final SocialStore store;
    private final SocialRulesAssembler rules;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    private final com.ironoath.web.reward.PlayerWallet wallet;
    private final com.ironoath.web.ws.SocialPushPublisher pushPublisher;
    private final com.ironoath.core.army.ArmyRepository armies;
    private final AttackGuardService attackGuard;
    /** 联盟 ⊂ 国家：联盟解散时要把它从所属国家的成员表里摘掉（B13 §二冲突规则）。 */
    private final com.ironoath.web.nation.NationStore nations;
    /** 与 NationAppService 共用同一份议员席注入规则。 */
    private final com.ironoath.web.nation.NationLeaders nationLeaders;
    /**
     * 国家花名册的唯一口径（玩家 → 国家、国家 → 成员）。
     *
     * <p>国家集结的人数上限要数「本国此刻实有人数」，而这个数在别处已经有唯一一个实现
     * （{@code NationMembership.playerIdsOf}，国战发奖与击杀归属都走它）。在本类里再写一遍
     * 盟→人的展开，就会出现"发奖用的国人数与集结上限用的国人数不是同一个口径"——
     * 那正是 {@code alliance_tech.json:5} 记的那起「一个数字两个家」事故的形状。
     */
    private final com.ironoath.web.nation.NationMembership membership;
    /** 集结随军武将的归属与编队上限校验（与个人出征同一个入口）。 */
    private final HeroAppService heroAppService;
    /**
     * 城建仓储。帮助加速要落到盟友的建筑上（B03 §3），所以这里直接用它，
     * <b>而不是注入 {@code CityAppService}</b>：那个服务反过来要为"升级开始"调用本服务登记求助请求，
     * 两条服务间依赖会成环，而仓储是叶子。
     */
    private final com.ironoath.core.city.CityRepository cities;
    private final ChatRateLimiter chatLimiter;
    private final HelpLedger helpLedger;
    /** 求助请求的登记器（叶子依赖，供城建/军队域直接调用而不成环）。 */
    private final com.ironoath.web.social.HelpRequestRegistrar helpRequests;
    /** 任务进度的事件入口（B12 §1）：互助与集结都是"做人做到位"的目标，由本服务上报。 */
    private final com.ironoath.web.quest.QuestEvents questEvents;
    /**
     * 事件触发的聊天（B11 §四 RALLY_CALL）：发起集结时喊一句「来人」。
     *
     * <p>发布的是「谁发起了集结」这件事，订阅与说话由 {@code BotEventChatListener} 负责 ——
     * 本服务不认识任何 Bot 类型，也不该认识（它在上游，Bot 适配器在它下游）。
     */
    private final org.springframework.context.ApplicationEventPublisher events;
    /** 内容安全：小队名/联盟名/聊天三处玩家可自由填写的内容都要送检（上线检查清单 §二 7）。 */
    private final com.ironoath.web.security.ContentSecurityGuard contentSecurity;
    /** 关注列表的"在线"来自它（WS 网关的在线快照，见 {@link #follows}）。 */
    private final com.ironoath.web.ws.PushGateway pushGateway;

    public SocialAppService(SocialStore store, SocialRulesAssembler rules, PlayerRepository players,
                            PlayerLock playerLock, IdempotencyStore idempotency, TimeService timeService,
                            ConfigRegistry configs,
                            com.ironoath.web.reward.PlayerWallet wallet,
                            com.ironoath.web.ws.SocialPushPublisher pushPublisher,
                            com.ironoath.core.army.ArmyRepository armies,
                            AttackGuardService attackGuard,
                            com.ironoath.web.nation.NationStore nations,
                            com.ironoath.web.nation.NationLeaders nationLeaders,
                            com.ironoath.web.nation.NationMembership membership,
                            com.ironoath.core.city.CityRepository cities,
                            com.ironoath.web.social.HelpRequestRegistrar helpRequests,
                            HeroAppService heroAppService,
                            com.ironoath.web.quest.QuestEvents questEvents,
                            org.springframework.context.ApplicationEventPublisher events,
                            com.ironoath.web.security.ContentSecurityGuard contentSecurity,
                            com.ironoath.web.ws.PushGateway pushGateway) {
        this.store = store;
        this.rules = rules;
        this.players = players;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.configs = configs;
        this.wallet = wallet;
        this.pushPublisher = pushPublisher;
        this.armies = armies;
        this.attackGuard = attackGuard;
        this.nations = nations;
        this.nationLeaders = nationLeaders;
        this.membership = membership;
        this.cities = cities;
        this.helpRequests = helpRequests;
        this.heroAppService = heroAppService;
        this.questEvents = questEvents;
        this.events = events;
        this.contentSecurity = contentSecurity;
        this.pushGateway = pushGateway;
        this.chatLimiter = new ChatRateLimiter(rules.chatRules());
        this.helpLedger = new HelpLedger(rules.helpRules());
    }

    // ================= 小队 =================

    /** 创建小队。发起人即队长，前置是主城等级与开服天数（B10 §1）。 */
    public SocialSummaryResp squadCreate(String playerId, SquadCreateReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                PlayerSave save = requirePlayer(playerId);
                CreateBlock blocked = squadCreateBlock(playerId, save, now);
                if (blocked != null) {
                    throw new BizException(blocked.code(), blocked.reason());
                }
                String name = req.name() == null ? "" : req.name().trim();
                if (name.isEmpty()) {
                    throw new BizException(ErrorCode.SQUAD_NAME_INVALID, "小队名不得为空");
                }
                contentSecurity.requireClean(playerId,
                        com.ironoath.web.security.ContentSecurityClient.Scene.PROFILE,
                        name, ErrorCode.SQUAD_NAME_INVALID, "小队名");
                if (store.squadNameTaken(name)) {
                    throw new BizException(ErrorCode.SQUAD_NAME_TAKEN, "小队名「" + name + "」已被占用");
                }
                Squad squad = Squad.create("squad_" + playerId, name, playerId, rules.squadRules());
                // 建档：expectedVersion=0 表示"我认为它还不存在"；库里已有同 id 才是冲突
                store.saveSquad(squad, 0L);
                LOG.info("创建小队 playerId={} squadId={} name={} 人数上限={}",
                        playerId, squad.id(), name, squad.memberCap(save.cityLevel()));
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 加入小队。人数上限由 {@link Squad#memberCap} 按「小队等级 + 队长主城等级」两个条件算。 */
    public SocialSummaryResp squadJoin(String playerId, SquadIdReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                if (store.squadOf(playerId).isPresent()) {
                    throw new BizException(ErrorCode.SQUAD_ALREADY_IN, "你已经在一个小队里");
                }
                Squad squad = store.squadById(req.squadId())
                        .orElseThrow(() -> new BizException(ErrorCode.SQUAD_NOT_FOUND, "squadId=" + req.squadId()));
                long expectedSquadVersion = squad.version();
                int leaderCityLevel = cityLevelOf(squad.leaderId());
                try {
                    squad.join(playerId, leaderCityLevel);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.SQUAD_FULL, e.getMessage());
                }
                store.saveSquad(squad, expectedSquadVersion);
                pushToSquad(squad, playerId, "SQUAD_JOINED",
                        nickname(playerId) + " 加入了小队", null, now);
                LOG.info("加入小队 playerId={} squadId={} 当前人数={}", playerId, squad.id(), squad.memberCount());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * GET /squad/list（B26 S7）：可加入小队的前 N 个。
     *
     * <p>这一格与 {@link #allianceList} 是同一个缺陷的第二处：{@code /squad/join} 早就有，
     * 但客户端连"世界上有哪些小队"都读不到 ⇒ 没有小队的玩家那一屏只有一句「未加入小队」，
     * 除了自己建一支没有别的路走。小队比联盟更缺这一口：<b>加入小队不要审核、也不要金币</b>，
     * 它是新手最容易走进去的组织，而它恰好是最难被看见的。
     *
     * <p>我自己已经在的那一支<b>不列</b>（{@code total} 仍是全服未解散数，它是"世界上有多少支"的口径）：
     * 一颗点了只会回 SQUAD_ALREADY_IN 的按钮，比没有那颗按钮更糟。
     *
     * <p>{@code memberCap} 按<b>队长</b>主城等级现算（{@link Squad#memberCap} 的口径，
     * 传给错的人会让人数上限永远停在 5），{@code full} 就用同一个数比 ——
     * 于是"界面上亮着的那一行点下去一定不会被拒"是这里算出来的，不是客户端猜的。
     */
    public SquadListResp squadList(String playerId, long now) {
        int limit = (int) configs.longParam("SQUAD_LIST_LIMIT");
        List<Squad> all = new ArrayList<>(store.allSquads());
        all.sort(java.util.Comparator.comparingInt(Squad::level).reversed()
                .thenComparing(java.util.Comparator.comparingInt(Squad::memberCount).reversed()));
        List<SquadDiscoveryView> rows = new ArrayList<>();
        for (Squad squad : all) {
            if (rows.size() >= limit) {
                break;
            }
            if (squad.isMember(playerId)) {
                // 自己已经在的那一支不列：加入那一枪会回 SQUAD_ALREADY_IN，
                // 而界面上一颗点了只会吃错的按钮比没有按钮更糟（客户端只是不画，权威在这里）
                continue;
            }
            int cap = squad.memberCap(cityLevelOf(squad.leaderId()));
            rows.add(new SquadDiscoveryView(squad.id(), squad.name(), squad.level(),
                    squad.memberCount(), cap, squad.memberCount() >= cap));
        }
        return new SquadListResp(rows, all.size(), limit, now);
    }

    /** 退出小队。队长不能直接退（先转让或解散），否则小队会剩下没有责任人的成员。 */
    public SocialSummaryResp squadLeave(String playerId, SquadSelfReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Squad squad = requireSquad(playerId);
                long expectedSquadVersion = squad.version();
                try {
                    squad.leave(playerId);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.SQUAD_NOT_LEADER, e.getMessage());
                }
                store.saveSquad(squad, expectedSquadVersion);
                store.unbindSquadMember(squad.id(), playerId);
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 踢人。权限位 KICK_MEMBER 由 PermissionMatrix 裁决（验收 4：不在代码里硬编码）。 */
    public SocialSummaryResp squadKick(String playerId, SquadMemberReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Squad squad = requireSquad(playerId);
                long expectedSquadVersion = squad.version();
                // 权限位取 role_permission 表的 permission 列（START_RALLY），不是行 id
                // （perm_squad_start_rally）—— 用行 id 的话查表永远查不到，而症状是
                // 「连队长都发起不了集结」，看起来像权限表配错了
                requirePermission(PermissionMatrix.Scope.SQUAD, squad.roleOf(playerId), "KICK_MEMBER");
                try {
                    squad.kick(playerId, req.memberId());
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.SQUAD_NOT_MEMBER, e.getMessage());
                }
                store.saveSquad(squad, expectedSquadVersion);
                store.unbindSquadMember(squad.id(), req.memberId());
                // 被踢的人必须收到通知：不通知的话他只会在下次打开面板时发现小队没了，
                // 而那会被理解成 bug 或者被背叛（B10 验收 2 的同一条纪律）
                store.pushEvent(req.memberId(), event("SQUAD_KICKED", "你已被移出小队「" + squad.name() + "」",
                        null, null, now));
                LOG.info("小队踢人 operator={} squadId={} target={}", playerId, squad.id(), req.memberId());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ================= 联盟 =================

    /** 创建联盟。消耗金币，且解散保护期内不能再建（验收 7）。 */
    public SocialSummaryResp allianceCreate(String playerId,
                                            com.ironoath.web.dto.generated.AllianceCreateReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                PlayerSave save = requirePlayer(playerId);
                Alliance.Rules allianceRules = rules.allianceRules();
                CreateBlock blocked = allianceCreateBlock(playerId, save, now);
                if (blocked != null) {
                    throw new BizException(blocked.code(), blocked.reason());
                }
                String name = trim(req.name());
                String tag = trim(req.tag());
                if (name.isEmpty() || tag.isEmpty()) {
                    throw new BizException(ErrorCode.ALLIANCE_NAME_INVALID, "联盟名与标签都不得为空");
                }
                contentSecurity.requireClean(playerId,
                        com.ironoath.web.security.ContentSecurityClient.Scene.PROFILE,
                        name, ErrorCode.ALLIANCE_NAME_INVALID, "联盟名");
                contentSecurity.requireClean(playerId,
                        com.ironoath.web.security.ContentSecurityClient.Scene.PROFILE,
                        tag, ErrorCode.ALLIANCE_NAME_INVALID, "联盟标签");
                if (store.allianceNameTaken(name)) {
                    throw new BizException(ErrorCode.ALLIANCE_NAME_TAKEN, "联盟名「" + name + "」已被占用");
                }
                if (store.allianceTagTaken(tag)) {
                    throw new BizException(ErrorCode.ALLIANCE_NAME_TAKEN, "联盟标签「" + tag + "」已被占用");
                }
                long cost = Alliance.createCost(allianceRules);
                long balance = balanceOf(playerId, GOLD_RESOURCE_ID, now);
                CreateBlock costLack = costLack(cost, balance);
                if (costLack != null) {
                    throw new BizException(costLack.code(), costLack.reason());
                }
                // 先扣款再建盟：反过来的话建盟失败会留下一笔已扣的钱，
                // 或者建盟成功而钱没扣 —— 后者等于白送一个联盟
                if (!spend(playerId, GOLD_RESOURCE_ID, cost, now, "创建联盟 " + name)) {
                    throw new BizException(ErrorCode.ALLIANCE_CREATE_COST_LACK,
                            "扣款失败：需要金币 " + cost + "，检查与扣款之间余额被其它路径改动了");
                }
                Alliance alliance;
                try {
                    // 传扣款**前**的余额：Alliance.create 自己会校验「够不够」，
                    // 传扣款后的值会让它的校验反过来否掉这次合法的创建
                    alliance = Alliance.create("alliance_" + playerId, name, tag, playerId,
                            balance, allianceRules);
                } catch (IllegalStateException e) {
                    refund(playerId, GOLD_RESOURCE_ID, cost, now, "创建联盟失败退款 " + name);
                    throw new BizException(ErrorCode.ALLIANCE_CREATE_COST_LACK, e.getMessage());
                }
                // 建档：expectedVersion=0 表示"我认为它还不存在"
                store.saveAlliance(alliance, 0L);
                // 入盟时小队自动转为联盟内分队，功能全部保留（关键设计点 1、验收 1）
                store.squadOf(playerId).ifPresent(squad -> {
                    long expectedSquadVersion = squad.version();
                    squad.attachToAlliance(alliance.id());
                    store.saveSquad(squad, expectedSquadVersion);
                });
                LOG.info("创建联盟 playerId={} allianceId={} name={} tag={} 扣金币={} 人数上限={}",
                        playerId, alliance.id(), name, tag, cost, alliance.effectiveMemberCap());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * GET /alliance/list（B26 S6）：可申请联盟的**前 N 个**。
     *
     * <p>这一格的存在理由：`/alliance/apply` 早就有，但客户端连"有哪些联盟可申"都读不到 ⇒
     * 没有联盟的玩家只能自己花 500 金建一个，或者根本不知道联盟玩法能进。
     *
     * <p>排序按等级降序、同级人数降序（玩家挑的就是"哪家打得动、哪家还有位置"）。
     * 条数上限是 global.ALLIANCE_LIST_LIMIT：响应必须有界，这一版不做翻页，
     * 但把 total 一起下发，界面才写得出「共 X 个，只显示前 Y 个」而不是假装这就是全部。
     */
    public AllianceListResp allianceList(String playerId, long now) {
        int limit = (int) configs.longParam("ALLIANCE_LIST_LIMIT");
        List<Alliance> all = new ArrayList<>(store.allAlliances());
        all.sort(java.util.Comparator.comparingInt(Alliance::level).reversed()
                .thenComparing(java.util.Comparator.comparingInt(Alliance::memberCount).reversed()));
        List<AllianceDiscoveryView> rows = new ArrayList<>();
        for (Alliance alliance : all) {
            if (rows.size() >= limit) {
                break;
            }
            if (alliance.isMember(playerId)) {
                // 与 squadList 同一条（B26 S7 一起扫出来的同族）：我已经在的那个盟不列，
                // 否则那一行写着「申请加入」而点下去必然被拒
                continue;
            }
            rows.add(new AllianceDiscoveryView(alliance.id(), alliance.name(), alliance.tag(),
                    alliance.level(), alliance.memberCount(), alliance.effectiveMemberCap(),
                    // 满不满、申请过没有都由服务端算：客户端自己比会漏掉"队长刚扩过容"这种只有这里有账的口径
                    alliance.memberCount() >= alliance.effectiveMemberCap(),
                    store.hasApplication(alliance.id(), playerId)));
        }
        return new AllianceListResp(rows, all.size(), limit, now);
    }

    /** 申请入盟。 */
    public SocialSummaryResp allianceApply(String playerId, AllianceIdReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                if (store.allianceOf(playerId).isPresent()) {
                    throw new BizException(ErrorCode.ALLIANCE_ALREADY_IN, "你已经在联盟里");
                }
                Alliance alliance = requireAlliance(req.allianceId());
                if (store.hasApplication(alliance.id(), playerId)) {
                    throw new BizException(ErrorCode.ALLIANCE_APPLY_DUPLICATED, "申请已提交，等待审核");
                }
                if (alliance.memberCount() >= alliance.effectiveMemberCap()) {
                    throw new BizException(ErrorCode.ALLIANCE_FULL,
                            "联盟人数已满（上限 " + alliance.effectiveMemberCap() + " 人）");
                }
                store.addApplication(alliance.id(), playerId);
                // 审核请求要通知到有权限的人，否则申请会一直挂着（B10 禁止项：绝不静默失败）
                // 申请人的昵称在循环外读一次：它原来落在下面这个循环里，于是乘数 = 有权限的官员数，
                // 而每一次读的都是同一个人的整份存档（判据见 SamePlayerRepeatReadQueryCountTest#applicantSaveIsReadOnceNotOncePerNotifiedOfficer）
                String applicantName = nickname(playerId);
                for (String officer : alliance.memberIds()) {
                    AllianceRole role = alliance.roleOf(officer);
                    if (role != null && role.tier() != AllianceRole.MEMBER.tier()) {
                        store.pushEvent(officer, event("ALLIANCE_APPLIED",
                                applicantName + " 申请加入联盟", null, alliance.id(), now));
                    }
                }
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 审核入盟申请。拒绝也要显式调用 —— 只是不处理会让申请者永远不知道自己被忽略了。 */
    /**
     * GET /alliance/applications（B26 S8）：本盟待审申请的前 N 条。
     *
     * <p>这一格是"有写口没发现口"那一族的第三处：{@code /alliance/review} 早就有，
     * 而存储端口只问得出「这个人申过没有」与「一共几条」，问不出「申的是谁」⇒
     * 那颗批准按钮在任何客户端上都按不下去，而申请的人以为盟主已经看见了。
     *
     * <p><b>只对能审核的人开</b>：这条下发的是别人的身份（昵称 + 主城等级），
     * 判据与写口用同一个权限码 {@code APPROVE_APPLICATION} —— 读口比写口松，
     * 等于任何成员都能把申请名单当花名册读。
     */
    public AllianceApplicationListResp allianceApplications(String playerId, long now) {
        Alliance alliance = requireAllianceOf(playerId);
        requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId),
                "APPROVE_APPLICATION");
        int limit = (int) configs.longParam("ALLIANCE_APPLICATION_LIST_LIMIT");
        List<String> applicantIds = store.applicantsOf(alliance.id());
        List<String> page = applicantIds.subList(0, Math.min(limit, applicantIds.size()));
        // 一页申请人的昵称与城等来自**同一次批量读**：改之前 nickname(...) 与 cityLevelOf(...) 各自
        // 点查一次，于是打开这一屏要发 2 × min(上限, 申请数) 趟整份存档读取（上限实测 50 ⇒ 最坏 100 趟）。
        // 判据是往返计数：AllianceApplicationQueryCountTest。名单为空时连这一趟都不发。
        // 走投影口：这一屏只点昵称与主城等级两列，剩下整档（资源表 / PVP 账本 / 科技 …）是白搬白反序列化
        Map<String, PlayerBrief> briefs = page.isEmpty() ? Map.of() : players.findBriefs(page);
        List<ApplicantView> rows = new ArrayList<>(page.size());
        for (String applicantId : page) {
            // 昵称与城等都走服务端那一份：客户端没有玩家表，拿 id 猜出来的名字就是第二真源
            PlayerBrief brief = briefs.get(applicantId);
            // 读不到档的两条兜底与改之前那两个助手逐字同口径（昵称回 id、城等回 0）：
            // "申请还挂着但人已消失"是删号后的正常状态，客户端要靠它画出一行而不是少一行
            rows.add(new ApplicantView(applicantId,
                    brief == null ? applicantId : brief.nickName(),
                    brief == null ? 0 : brief.cityLevel()));
        }
        return new AllianceApplicationListResp(rows, applicantIds.size(), limit, now);
    }
    public SocialSummaryResp allianceReview(String playerId, AllianceReviewReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "APPROVE_APPLICATION");
                if (!store.hasApplication(alliance.id(), req.applicantId())) {
                    throw new BizException(ErrorCode.ALLIANCE_APPLY_NOT_FOUND,
                            "applicantId=" + req.applicantId());
                }
                store.removeApplication(alliance.id(), req.applicantId());
                if (!req.approve()) {
                    store.pushEvent(req.applicantId(), event("ALLIANCE_REJECTED",
                            "联盟「" + alliance.name() + "」拒绝了你的申请", null, alliance.id(), now));
                    return summary(playerId, now);
                }
                if (store.allianceOf(req.applicantId()).isPresent()) {
                    throw new BizException(ErrorCode.ALLIANCE_ALREADY_IN, "对方已经加入了别的联盟");
                }
                try {
                    alliance.join(req.applicantId());
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.ALLIANCE_FULL, e.getMessage());
                }
                store.saveAlliance(alliance, expectedAllianceVersion);
                // 关键设计点 1：入盟时小队转为分队，功能全部保留
                store.squadOf(req.applicantId()).ifPresent(squad -> {
                    long expectedSquadVersion = squad.version();
                    squad.attachToAlliance(alliance.id());
                    store.saveSquad(squad, expectedSquadVersion);
                });
                store.pushEvent(req.applicantId(), event("ALLIANCE_JOINED",
                        "你已加入联盟「" + alliance.name() + "」", null, alliance.id(), now));
                LOG.info("审核通过 allianceId={} reviewer={} applicant={} 当前人数={}",
                        alliance.id(), playerId, req.applicantId(), alliance.memberCount());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 转让队长。领域方法 {@code Squad#transferLeadership} 早就写好了（含"只有队长能转"
     * 与"对方必须是成员"两条不变量），缺的只是这一层接法 —— 客户端早就绑了
     * {@code /squad/transfer}，也就是说那个按钮此前一点就 404。
     *
     * <p>权限位取 role_permission 表的 <b>permission 列</b>（{@code TRANSFER_LEADER}），
     * 不是行 id（{@code perm_squad_transfer_leader}）—— 用行 id 时查表永远查不到，
     * 症状是"连队长都转不了"，看起来像表配错了。
     */
    public SocialSummaryResp squadTransfer(String playerId, SquadMemberReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Squad squad = requireSquad(playerId);
                long expectedSquadVersion = squad.version();
                requirePermission(PermissionMatrix.Scope.SQUAD, squad.roleOf(playerId), "TRANSFER_LEADER");
                try {
                    squad.transferLeadership(playerId, req.memberId());
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.SQUAD_NOT_MEMBER, e.getMessage());
                }
                store.saveSquad(squad, expectedSquadVersion);
                store.pushEvent(req.memberId(), event("SQUAD_LEADER_CHANGED",
                        "你已成为小队「" + squad.name() + "」的队长", null, null, now));
                LOG.info("小队转让队长 operator={} squadId={} 新队长={}", playerId, squad.id(), req.memberId());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 解散小队（B10 验收 2 那条责任链的<b>主动</b>那一半）。
     *
     * <p><b>这一格缺了很久而四处都看不出缺</b>：{@code Squad.disband} 早就在领域里、两套存储的
     * {@code unbindSquadMember} 都写了"小队已解散就把文档删掉"（{@code #onMemberLeftAlliance} 在用）、
     * {@code role_permission} 有 {@code DISBAND_SQUAD} 行、契约枚举有 {@code SQUAD_DISBANDED}。
     * 少的是这个入口，症状是队长<b>既解散不了小队也退不出去</b>（{@code leave} 拒绝队长），
     * 只能先把队长转让给某人 —— 而那等于凭空多一次"换队长"。
     *
     * <p>成员名单必须在 {@code disband} <b>之前</b>取走：那一步会 {@code members.clear()}，
     * 之后再问"影响了谁"只能拿到空表，而解散恰恰是最需要通知其他人的那种操作。
     */
    public SocialSummaryResp squadDisband(String playerId, SquadSelfReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Squad squad = requireSquad(playerId);
                long expectedSquadVersion = squad.version();
                // 队长一人决定小队存亡，队员不投票（perm_squad_disband 的 why：
                // 5~10 人的熟人圈子里投票，输的那一方会直接退队）
                requirePermission(PermissionMatrix.Scope.SQUAD, squad.roleOf(playerId), "DISBAND_SQUAD");
                List<String> members = squad.memberIds();
                try {
                    squad.disband(now);
                } catch (IllegalStateException e) {
                    // 只剩"已经解散"这一种失败，而那个状态在这里到不了：
                    // requireSquad 读不到已解散的队（文档已被删），SQUAD_NOT_FOUND 的文案正覆盖这件事
                    throw new BizException(ErrorCode.SQUAD_NOT_FOUND, e.getMessage());
                }
                store.saveSquad(squad, expectedSquadVersion);
                // 逐个清反查索引：解散把成员一次清空，漏掉谁的索引，他打开面板就"还在队里"，
                // 而他再点任何小队操作都会打到一支已经不存在的队上
                for (String member : members) {
                    store.unbindSquadMember(squad.id(), member);
                }
                for (String member : members) {
                    if (member.equals(playerId)) {
                        continue;
                    }
                    store.pushEvent(member, event("SQUAD_DISBANDED",
                            "小队「" + squad.name() + "」已被队长解散",
                            "你可以创建或加入一个独立小队", null, now));
                }
                LOG.info("解散小队 squadId={} operator={} 影响成员={}",
                        squad.id(), playerId, members.size());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 扩容人数上限（B10 验收 3）。扣的是联盟资金，档位与价格全在 {@code Alliance.Rules} 里，
     * 而"检查 + 扣款 + 抬档"在联盟自己的监视器内原子完成 —— 玩家锁只挡同一个人并发，
     * 挡不住两个官员同时扩同一座联盟。
     *
     * <p>领域只抛 {@code IllegalStateException}，直接冒出去会变成 500，而"资金不足"是玩家
     * 每天都在触发的正常失败，所以在这一层按文案映射成明确错误码。匹配不到时回
     * {@code SYSTEM_ERROR} 而不是硬编一个"资金不足" —— 那会把别的故障说成钱的问题。
     */
    public SocialSummaryResp allianceExpand(String playerId, AllianceSelfReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                // 权限位是 permission 列的 EXPAND_CAPACITY，不是行 id perm_alliance_expand
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "EXPAND_CAPACITY");
                Alliance.Expansion expansion;
                try {
                    expansion = alliance.expand();
                } catch (IllegalStateException e) {
                    String message = String.valueOf(e.getMessage());
                    // 领域层的三种失败都只能靠文案区分，所以这里按文案分流：
                    // 与其让领域去猜错误码（那会把 web 的码表拖进 core），不如在这层映射。
                    // 匹配不到时回 SYSTEM_ERROR 而不是硬编一个"资金不足"—— 那会把别的故障说成钱的问题
                    if (message.contains("资金不足")) {
                        throw new BizException(ErrorCode.ALLIANCE_FUND_LACK, message);
                    }
                    if (message.contains("联盟等级不足")) {
                        throw new BizException(ErrorCode.ALLIANCE_LEVEL_MAX, message);
                    }
                    if (message.contains("最高档位")) {
                        throw new BizException(ErrorCode.ALLIANCE_LEVEL_MAX, message);
                    }
                    throw new BizException(ErrorCode.SYSTEM_ERROR, message);
                }
                store.saveAlliance(alliance, expectedAllianceVersion);
                LOG.info("联盟扩容 operator={} allianceId={} 消耗资金={} 余额={} 等级={} 人数上限={}",
                        playerId, alliance.id(), expansion.cost(), expansion.fund(),
                        expansion.level(), expansion.memberCap());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 踢出成员。权限位 {@code KICK_MEMBER} 走 role_permission 表（国王与官员档可踢）。
     *
     * <p>被踢的人必须收到通知：组织身份的变化如果只能自己发现，
     * 表现就是「我明明在联盟里怎么捐献不见了」，而那次困惑会直接变成客服工单。
     */
    public SocialSummaryResp allianceKick(String playerId, AllianceMemberReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "KICK_MEMBER");
                // 不另加一份 requireText：空或非法的 memberId 本来就不是成员，
                // 下面那条 ALLIANCE_NOT_MEMBER 是更一致的答案（控制器里已有三份私有实现，够了）
                String target = req.memberId();
                if (!alliance.isMember(target)) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_MEMBER, "memberId=" + target);
                }
                if (target.equals(alliance.leaderId())) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "不能踢盟主：要换人请走 /alliance/transfer");
                }
                alliance.kick(playerId, target);
                store.saveAlliance(alliance, expectedAllianceVersion);
                // 与 allianceLeave 同一套收尾：成员索引不解的话，被踢的人之后每次
                // 读 /social/summary 都会指回这个已经没有他位置的联盟
                store.unbindAllianceMember(alliance.id(), target);
                store.pushEvent(target, event("ALLIANCE_KICKED",
                        "你已被联盟「" + alliance.name() + "」移出", null, alliance.id(), now));
                LOG.info("联盟踢人 allianceId={} 操作者={} 职位={} 被踢={} 剩余人数={}："
                        + "组织变动必须留完整审计，纠纷时这是唯一的事实来源",
                        alliance.id(), playerId, alliance.roleOf(playerId), target, alliance.memberCount());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 转让盟主。只有盟主能做（域里也挡一层，这里是给出正确错误码的那一层）。 */
    public SocialSummaryResp allianceTransfer(String playerId, AllianceMemberReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                // 不另加一份 requireText：空或非法的 memberId 本来就不是成员，
                // 下面那条 ALLIANCE_NOT_MEMBER 是更一致的答案（控制器里已有三份私有实现，够了）
                String target = req.memberId();
                if (!playerId.equals(alliance.leaderId())) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_LEADER, "转让盟主只有盟主能发起");
                }
                if (target.equals(playerId)) {
                    throw new BizException(ErrorCode.ALLIANCE_TRANSFER_TO_SELF,
                            "把盟主转给自己等于没转，却会留下一条「已转让」的审计记录");
                }
                if (!alliance.isMember(target)) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_MEMBER, "memberId=" + target);
                }
                alliance.transferLeadership(playerId, target);
                store.saveAlliance(alliance, expectedAllianceVersion);
                store.pushEvent(target, event("ALLIANCE_TRANSFERRED",
                        "你已成为联盟「" + alliance.name() + "」的盟主", null, alliance.id(), now));
                LOG.info("盟主转让 allianceId={} 原盟主={} 新盟主={}：权力交接必须留痕",
                        alliance.id(), playerId, target);
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 任命职位（副盟主 / 长老 / 普通成员）。
     *
     * <p><b>role_permission 表里没有「任命」这一位</b>（B10 只给了 KICK_MEMBER 等行），
     * 所以这里靠域内的等级规则兜底：<b>不能任命不低于自己的职位</b>，否则副盟主能造出一个
     * 新盟主。缺的那一行已记入收口清单 —— 权限走表而不是走代码，是本项目反复立的规矩。
     */
    public SocialSummaryResp allianceSetRole(String playerId, AllianceRoleReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                // 不另加一份 requireText：空或非法的 memberId 本来就不是成员，
                // 下面那条 ALLIANCE_NOT_MEMBER 是更一致的答案（控制器里已有三份私有实现，够了）
                String target = req.memberId();
                // 表里先判「你有没有资格开这个口」（B26 S11 新增 SET_ROLE 位）：原来只靠域内那条
                // "不能任命高于自己"，于是普通成员也看得见「设职」那颗按钮，点下去拿到一条
                // SOCIAL_PERMISSION_DENIED —— 看得见却永远点不动，正是这一串格子在修的东西
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "SET_ROLE");
                if (req.role() == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "role 不得为空");
                }
                if (!alliance.isMember(target)) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_MEMBER, "memberId=" + target);
                }
                com.ironoath.core.social.AllianceRole newRole =
                        com.ironoath.core.social.AllianceRole.valueOf(req.role().name());
                try {
                    alliance.setRole(playerId, target, newRole);
                } catch (IllegalStateException e) {
                    // 域内的规则全是权限问题（任命低于自己、不能降级盟主），统一翻成权限码
                    throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED, e.getMessage());
                }
                store.saveAlliance(alliance, expectedAllianceVersion);
                store.pushEvent(target, event("ALLIANCE_ROLE_SET",
                        "你在联盟「" + alliance.name() + "」的职位变为 " + newRole.displayName(),
                        null, alliance.id(), now));
                LOG.info("联盟任命 allianceId={} 操作者={} 职位={} 目标={} 新职位={}：审计留痕",
                        alliance.id(), playerId, alliance.roleOf(playerId), target, req.role());
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 退出联盟。盟主必须先转让或解散。
     *
     * <p><b>验收 2 的落点在这里</b>：退盟者若是分队的队长，小队自动解散并通知队员。
     * 理由是队长是分队与联盟之间唯一的连接点 —— 他走了这个分队就没有责任人，
     * 让队员自动选一个新队长等于替他们做了一个组织决定（谁当队长是熟人圈子里最敏感的事）。
     *
     * <p>退盟者只是普通队员时，<b>只把他从分队里摘掉，小队完整保留</b>（关键设计点 1）：
     * 剩下的人仍然是彼此的兄弟，不该因为一个人退盟就散伙。
     */
    public SocialSummaryResp allianceLeave(String playerId,
                                           com.ironoath.web.dto.generated.AllianceSelfReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                try {
                    alliance.leave(playerId);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_LEADER, e.getMessage());
                }
                store.saveAlliance(alliance, expectedAllianceVersion);
                store.unbindAllianceMember(alliance.id(), playerId);
                onMemberLeftAlliance(playerId, alliance.id(), now);
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 某人离开联盟后，同步他所在分队的状态。
     *
     * <p>解散与摘人两条分支的区别就是验收 2 的全部内容，所以集中在一处：
     * 散在两个方法里的话，将来加「转让队长后退盟」这类分支时一定会漏掉一条。
     */
    private void onMemberLeftAlliance(String playerId, String allianceId, long now) {
        Squad squad = store.squadOf(playerId).orElse(null);
        if (squad == null || !allianceId.equals(squad.allianceId())) {
            return;
        }
        long expectedSquadVersion = squad.version();
        if (!squad.leaderId().equals(playerId)) {
            // 普通队员退盟：只摘掉他，小队保留
            squad.memberLeavesAlliance(playerId);
            store.saveSquad(squad, expectedSquadVersion);
            store.unbindSquadMember(squad.id(), playerId);
            return;
        }
        List<String> toNotify;
        try {
            toNotify = squad.leaderLeavesAlliance(now);
        } catch (IllegalStateException e) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND, e.getMessage());
        }
        store.saveSquad(squad, expectedSquadVersion);
        for (String member : toNotify) {
            store.unbindSquadMember(squad.id(), member);
            store.pushEvent(member, event("SQUAD_DISBANDED",
                    "小队「" + squad.name() + "」已解散：队长退出了联盟",
                    "你可以创建或加入一个独立小队", null, now));
        }
        LOG.info("队长退盟导致小队解散 squadId={} 队长={} 通知队员={}", squad.id(), playerId, toNotify.size());
    }

    /** 解散联盟。解散者进入保护期（验收 7）。 */
    public SocialSummaryResp allianceDisband(String playerId,
                                             com.ironoath.web.dto.generated.AllianceSelfReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "DISBAND_ALLIANCE");
                // 排第一，且在联盟侧任何写操作之前：国家那一次带版本写如果撞了锁，整次解散就该失败，
                // 而不是留下"联盟已经没了、国家的成员表里还挂着它"这种半状态（幽灵席位白占一个名额）
                detachFromNation(alliance.id(), alliance.memberIds(), playerId, now);
                List<String> members = alliance.memberIds();
                try {
                    alliance.disband(playerId, now);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.ALLIANCE_NOT_LEADER, e.getMessage());
                }
                long protectedUntil = rules.allianceRules().disbandProtectUntil(now);
                // 保护期落在解散者头上：让干部也能解散的话，就没人承担这个冷却
                store.protectFromCreating(playerId, protectedUntil);
                store.removeAlliance(alliance);
                for (String member : members) {
                    store.unbindAllianceMember(alliance.id(), member);
                    detachSquad(member, alliance.id());
                    if (!member.equals(playerId)) {
                        store.pushEvent(member, event("ALLIANCE_DISBANDED",
                                "联盟「" + alliance.name() + "」已被盟主解散", null, null, now));
                    }
                }
                LOG.info("解散联盟 allianceId={} operator={} 影响成员={} 保护期至={}",
                        alliance.id(), playerId, members.size(), protectedUntil);
                return summary(playerId, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 捐献（验收 8：联盟资金与个人贡献值同步增加）。
     *
     * <p>先扣款再入账：顺序反了的话，扣款失败会留下一笔白给的资金，
     * 而资金是公共资产，多出来的那部分谁也说不清是哪来的。
     */
    public AllianceDonateResp allianceDonate(String playerId, AllianceDonateReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                // 这一格查的是 perm_alliance_donate（三档都给 ⇒ 今天拦不住任何人）。
                // 既然现在拦不住，为什么还要查：**这张表是权限的唯一来源**，不查它的后果是
                // "哪天把 allowMember 改成 false，捐献照旧对所有人开放"，改表的人完全看不出没生效。
                // 放在取档位与查钱之前：没权限的人不该先被告知"你金币不够"
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "DONATE");
                Alliance.DonateTier tier = Alliance.donateTierOf(alliance.rules(), req.tier());
                String reason = "联盟捐献 档位" + req.tier();
                long goldBalance = balanceOf(playerId, GOLD_RESOURCE_ID, now);
                if (tier.costGold() > 0 && goldBalance < tier.costGold()) {
                    throw new BizException(ErrorCode.ALLIANCE_DONATE_COST_LACK,
                            "需要金币 " + tier.costGold() + "，当前 " + goldBalance);
                }
                if (tier.costResourceType() != null) {
                    long have = balanceOf(playerId, tier.costResourceType(), now);
                    if (have < tier.costResourceAmount()) {
                        throw new BizException(ErrorCode.ALLIANCE_DONATE_COST_LACK,
                                "需要 " + tier.costResourceType() + " " + tier.costResourceAmount()
                                        + "，当前 " + have);
                    }
                }
                // **先扣款再入账**（本方法的注释就是这么写的，而骨架期的实现顺序恰好相反）：
                // 反过来一旦扣款失败，联盟资金里就多出一笔谁也说不清是哪来的钱，
                // 而资金是公共资产，对不上账的公共资产最终会变成玩家之间的现实纠纷
                boolean goldTaken = tier.costGold() <= 0
                        || spend(playerId, GOLD_RESOURCE_ID, tier.costGold(), now, reason);
                if (!goldTaken) {
                    throw new BizException(ErrorCode.ALLIANCE_DONATE_COST_LACK,
                            "扣款失败：需要金币 " + tier.costGold() + "，检查与扣款之间余额被其它路径改动了");
                }
                boolean resourceTaken = tier.costResourceType() == null
                        || spend(playerId, tier.costResourceType(), tier.costResourceAmount(), now, reason);
                if (!resourceTaken) {
                    refund(playerId, GOLD_RESOURCE_ID, tier.costGold(), now, reason + " 资源扣款失败退款");
                    throw new BizException(ErrorCode.ALLIANCE_DONATE_COST_LACK,
                            "扣款失败：需要 " + tier.costResourceType() + " " + tier.costResourceAmount()
                                    + "，检查与扣款之间余额被其它路径改动了");
                }
                Alliance.Donation donation;
                try {
                    donation = alliance.donate(playerId, req.tier(), DayKey.of(now));
                } catch (IllegalStateException e) {
                    // 入账失败必须退款：钱已经扣了而捐献没记上，那是最糟的一种失败
                    refund(playerId, GOLD_RESOURCE_ID, tier.costGold(), now, reason + " 捐献失败退款");
                    if (tier.costResourceType() != null) {
                        refund(playerId, tier.costResourceType(), tier.costResourceAmount(), now,
                                reason + " 捐献失败退款");
                    }
                    throw new BizException(ErrorCode.ALLIANCE_DONATE_DAILY_LIMIT, e.getMessage());
                }
                alliance.addExp(tier.expGained());
                store.saveAlliance(alliance, expectedAllianceVersion);
                LOG.info("联盟捐献 playerId={} allianceId={} tier={} 资金+{} 贡献+{} 余额={}",
                        playerId, alliance.id(), req.tier(), donation.fundGained(),
                        donation.contributionGained(), donation.fund());
                // 活动进度（B17 捐献周）：增量 = 这次真进联盟资金的那份资源量。
                // 发完事件再返回 —— 事件在写路径内同步派发，失败由发布入口收口成日志，
                // 所以"捐献成功但活动没记上"至多是少一格进度，不会让这笔捐献失败
                questEvents.progress(playerId, com.ironoath.core.quest.GoalType.ALLIANCE_DONATE,
                        null, donation.fundGained(), now);
                return new AllianceDonateResp(donation.fundGained(), donation.contributionGained(),
                        donation.fund(), donation.contribution(), donation.donateToday(),
                        donation.dailyCap(), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 研究联盟科技（B10 §2：用联盟资金研究、全盟生效、等级上限随联盟等级）。
     *
     * <p><b>权限走 {@code role_permission} 的 {@code RESEARCH_TECH} 行</b>，不在代码里写「只有盟主能」：
     * 联盟里谁能花公账本来就是一张可以随职位调整的表，写死之后改表不生效也不报错。
     *
     * <p><b>上限与资金先预检再交给领域方法</b>，因为两种失败要回的错误码不同
     * （{@code ALLIANCE_TECH_LEVEL_MAX} / {@code ALLIANCE_FUND_LACK}）。预检用的价格与扣款用的是
     * 同一个 {@code Alliance.researchCost}，不会出现"预检说够、扣款说不够"；而真正的
     * 「检查 + 扣款 + 抬等级」在 {@code Alliance} 的监视器里原子完成（那是联盟级共享资产，
     * 本类的锁只是玩家级的）。预检之后仍被领域拒绝的情况只剩一种：两个官员真的同时扣，
     * 这时按「请重试」处理，钱一分没动。
     */
    public AllianceTechResp allianceTech(String playerId, AllianceTechReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId), "RESEARCH_TECH");
                if (req.levels() < 1) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "一次研究的等级数必须 >= 1，实际=" + req.levels());
                }
                com.ironoath.config.cfg.AllianceTechCfg tech;
                try {
                    tech = configs.get(com.ironoath.config.cfg.AllianceTechCfg.class, req.techId());
                } catch (com.ironoath.config.ConfigException missing) {
                    // 表里没有这一行 ⇒ 必须是业务码，不能是 500。两种到达方式：手滑写错 id，
                    // 以及**已退役的科技**（atech_rally / atech_help，2026-09-13 从 v1 删到 v2，
                    // 见收口清单 §三·补 B10/B11）—— 老客户端拿着旧 id 来研究时，
                    // 500 会让人以为服务器坏了（然后重试、报障），而事实只有一句「这一项不存在」。
                    throw new BizException(ErrorCode.CONFIG_NOT_FOUND,
                            "联盟科技表里没有这一行: " + req.techId());
                }
                int tableMax = (int) tech.maxLevel();
                int current = alliance.techLevel(tech.id());
                int cap = alliance.techLevelCap(tableMax);
                if (current >= cap) {
                    throw new BizException(ErrorCode.ALLIANCE_TECH_LEVEL_MAX,
                            tech.name() + " 在联盟 " + alliance.level() + " 级下最高 " + cap + " 级");
                }
                if (current + req.levels() > cap) {
                    throw new BizException(ErrorCode.ALLIANCE_TECH_LEVEL_MAX,
                            "本次最多只能研究 " + (cap - current) + " 级（当前 " + current
                                    + " 级，上限 " + cap + " 级）");
                }
                long cost = alliance.researchCost(tech.costBaseDonation(), tech.id(), req.levels());
                if (alliance.fund() < cost) {
                    throw new BizException(ErrorCode.ALLIANCE_FUND_LACK,
                            "研究 " + req.levels() + " 级需要 " + cost + "，联盟资金当前 "
                                    + alliance.fund());
                }
                Alliance.Research research;
                try {
                    research = alliance.researchTech(tech.id(), tech.costBaseDonation(), tableMax,
                            tech.effectValue(), req.levels());
                } catch (IllegalStateException race) {
                    // 走到这里只剩一种可能：预检与扣款之间，别的官员把资金或等级改动了。
                    // 领域方法没执行成功，所以钱一分没扣 —— 提示重试而不是编一个「资金不足」，
                    // 因为玩家看到的那个数可能是对的
                    throw new BizException(ErrorCode.SYSTEM_ERROR,
                            "联盟内有其它研究同时进行，本次未执行，请重试：" + race.getMessage());
                }
                store.saveAlliance(alliance, expectedAllianceVersion);
                LOG.info("联盟科技研究 playerId={} allianceId={} tech={} 研究{}级 → {}级 上限={} 资金-{} 余额={}"
                                + " 累计效果={}",
                        playerId, alliance.id(), tech.id(), req.levels(), research.level(),
                        research.levelCap(), research.fundCost(), research.fundAfter(),
                        research.effectFixed());
                return new AllianceTechResp(research.techId(), research.level(), research.levelCap(),
                        research.fundCost(), research.fundAfter(), research.effectFixed(), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ================= 汇总 / 权限 =================

    /** GET /social/summary。三层社交一屏给全，红点数由服务端算好（验收 6）。 */
    public SocialSummaryResp summary(String playerId, long now) {
        List<SocialStore.SocialEvent> unread = store.unreadEvents(playerId);
        List<SocialEventView> events = new ArrayList<>(unread.size());
        for (SocialStore.SocialEvent record : unread) {
            events.add(toEventView(record, now));
        }
        return new SocialSummaryResp(
                store.squadOf(playerId).map(squad -> toSquadView(squad, playerId, now)).orElse(null),
                store.allianceOf(playerId).map(alliance -> toAllianceView(alliance, playerId, now)).orElse(null),
                // 我的国家 id（B13 接入后不再是恒 null）。**只给 id 不给视图**：国家那一屏走
                // `GET /nation`（那里有等级、国库、官职），这里只回答"我有没有国籍"，
                // 免得同一份国家数据有第二个家
                store.allianceOf(playerId)
                        .flatMap(alliance -> nations.findByAlliance(alliance.id()))
                        .filter(nation -> !nation.isDisbanded())
                        .map(com.ironoath.core.nation.Nation::id)
                        .orElse(null),
                pendingInvitesOf(playerId),
                pendingHelpsOf(playerId, now),
                helpLedger.remainingToday(playerId, now),
                events,
                now);
    }

    /**
     * GET /social/createPolicy（B26 S2）：创建小队/联盟之前那几道门的**结论**。
     *
     * <p>客户端拿不到 squad_config/alliance_config 的表，也不该拿：门槛是"主城几级、开服第几天"，
     * 消耗是 global 里的金币数额，客户端抄一份就是第二真相 —— 表一改，界面会写着「还差 2 级」
     * 而服务端其实已经放行（反过来会把能点的人挡在门外）。所以结论与那句给人看的原因一起下发。
     */
    public SocialCreatePolicyResp createPolicy(String playerId, long now) {
        PlayerSave save = requirePlayer(playerId);
        long cost = Alliance.createCost(rules.allianceRules());
        CreateBlock allianceBlocked = allianceCreateBlock(playerId, save, now);
        if (allianceBlocked == null) {
            // 门都过了才问钱：与写路径同一个顺序，否则"名字没填"会被"金币不足"抢先盖掉
            allianceBlocked = costLack(cost, balanceOf(playerId, GOLD_RESOURCE_ID, now));
        }
        CreateBlock squadBlocked = squadCreateBlock(playerId, save, now);
        return new SocialCreatePolicyResp(
                new SocialCreatePolicy(squadBlocked == null, 0L, GOLD_RESOURCE_ID,
                        squadBlocked == null ? null : squadBlocked.reason()),
                new SocialCreatePolicy(allianceBlocked == null, cost, GOLD_RESOURCE_ID,
                        allianceBlocked == null ? null : allianceBlocked.reason()),
                now);
    }

    /** 一条创建门的结论：错误码留给写路径抛异常，读路径只取那句人话。 */
    private record CreateBlock(ErrorCode code, String reason) {
    }

    private CreateBlock squadCreateBlock(String playerId, PlayerSave save, long now) {
        String locked = Squad.checkUnlock(save.cityLevel(), dayOffset(now), rules.squadRules());
        if (locked != null) {
            return new CreateBlock(ErrorCode.SQUAD_LOCKED, locked);
        }
        return store.squadOf(playerId)
                .map(squad -> new CreateBlock(ErrorCode.SQUAD_ALREADY_IN,
                        "你已经在小队「" + squad.name() + "」里"))
                .orElse(null);
    }

    private CreateBlock allianceCreateBlock(String playerId, PlayerSave save, long now) {
        long protectedUntil = store.disbandProtectedUntil(playerId);
        if (now < protectedUntil) {
            return new CreateBlock(ErrorCode.ALLIANCE_DISBAND_PROTECTED,
                    "还需等待 " + ((protectedUntil - now + 999L) / 1000L) + " 秒");
        }
        String locked = Alliance.checkUnlock(save.cityLevel(), dayOffset(now), rules.allianceRules());
        if (locked != null) {
            return new CreateBlock(ErrorCode.ALLIANCE_LOCKED, locked);
        }
        return store.allianceOf(playerId)
                .map(alliance -> new CreateBlock(ErrorCode.ALLIANCE_ALREADY_IN,
                        "你已经在联盟「" + alliance.name() + "」里"))
                .orElse(null);
    }

    private static CreateBlock costLack(long cost, long balance) {
        return balance < cost
                ? new CreateBlock(ErrorCode.ALLIANCE_CREATE_COST_LACK,
                        "需要金币 " + cost + "，当前 " + balance)
                : null;
    }

    /** GET /social/permissions（验收 4）。下发结论而不是矩阵。 */
    public PermissionListResp permissions(String playerId, String scopeName, long now) {
        PermissionMatrix.Scope scope;
        try {
            scope = PermissionMatrix.Scope.valueOf(scopeName);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "scope 只支持 SQUAD / ALLIANCE / NATION，实际=" + scopeName);
        }
        PermissionMatrix matrix = rules.permissions();
        String role = switch (scope) {
            case SQUAD -> store.squadOf(playerId).map(squad -> String.valueOf(squad.roleOf(playerId)))
                    .orElse("NONE");
            case ALLIANCE -> store.allianceOf(playerId)
                    .map(alliance -> String.valueOf(alliance.roleOf(playerId))).orElse("NONE");
            case NATION -> nationRoleOf(playerId);
        };
        PermissionMatrix.Tier tier = tierOf(scope, role);
        return new PermissionListResp(scope.name(), role,
                tier == null ? List.of() : List.copyOf(matrix.permissionsOf(scope, tier)), now);
    }

    /**
     * 我在国家里的"职位名"：有官职就是官职名，在国里但没官职是 {@code MEMBER}，
     * 不在任何国家里是 {@code NONE}。
     *
     * <p><b>为什么读路径也要先 bind 再问官职</b>：议员是**派生席位**（每盟主一席，没人任命它），
     * 只有 {@code NationLeaders.bind} 注入盟主查询之后 {@code officeOf} 才看得见它。
     * 少这一次 bind 的症状很隐蔽：议员看到自己的 role 变成 MEMBER —— 而两者的权限档位**恰好相同**，
     * 所以面板上的按钮一模一样，只有角色名一处不对。
     *
     * <p>亡国的国家不算：它已经不是一个可查询、可入籍的对象（成员表也被清空），
     * 把它当成国籍会让玩家在一个不存在的国家里"有权限"。
     */
    private String nationRoleOf(String playerId) {
        return store.allianceOf(playerId)
                .flatMap(alliance -> nations.findByAlliance(alliance.id()))
                .filter(nation -> !nation.isDisbanded())
                .map(nation -> {
                    com.ironoath.core.nation.Nation.Office office =
                            nationLeaders.bind(nation).officeOf(playerId);
                    return office == null
                            ? com.ironoath.core.nation.NationPermissions.MEMBER_ROLE
                            : office.name();
                })
                .orElse(com.ironoath.core.nation.NationPermissions.NO_ROLE);
    }

    // ================= 互助（验收 6） =================

    /**
     * 登记一条帮助请求。由升级/训练/治疗的发起方调用（B03/B05 的接线点）。
     *
     * <p><b>实现已挪到 {@link com.ironoath.web.social.HelpRequestRegistrar}</b>：发起方是城建/军队域，
     * 而本服务依赖着攻击闸门链（→ AttackGuardService → PowerRefreshService → CityAppService），
     * 直接注入本服务会让依赖成环、上下文起不来。这里保留同名入口只是为了既有调用点与测试不必改。
     *
     * @param targetKey 加速要落到哪个目标上（建筑 instanceId 等）。<b>它不是给人看的 targetDesc</b>：
     *                  少这个定位符，"被帮助"就找不到要加速的对象，帮助会退化成只加计数与红点的表演
     * @param expireAfterMillis 请求的有效期（本参数由 {@code finishAt} 表达：过期的请求不再进红点）
     */
    public void registerHelpRequest(String requestId, String playerId, HelpTargetKind kind,
                                    String targetKey, String targetDesc, long finishAt, long now) {
        helpRequests.register(requestId, playerId, kind, targetKey, targetDesc, finishAt, now);
    }

    /** 帮助一条请求。 */
    public HelpResp help(String playerId, String helpRequestId, long now) {
        return doHelp(playerId, List.of(helpRequestId), now);
    }

    /**
     * 一键帮助全部（验收 6：正确帮助所有可帮助项，红点清零）。
     *
     * <p><b>一次请求处理全部</b>：与扫荡同一条纪律（B09 验收 9）。
     * 逐条发 20 个请求的话，弱网下会有几次超时，玩家看到的是「点了全部却只帮到 5 个人」而红点还剩一半。
     */
    public HelpResp helpAll(String playerId, long now) {
        // 候选与徽标必须出自同一次判定，见 helpBoard
        List<String> candidates = new ArrayList<>();
        for (HelpRequestView row : helpBoard(playerId, now).rows()) {
            if (!row.alreadyHelped()) {
                candidates.add(row.requestId());
            }
        }
        return doHelp(playerId, candidates, now);
    }

    private HelpResp doHelp(String playerId, List<String> requestIds, long now) {
        // 本人的昵称只读一次：它原来落在下面的循环里，乘数 = 本次真正帮到的条数（一键帮助能一次
        // 帮满当天的额度），而每次读的都是同一个人的整份存档。
        // 刻意"用到才读"而不是提到循环外：全部跳过的批次本来一次存档都不该读。
        String helperName = null;
        int helped = 0;
        int skipped = 0;
        long speedup = 0L;
        for (String requestId : requestIds) {
            Optional<SocialStore.HelpRequest> found = store.helpRequest(requestId);
            if (found.isEmpty()) {
                skipped++;
                continue;
            }
            SocialStore.HelpRequest request = found.get();
            if (request.fromPlayerId().equals(playerId)) {
                skipped++;
                continue;
            }
            String targetKey = helpKey(request);
            if (helpLedger.alreadyHelped(playerId, targetKey, now)) {
                skipped++;
                continue;
            }
            HelpLedger.Outcome outcome = helpLedger.help(playerId, targetKey, now);
            if (outcome == null) {
                // 额度用完了：照实返回已帮到的数量，而不是报错 ——
                // 已经帮上的那几次是有效的，整批失败会让玩家以为自己白点了
                break;
            }
            helped++;
            speedup += outcome.grantedFixed();
            applyHelpToTarget(request, outcome.grantedFixed(), now);
            store.markHelped(requestId);
            // 帮一次算一次（目标不细分）：B12 §1 的 HELP_SQUAD
            questEvents.progress(playerId, com.ironoath.core.quest.GoalType.HELP_SQUAD, null, 1L, now);
            if (helperName == null) {
                helperName = nickname(playerId);
            }
            store.pushEvent(request.fromPlayerId(), event("HELP_RECEIVED",
                    helperName + " 帮助了你：" + request.targetDesc(), null, requestId, now));
        }
        LOG.info("互助帮助 helper={} 成功={} 跳过={} 合计加速={}", playerId, helped, skipped, speedup);
        return new HelpResp(helped, skipped, helpLedger.remainingToday(playerId, now),
                pendingHelpsOf(playerId, now), speedup, now);
    }

    /**
     * 把一次帮助真正落到目标上（B03 §3 × B10 §2）。
     *
     * <p><b>三种目标都由这里落地</b>：建筑（{@code BUILDING}）走
     * {@link com.ironoath.core.city.CityState#speedUpByRatio}，训练（{@code TRAINING}）与
     * 治疗（{@code TREATING}）走 {@code ArmyState} 上与之对等的两个方法。之前只处理建筑、
     * 训练与治疗"只记账不落地"，那是一个只加计数与红点的假承诺（收口清单 #26）。
     *
     * <p><b>目标消失或已经完成时安静跳过</b>：请求可能刚好在完成的那一刻被帮（列表那边有过滤，
     * 但过滤与帮助之间隔着一次网络往返）。为一条过期请求让整批帮助失败，是拿大多数人的正常体验
     * 去赔一个边缘情况。
     *
     * <p><b>只落库、不碰账本</b>：这次实际授予多少比例由 {@code HelpLedger} 说了算，
     * 本方法只负责把它乘到目标的原始总时长上（那才是"目标自己的属性"）。
     */
    private void applyHelpToTarget(SocialStore.HelpRequest request, long grantedFixed, long now) {
        if (request.targetKey() == null || request.targetKey().isBlank()) {
            return;
        }
        String ownerId = request.fromPlayerId();
        try {
            if (HelpTargetKind.BUILDING.name().equals(request.kind())) {
                applyToBuilding(ownerId, request.targetKey(), grantedFixed, now);
            } else if (HelpTargetKind.TRAINING.name().equals(request.kind())) {
                applyToArmy(ownerId, true, request.targetKey(), grantedFixed, now);
            } else if (HelpTargetKind.TREATING.name().equals(request.kind())) {
                applyToArmy(ownerId, false, request.targetKey(), grantedFixed, now);
            }
        } catch (RuntimeException e) {
            // 目标已经完成、或被取消/移除：这次帮助在账上算数（额度与事件都已发生），
            // 但没有可加速的对象。记一条而不是抛，理由见方法注释
            LOG.info("帮助加速没有落点（目标可能已完成或被移除）owner={} kind={} target={} 原因={}",
                    ownerId, request.kind(), request.targetKey(), e.getMessage());
        }
    }

    /**
     * 落到建筑上：仓储返回副本 → 按比例压完成时刻 → 带版本写回。
     *
     * <p>目标不在升级中（已完成/已取消）时 {@code speedUpByRatio} 返回 0，
     * 这里就什么都不写 —— 把"安静跳过"的判断留在领域层，而不是在服务层再抄一份
     * 关于"什么算在升级中"的判定。
     */
    private void applyToBuilding(String ownerId, String buildingId, long grantedFixed, long now) {
        com.ironoath.core.city.CityState city = cities.findByPlayerId(ownerId).orElse(null);
        if (city == null) {
            return;
        }
        long version = cities.versionOf(ownerId);
        long applied = city.speedUpByRatio(buildingId, grantedFixed, now);
        if (applied > 0L) {
            cities.save(ownerId, city, version);
            LOG.info("帮助加速已落到建筑 owner={} building={} 提前={}秒", ownerId, buildingId, applied);
        }
    }

    /**
     * 落到军队上：训练按兵种定位到队列里的那一批，治疗是玩家全局唯一的那份倒计时。
     *
     * <p>同样只写回「真的提前了」的情况：训练已完成、治疗已收割时领域方法返回 0。
     */
    private void applyToArmy(String ownerId, boolean training, String targetKey,
                             long grantedFixed, long now) {
        com.ironoath.core.army.ArmyState army = armies.findByPlayerId(ownerId).orElse(null);
        if (army == null) {
            return;
        }
        long version = armies.versionOf(ownerId);
        long applied = training
                ? army.speedUpTrainingByRatio(targetKey, grantedFixed, now)
                : army.speedUpTreatmentByRatio(grantedFixed, now);
        if (applied > 0L) {
            armies.save(ownerId, army, version);
            LOG.info("帮助加速已落到军队 owner={} 类型={} 目标={} 提前={}秒",
                    ownerId, training ? HelpTargetKind.TRAINING : HelpTargetKind.TREATING,
                    targetKey, applied);
        }
    }

    private static String helpKey(SocialStore.HelpRequest request) {
        // 目标键要能唯一标识「谁的哪一件事」：只用 playerId 的话，
        // 同一个人同时升级两个建筑就只能被帮一次
        return request.fromPlayerId() + ":" + request.requestId();
    }

    /**
     * 「可帮列表 + 徽标数」的唯一来源：一次遍历同时给出两者。
     *
     * <p>刻意不让列表与计数各写一遍循环：那正是徽标与列表漂移的成因，而表现是
     * 「红点说 5 条、点进去只有 3 条、一键帮助却帮了 5 次」—— 三个数字各自都有日志，
     * 谁都不像 bug。契约里 {@code SocialHelpListResp} 的说明记的是同一条要求。
     *
     * <p>列表里<b>包含已经帮过的行</b>（靠 {@code alreadyHelped} 标出）：玩家要能看见「我帮过谁」，
     * 而 {@code helpAll} 也必须靠这个标记跳过重复消耗额度的项。
     */
    private HelpBoard helpBoard(String playerId, long now) {
        int remaining = Math.max(0, helpLedger.remainingToday(playerId, now));
        List<HelpRequestView> rows = new ArrayList<>();
        int pending = 0;
        for (SocialStore.HelpRequest request : store.helpRequests()) {
            if (request.fromPlayerId().equals(playerId)) {
                continue;   // 不能帮自己
            }
            if (request.finishAt() > 0L && request.finishAt() <= now) {
                continue;   // 已经完成了，帮它没有意义
            }
            boolean helped = helpLedger.alreadyHelped(playerId, helpKey(request), now);
            if (!helped) {
                pending++;
            }
            long remainingSeconds = request.finishAt() > 0L
                    ? Math.max(0L, (request.finishAt() - now) / 1000L) : 0L;
            rows.add(new HelpRequestView(request.requestId(), request.fromPlayerId(),
                    request.fromPlayerName(), HelpTargetKind.valueOf(request.kind()),
                    request.targetDesc(), remainingSeconds, request.helpedCount(), helped));
        }
        return new HelpBoard(List.copyOf(rows), Math.min(pending, remaining), remaining);
    }

    /** 一次遍历的产物。行序即 store 的插入序，客户端不需要再排。 */
    private record HelpBoard(List<HelpRequestView> rows, int pending, int remainingToday) {
    }

    /** 红点数。必须与 {@link #helpBoard} 同源，所以这里只剩一行委托。 */
    private int pendingHelpsOf(String playerId, long now) {
        return helpBoard(playerId, now).pending();
    }

    /** GET /social/helpRequests（B10 验收 6 的数据源）。 */
    public SocialHelpListResp helpList(String playerId, long now) {
        HelpBoard board = helpBoard(playerId, now);
        return new SocialHelpListResp(board.rows(), board.pending(), board.remainingToday(), now);
    }

    /** 同组织的成员（小队 + 联盟），用于帮助请求与被攻击通知的推送范围。 */
    private List<String> peersOf(String playerId) {
        // 委托存储：这条查询同时被求助登记器需要，留在服务里就会逼着另一个域依赖本服务（成环）
        return store.peerPlayerIds(playerId);
    }

    /**
     * 成员被攻击时通知盟友（验收 5：在线成员 3 秒内收到推送并可点击跳转支援）。
     *
     * <p>由战斗结算方调用。这里只做「落事件」—— 推送本身走 WebSocket，
     * 而 B10 禁止项要求广播不得同步阻塞业务线程，所以推送发布点必须在异步线程池里，
     * 不在本方法内（本方法在玩家锁内被调用，任何阻塞都会拖长锁持有时间）。
     *
     * @param expireAt 支援窗口的截止时刻。过了它就置灰，点进去只会看到一片废墟
     */
    public void notifyMemberAttacked(String victimId, String attackerName, long coordX, long coordY,
                                     long now) {
        // 支援窗口由社交规则算，不让调用方给：战斗结算方不知道「支援窗口有多长」，
        // 让它传一个数就等于把这条口径复制到第二个地方，而两处不一致的症状是
        // 「推送说还能支援，点进去已经置灰」
        notifyMemberAttacked(victimId, attackerName, coordX, coordY, now,
                now + rules.attackPushDeadlineMillis());
    }

    public void notifyMemberAttacked(String victimId, String attackerName, long coordX, long coordY,
                                     long now, long expireAt) {
        SocialStore.SocialEvent record = new SocialStore.SocialEvent(
                SocialEventIds.of("evt_attack_" + victimId + "_" + now), "MEMBER_ATTACKED",
                "盟友 " + nickname(victimId) + " 正在被 " + attackerName + " 攻击",
                "点击跳转支援", coordX, coordY, victimId, now, expireAt);
        var peers = peersOf(victimId);
        for (String peer : peers) {
            // 落事件是离线补偿那条路（验收 12）：不在线的人下次上线时按已读标记补收
            store.pushEvent(peer, record);
        }
        // WebSocket 推送是在线那条路（验收 5：3 秒内收到）。异步、有界、满了就丢 ——
        // 本方法在业务线程里被调用，而广播绝不能阻塞它（B10 禁止项）。
        // **推的是视图而不是存储记录**：两个 record 的字段名不同（记录是 coordX/coordY + expireAt，
        // 视图是 coord + expired），而验收 5/12 明写"推送与离线补偿共用同一结构、内容必须一致"。
        // 此前这里直接发了存储记录 —— 两条路各自都"看起来对"，所以没有任何用例会红；
        // 第一个把两路放进同一个列表的消费者（B22 聊天页签的未读账）会读到 undefined
        pushPublisher.publish("MEMBER_ATTACKED", peers, toEventView(record, now));
        LOG.info("盟友被攻击推送 victim={} 通知人数={} 支援窗口至={} 推送预算={}ms",
                victimId, peers.size(), expireAt, rules.attackPushDeadlineMillis());
    }

    /** 标记事件已读（验收 12：离线补偿的事件不能每次上线都重收一遍）。 */
    public SocialSummaryResp ackEvents(String playerId, List<String> eventIds, long now) {
        int acked = store.ackEvents(playerId, eventIds);
        LOG.info("社交事件已读 playerId={} 标记={} 条", playerId, acked);
        return summary(playerId, now);
    }

    // ================= 聊天（验收 9） =================

    /** 发消息。被限流时抛业务错误而不是静默丢弃（假装发成功会让玩家以为对方收到了）。 */
    public ChatSendResp chatSend(String playerId, ChatSendReq req, long now) {
        ChatChannel channel = req.channel();
        if (channel == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_CONTENT_INVALID, "消息内容不得为空");
        }
        // 送检排在限流之后：限流是"你发太快"，送检是"这句能不能发"，
        // 顺序反了会让被限流的消息也白跑一次外部调用（而外部调用是这四处里最贵的一步）。
        // 送检排在限流之后：限流是"你发太快"，送检是"这句能不能发"，
        // 顺序反了会让被限流的消息也白跑一次外部调用（而外部调用是这四处里最贵的一步）。
        contentSecurity.requireClean(playerId,
                com.ironoath.web.security.ContentSecurityClient.Scene.SOCIAL_LOG,
                content, ErrorCode.SOCIAL_CHAT_CONTENT_INVALID, "消息内容");
        if (channel == ChatChannel.PRIVATE) {
            // 拉黑拦在写之前：拦不住的代价是"消息已经送到对方那儿了再报错"
            requireNotBlocked(playerId, req.toPlayerId());
        }
        String channelKey = requireChannelKey(channel, playerId, req.toPlayerId());
        ChatRateLimiter.Verdict verdict = chatLimiter.check(playerId, content, now);
        if (!verdict.allowed()) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_RATE_LIMITED,
                    "同一句话 " + (verdict.retryAfterMillis() / 1000L + 1L) + " 秒后才能再发");
        }
        SocialStore.ChatMessage message = new SocialStore.ChatMessage(
                // 不与内容、时刻、发送者挂钩：原先那串（playerId + now + content.hashCode）里，
                // hashCode 不是单射（"Aa" 与 "BB" 同码是 Java 的经典例子），所以"同一毫秒 + 不同内容
                // 但同码"的两条会得到同一个 id —— 而 /chat/list 的游标就是按 id 定位的，
                // 撞上的表现不是报错，是翻页时静默少一条，客户端无从发现。
                // 与 march_ / battle_ 同一套随机 id 约定：id 只负责唯一，可读性靠前缀。
                "msg_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                channel.name(), playerId, nickname(playerId), content, now);
        store.appendChat(channelKey, message, (int) configs.longParam("CHAT_LOCAL_HISTORY_MAX"));
        if (channel == ChatChannel.PRIVATE) {
            notifyPrivateMessage(playerId, req.toPlayerId(), now);
        }
        return new ChatSendResp(toMessageView(message), now);
    }

    /**
     * 私聊送达通知（2026-09-13 裁决 C23）。此前私聊「发得出去、对方不拉就永远不知道」，
     * 而 B10 的实时性一节已经把"事件 + 离线补偿"这套机制建好了 —— 不接它才是缺口。
     *
     * <p><b>事件里不写正文</b>：正文的家是聊天频道（{@code /chat/list} 拉得到）。
     * 抄一份进事件就有两个版本，而且事件带 3 小时 TTL、聊天带 200 条裁剪，
     * 两边各自消失的时间还不一样 —— 症状是"通知说有人找我，点开却看不到那条"。
     *
     * <p><b>同一发信人只留一条未读</b>：连发 20 条不该产生 20 条通知，
     * 那会把"有人找我"这一件事变成刷屏，而收件人真正要做的动作只有一个（去回话）。
     */
    private void notifyPrivateMessage(String senderId, String recipientId, long now) {
        if (recipientId == null || recipientId.isBlank() || recipientId.equals(senderId)) {
            return;
        }
        for (SocialStore.SocialEvent pending : store.unreadEvents(recipientId)) {
            if ("PRIVATE_MESSAGE".equals(pending.type()) && senderId.equals(pending.relatedId())
                    && !pending.expiredAt(now)) {
                return;
            }
        }
        SocialStore.SocialEvent record = event("PRIVATE_MESSAGE",
                nickname(senderId) + " 给你发来一条私信", null, senderId, now);
        store.pushEvent(recipientId, record);
        // 与 MEMBER_ATTACKED 同一处理由：推送与离线补偿必须是同一个形状，
        // 否则把两路事件合并读的客户端会在缺席字段上拿到 undefined
        pushPublisher.publish("PRIVATE_MESSAGE", List.of(recipientId), toEventView(record, now));
    }

    /** 拉取某频道的最近消息。 */
    public ChatListResp chatList(String playerId, ChatListReq req, long now) {
        String channelKey = requireChannelKey(req.channel(), playerId, req.toPlayerId());
        int limit = (int) Math.min(Math.max(1, req.limit()), configs.longParam("CHAT_LOCAL_HISTORY_MAX"));
        List<SocialStore.ChatMessage> messages =
                withoutBlocked(store.chat(channelKey, req.beforeMessageId(), limit), playerId);
        List<ChatMessageView> views = new ArrayList<>(messages.size());
        for (SocialStore.ChatMessage message : messages) {
            views.add(toMessageView(message));
        }
        return new ChatListResp(views, store.hasMore(channelKey, req.beforeMessageId(), limit), now);
    }

    // ================= 举报与拉黑（B22 §一 3） =================

    // ================= 关注（B22 §一 4） =================

    /**
     * 关注一个人（单向，§五 裁决④）。**对方不会收到任何通知**：关注是"我想看他在不在线、
     * 想随时私聊他"，不是请求 —— 通知对方就等于把"谁在看你"透出去。
     */
    public FriendListView follow(String playerId, FollowReq req) {
        String target = requireFollowTarget(playerId, req);
        if (!store.followedPlayers(playerId).contains(target)) {
            long cap = configs.longParam("SOCIAL_FOLLOW_MAX");
            if (store.followedPlayers(playerId).size() >= cap) {
                throw new BizException(ErrorCode.SOCIAL_FOLLOW_LIMIT,
                        "最多关注 " + cap + " 人，先取关几个");
            }
        }
        store.follow(playerId, target);
        LOG.info("已关注 playerId={} 目标={}", playerId, target);
        return follows(playerId);
    }

    /** 取消关注（幂等）。 */
    public FriendListView unfollow(String playerId, FollowReq req) {
        String target = requireFollowTarget(playerId, req);
        store.unfollow(playerId, target);
        LOG.info("已取消关注 playerId={} 目标={}", playerId, target);
        return follows(playerId);
    }

    /**
     * 我关注的人（最近关注的在前），带昵称、在线状态与最近活跃时刻。
     *
     * <p><b>在线是 WS 网关此刻的快照</b>（{@code PushGateway.isOnline}）：它是"现在能不能立刻聊上"的
     * 唯一可靠来源 —— 客户端自己猜（比如按 lastSeenAt 小于 5 分钟算在线）会在网络抖动时给出假的绿点，
     * 而"看到人在线"正是这一列表存在的理由。
     */
    public FriendListView follows(String playerId) {
        requirePlayer(playerId);
        long now = timeService.serverNow();
        List<String> ids = store.followedPlayers(playerId);
        // 关注对象的昵称与活跃时刻一次批量读回。逐个 findByPlayerId 是每次打开这一列表打
        // SOCIAL_FOLLOW_MAX（50）趟**整份存档**，而这一列表只要昵称与活跃时刻两项 —— 判据见
        // FollowListQueryCountTest（结果完全相同，只有往返数抓得住它）。名单为空时连这一趟都不发：
        // 新号第一次打开社交面板就是空名单，那不该是一次存储往返。
        // 走的还是投影口：这两项之外的一整份存档（资源 / PVP / 科技 / 头像框集合）搬回来没人看
        Map<String, PlayerBrief> briefs = ids.isEmpty() ? Map.of() : players.findBriefs(ids);
        List<FriendView> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            PlayerBrief brief = briefs.get(id);
            boolean online = pushGateway.isOnline(id);
            out.add(new FriendView(id,
                    brief == null ? id : brief.nickName(),
                    online,
                    online ? now : (brief == null ? 0L : brief.lastLoginAt())));
        }
        return new FriendListView(out);
    }

    private String requireFollowTarget(String playerId, FollowReq req) {
        requirePlayer(playerId);
        if (req == null || req.targetPlayerId() == null || req.targetPlayerId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "targetPlayerId 不得为空");
        }
        if (req.targetPlayerId().equals(playerId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能关注自己");
        }
        return req.targetPlayerId();
    }

    /**
     * 举报留痕的运维只读出口（B22 §一 3 的"留痕必须可查" + §五 裁决②）。
     *
     * <p>窗口回显与 `/ops/mail/recent` 同一条理由：不说明看了多大窗口的空结果，
     * 区分不开"那段时间没人举报"与"窗口传错了"。
     */
    public OpsReportRecentResp recentReportsForOps(Long windowSeconds, int limit) {
        long now = timeService.serverNow();
        long maxWindow = REPORT_RETENTION_SECONDS;
        long window = windowSeconds == null || windowSeconds <= 0 ? maxWindow : windowSeconds;
        if (window > maxWindow) {
            LOG.warn("举报只读出口要了 {} 秒窗口，超过保留期 {} 秒，已夹住",
                    window, maxWindow);
            window = maxWindow;
        }
        int cap = Math.min(Math.max(1, limit), 200);
        long since = now - window * 1000L;
        List<SocialStore.ReportRecord> rows = store.reportsSince(since, cap);
        List<OpsReportRow> out = new ArrayList<>(rows.size());
        for (SocialStore.ReportRecord record : rows) {
            out.add(new OpsReportRow(record.reportId(), record.reporterId(),
                    record.targetPlayerId(), record.messageId(), ReportReason.valueOf(record.reason()),
                    record.detail() == null || record.detail().isEmpty() ? null : record.detail(),
                    record.createdAt()));
        }
        return new OpsReportRecentResp(window, store.reportTotalSince(since), out.size(), out);
    }

    /**
     * 举报留痕的保留期。**与请求窗口的上限是同一个数**：比它更早的记录已经不在表里，
     * 给一个更大的窗口只会得到一张"没人举报过"的假表。
     *
     * <p>取 30 天：客服回查投诉通常在一周内，而运营周报按自然周汇总 —— 30 天覆盖得住，
     * 同时不至于让这张只会涨的表无限大（真要长期归档是数据侧的事，不是这张表的事）。
     */
    private static final long REPORT_RETENTION_SECONDS = 30L * 24 * 3600;

    /**
     * 举报留痕。**只记不改**（§五 裁决②）：服务端记下"谁、举报谁、哪条、为什么、何时"，
     * 封不封号是运营的决定 —— 代码不替它下结论，也不把结论回给举报人
     * （表现成"报了就一定封"会让举报变成一种攻击工具）。
     */
    public ReportResp report(String playerId, ReportReq req, long now) {
        if (req == null || req.reason() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "reason 不得为空");
        }
        String target = req.targetPlayerId();
        if (target == null || target.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "targetPlayerId 不得为空");
        }
        if (target.equals(playerId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能举报自己");
        }
        long limit = configs.longParam("SOCIAL_REPORT_DAILY_LIMIT");
        long since = now - REPORT_WINDOW_MILLIS;
        if (store.reportCount(playerId, target, since) >= limit) {
            throw new BizException(ErrorCode.SOCIAL_REPORT_DUPLICATE,
                    "同一个人 24 小时内最多举报 " + limit + " 次：前面那条已经记下了，运营会看到");
        }
        String detail = req.detail() == null ? null : req.detail().trim();
        if (detail != null && !detail.isEmpty()) {
            // 举报框同样是玩家自由输入 —— 不能因为它叫"举报"就免检（B22 验收 8）
            contentSecurity.requireClean(playerId,
                    com.ironoath.web.security.ContentSecurityClient.Scene.SOCIAL_LOG,
                    detail, ErrorCode.SOCIAL_CHAT_CONTENT_INVALID, "举报说明");
        }
        String reportId = "report_" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        store.appendReport(new SocialStore.ReportRecord(reportId, playerId, target,
                req.messageId(), req.reason().name(), detail == null ? "" : detail, now));
        LOG.info("举报已受理 reportId={} 举报人={} 目标={} 原因={} 消息={}",
                reportId, playerId, target, req.reason(), req.messageId());
        return new ReportResp(reportId, now);
    }

    /** 拉黑（幂等：已在名单里就是成功）。返回更新后的名单，界面直接照着画。 */
    public BlockListView block(String playerId, BlockReq req) {
        String target = requireBlockTarget(playerId, req);
        store.block(playerId, target);
        LOG.info("拉黑 playerId={} 目标={}", playerId, target);
        return blockListOf(playerId);
    }

    /** 取消拉黑（幂等）。 */
    public BlockListView unblock(String playerId, BlockReq req) {
        String target = requireBlockTarget(playerId, req);
        store.unblock(playerId, target);
        LOG.info("取消拉黑 playerId={} 目标={}", playerId, target);
        return blockListOf(playerId);
    }

    /** 我拉黑了谁。**只回我自己的名单**：对方拉没拉黑我是看不到的（那会变成一种骚扰反馈）。 */
    public BlockListView blocks(String playerId) {
        requirePlayer(playerId);
        return blockListOf(playerId);
    }

    /**
     * 名单视图：id 供发请求，**显示名由服务端解析好**（客户端不查表，铁律 2）。
     *
     * <p>2026-09-22（收口清单 #322）：原先这里只回一串 id，于是「取消拉黑」选择器只能把
     * {@code P9179c…} 印给玩家 —— 与 #255/#268/#320 同族。查不到存档时回
     * {@link PlayerDtoMapper#UNKNOWN_PLAYER_NAME}，**不回 id**（#323 同一条口径）。
     */
    private BlockListView blockListOf(String playerId) {
        List<BlockedPlayerView> out = new ArrayList<>();
        for (String blockedId : store.blockedPlayers(playerId)) {
            PlayerSave save = players.findByPlayerId(blockedId).orElse(null);
            out.add(new BlockedPlayerView(blockedId, PlayerDtoMapper.displayName(save)));
        }
        return new BlockListView(out);
    }

    private String requireBlockTarget(String playerId, BlockReq req) {
        requirePlayer(playerId);
        if (req == null || req.targetPlayerId() == null || req.targetPlayerId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "targetPlayerId 不得为空");
        }
        if (req.targetPlayerId().equals(playerId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能拉黑自己");
        }
        return req.targetPlayerId();
    }

    /**
     * 私聊的拉黑拦截（B22 §一 3：私聊拒收 + 不能再发消息给我）。
     *
     * <p><b>方向必须说清</b>：能自己解除的那一种（我拉黑了对方）与只能等的那一种（对方拉黑了我），
     * 下一步完全不同 —— 合成一句"消息被拦截"，前一种玩家会去找客服。
     */
    private void requireNotBlocked(String senderId, String recipientId) {
        if (recipientId == null || recipientId.isBlank() || recipientId.equals(senderId)) {
            return;
        }
        if (store.hasBlocked(senderId, recipientId)) {
            throw new BizException(ErrorCode.SOCIAL_BLOCKED, "你已拉黑对方，先解除拉黑再发消息");
        }
        if (store.hasBlocked(recipientId, senderId)) {
            throw new BizException(ErrorCode.SOCIAL_BLOCKED, "对方已将你拉黑，消息发不出去");
        }
    }

    /**
     * 频道消息过滤：我拉黑的人说的话，我看不到（B22 §一 3 的"频道过滤"）。
     *
     * <p><b>按观察者过滤，而不是从存储里删</b>：拉黑是"我不想看见"，不是"这句话不存在"——
     * 别人（以及运营）仍然看得到原话，而"举报后消息消失"会让举报变成一种删除工具。
     *
     * <p>代价说清楚：过滤发生在取到窗口之后，所以被拉黑的人一多，这一屏能看到的条数会少于 limit
     * （而 `hasMore` 说的是存储里还有没有）。这是可接受的：窗口本来就是"最近 200 条"的近似，
     * 想要精确条数就得在存储层做过滤，那会让"某人看到的历史"变成一份需要物化的东西。
     */
    private List<SocialStore.ChatMessage> withoutBlocked(List<SocialStore.ChatMessage> messages,
                                                         String viewerId) {
        List<String> blocked = store.blockedPlayers(viewerId);
        if (blocked.isEmpty()) {
            return messages;
        }
        List<SocialStore.ChatMessage> out = new ArrayList<>(messages.size());
        for (SocialStore.ChatMessage message : messages) {
            if (!blocked.contains(message.senderId())) {
                out.add(message);
            }
        }
        return out;
    }

    /** 举报的限频窗口：24 小时（契约 `SOCIAL_REPORT_DAILY_LIMIT` 的 why 里写着"同一目标 24h 内"）。 */
    private static final long REPORT_WINDOW_MILLIS = 24L * 3_600_000L;

    /**
     * 把一条「分享了战报」的消息写进组织频道（B22 §一 2），返回落地的频道键与消息 id。
     *
     * <p><b>为什么由聊天域来写、而不是战报域自己写频道</b>：频道资格、内容送检、限流、
     * 以及"消息长什么样"这四件事都已经在聊天域里各有一处实现。战报域再写一遍就是同一件事的
     * 第二个家 —— 改限流窗口时漏改一边，表现是"战报分享不受限流约束"。
     * 本方法只做一件事：以某个玩家的身份，往他能发言的频道里写一条<b>服务端拟好</b>的正文。
     *
     * <p>频道资格沿用 {@link #requireChannelKey}：未入盟的人分享到联盟频道拿到的错误码与
     * `/chat/send` 完全一致（{@code SOCIAL_CHAT_CHANNEL_INVALID}），而不是一句新造的"不能分享"。
     */
    public SharedPost postSharedReport(String playerId, ChatChannel channel, String text, long now) {
        if (channel == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_CONTENT_INVALID, "分享内容不得为空");
        }
        String channelKey = requireChannelKey(channel, playerId, null);
        contentSecurity.requireClean(playerId,
                com.ironoath.web.security.ContentSecurityClient.Scene.SOCIAL_LOG,
                content, ErrorCode.SOCIAL_CHAT_CONTENT_INVALID, "分享的战报");
        ChatRateLimiter.Verdict verdict = chatLimiter.check(playerId, content, now);
        if (!verdict.allowed()) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_RATE_LIMITED,
                    "同一句话 " + (verdict.retryAfterMillis() / 1000L + 1L) + " 秒后才能再发");
        }
        SocialStore.ChatMessage message = new SocialStore.ChatMessage(
                "msg_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                channel.name(), playerId, nickname(playerId), content, now);
        store.appendChat(channelKey, message, (int) configs.longParam("CHAT_LOCAL_HISTORY_MAX"));
        return new SharedPost(channelKey, message.messageId());
    }

    /** 分享落地的回执：写进了哪个频道键、那条消息的 id。 */
    public record SharedPost(String channelKey, String messageId) {
    }

    /**
     * 这名玩家此刻能读的频道键（联盟 / 小队各一个；没加入就没有）。
     *
     * <p>用来回答"这份战报分享到了他所在的频道吗"（B22 验收 4 的"对方可点开回放"）。
     * 键的拼法复用 {@link #channelKey}：两处各拼一份的话，`ALLIANCE:a1` 与 `alliance:a1`
     * 这种偏差会让分享过的战报突然打不开，而两边都不会报错。
     *
     * <p><b>按"此刻"算</b>：退了盟、退了队就读不到了 —— 与聊天频道本身同一条口径
     * （离开组织之后 `/chat/list` 也拉不到那个频道的消息）。可见范围跟着频道走，
     * 而不是跟着"谁看过"走。
     */
    public List<String> channelKeysOf(String playerId) {
        List<String> keys = new ArrayList<>(2);
        String alliance = channelKey(ChatChannel.ALLIANCE, playerId, null);
        if (alliance != null) {
            keys.add(alliance);
        }
        String squad = channelKey(ChatChannel.SQUAD, playerId, null);
        if (squad != null) {
            keys.add(squad);
        }
        return List.copyOf(keys);
    }

    /**
     * 取会话键，取不到就说清<b>是哪一种取不到</b>。
     *
     * <p>"没资格发言"与"漏传字段"必须分开说：前者的下一步是去加入组织，后者的下一步是把对象带上。
     * 混成一句"你没有在该频道发言的资格"，玩家会以为自己被踢出了会话（而私聊没有会话可退）。
     * 发与收两侧共用这一处，是为了不让同一件事在两个入口长出两种文案。
     */
    private String requireChannelKey(ChatChannel channel, String playerId, String toPlayerId) {
        if (channel == ChatChannel.PRIVATE && (toPlayerId == null || toPlayerId.isBlank())) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_CHANNEL_INVALID,
                    "私聊频道必须带上私聊对象 toPlayerId：会话键由双方 id 拼成，缺它就没有这一段会话");
        }
        String key = channelKey(channel, playerId, toPlayerId);
        if (key == null) {
            throw new BizException(ErrorCode.SOCIAL_CHAT_CHANNEL_INVALID,
                    "你没有在该频道发言的资格（未加入对应组织）");
        }
        return key;
    }

    /**
     * 频道键。返回 null 表示「没有资格在该频道发言」——
     * 联盟频道要真的是盟员，小队频道要真的是队员。
     * 不校验的话，任何人只要构造一个 allianceId 就能往别人的联盟频道里发消息。
     */
    private String channelKey(ChatChannel channel, String playerId, String toPlayerId) {
        switch (channel) {
            case WORLD:
                return "WORLD";
            case ALLIANCE: {
                Optional<Alliance> alliance = store.allianceOf(playerId);
                return alliance.map(value -> "ALLIANCE:" + value.id()).orElse(null);
            }
            case SQUAD: {
                Optional<Squad> squad = store.squadOf(playerId);
                return squad.map(value -> "SQUAD:" + value.id()).orElse(null);
            }
            case PRIVATE: {
                if (toPlayerId == null || toPlayerId.isBlank()) {
                    return null;
                }
                // 字典序拼接：A→B 与 B→A 必须落到同一个键，否则两人看到的是两条不同的会话
                String first = playerId.compareTo(toPlayerId) <= 0 ? playerId : toPlayerId;
                String second = playerId.compareTo(toPlayerId) <= 0 ? toPlayerId : playerId;
                return "PRIVATE:" + first + ":" + second;
            }
            default:
                return null;
        }
    }

    // ================= 视图组装 =================

    /** 联盟职位 → 协议枚举。非成员没有职位，回 null 而不是抛异常。 */
    private static com.ironoath.web.dto.generated.AllianceRole toRoleView(
            com.ironoath.core.social.AllianceRole role) {
        return role == null ? null : com.ironoath.web.dto.generated.AllianceRole.valueOf(role.name());
    }

    private SquadView toSquadView(Squad squad, String viewerId, long now) {
        List<SquadMember> members = new ArrayList<>(squad.memberCount());
        for (String memberId : squad.memberIds()) {
            PlayerSave save = players.findByPlayerId(memberId).orElse(null);
            members.add(new SquadMember(memberId,
                    PlayerDtoMapper.displayName(save),
                    save == null || save.power() == null ? 0L : save.power().displayPower(),
                    save == null ? now : save.lastLoginAt(),
                    com.ironoath.web.dto.generated.SquadRole.valueOf(
                            String.valueOf(squad.roleOf(memberId))),
                    save == null ? 0 : save.cityLevel()));
        }
        return new SquadView(squad.id(), squad.name(), squad.leaderId(), members,
                squad.level(), squad.exp(), squad.expToNext(),
                squad.memberCap(cityLevelOf(squad.leaderId())),
                squad.shopUnlocked() ? squad.level() : 0,
                squad.squadCoinOf(viewerId),
                squad.allianceId(), squad.isSubSquad(),
                squad.dailyQuestProgress(),
                configs.longParam("SQUAD_QUEST_DAILY_MONSTER"),
                now);
    }

    private AllianceView toAllianceView(Alliance alliance, String viewerId, long now) {
        return new AllianceView(alliance.id(), alliance.name(), alliance.tag(), alliance.leaderId(),
                alliance.level(), alliance.exp(), alliance.effectiveMemberCap(), alliance.memberCount(),
                alliance.fund(), allianceTechViews(alliance),
                alliance.territoryCount(), alliance.territoryCap(),
                // roleOf 对非成员返回 null：以前写成 valueOf(String.valueOf(...)) 会把
                // 它变成字符串 "null" 然后抛 IllegalArgumentException —— 表现是「看一眼联盟
                // 就报错」，而访客本来就该看到一个没有自己职位的联盟
                toRoleView(alliance.roleOf(viewerId)),
                alliance.contributionOf(viewerId),
                alliance.donatedToday(viewerId, DayKey.of(now)),
                // 「今天还剩什么可捐」只有服务端知道（档位账本在它手上）。客户端自己数就是第二份真相
                alliance.donatedTiers(viewerId, DayKey.of(now)),
                alliance.donationDailyCap(),
                "", alliance.version(), now);
    }

    /**
     * 本盟已研究的科技。
     *
     * <p><b>只列研究过的</b>：{@code alliance_tech} 表本身是随包下发的配置，客户端自己能画出货架，
     * 它缺的只有「本盟到哪一级了」。把 8 行全量塞进联盟视图等于把表的副本再传一遍，
     * 而联盟视图是按 version 做 diff 同步的（B10 验收 10：不要让联盟数据每帧全量同步）。
     */
    /**
     * 联盟科技目录（B26 S9）。<b>0 级的那几项也要下发</b>：原来写成 `level <= 0 → continue`，
     * 于是一个刚建的盟拿到的是空列表 —— 玩家既看不见"这游戏有联盟科技"，也永远点不到
     * `/alliance/tech` 那颗按钮（有写口没发现口那一族的第四处）；而且旧版只给 id 不给名字，
     * 画出来就是一串 `atech_atk`。
     *
     * <p>`canResearch` 只判两条：<b>没到本盟上限</b> 与 <b>资金够下一级</b>，用的是与写口同一句
     * 比较（`researchCost(base, id, 1)`）—— 差一条就是"界面亮着、点下去回 ALLIANCE_FUND_LACK"。
     * 职位能不能研究不在这里判：那份结论在 `/social/permissions` 的 `RESEARCH_TECH` 里，
     * 两处各说一件事才不会互相打脸。
     */
    private List<com.ironoath.web.dto.generated.AllianceTechView> allianceTechViews(Alliance alliance) {
        List<com.ironoath.web.dto.generated.AllianceTechView> out = new ArrayList<>();
        for (com.ironoath.config.cfg.AllianceTechCfg tech
                : configs.all(com.ironoath.config.cfg.AllianceTechCfg.class)) {
            int level = alliance.techLevel(tech.id());
            int cap = alliance.techLevelCap((int) tech.maxLevel());
            long nextCost = alliance.researchCost(tech.costBaseDonation(), tech.id(), 1);
            boolean atCap = level >= cap;
            boolean affordable = alliance.fund() >= nextCost;
            out.add(new com.ironoath.web.dto.generated.AllianceTechView(tech.id(), level, cap,
                    tech.effectValue() * level, tech.name(), nextCost, !atCap && affordable,
                    atCap ? "本盟等级下已经研究到头了，提升联盟等级才能继续"
                            : affordable ? null : "联盟资金还不够，多捐一些就能研究"));
        }
        return List.copyOf(out);
    }

    /**
     * 联盟成员列表（走 /alliance/sync 的 diff 通道，验收 10）。
     *
     * <p><b>成员信息一次批量取回，不在循环里点查</b>：原先每人各一次 {@code findByPlayerId}
     * 与一次 {@code squadOf}，150 人的盟就是一次同步打 300 趟存储（其中 150 趟是整份玩家存档，
     * 收口清单 #425）。次数由 {@code AllianceMemberQueryCountTest} 按调用计数钉住 —— 结果本身
     * 两种写法一字不差，所以端点测试判不出这件事。
     */
    public List<AllianceMember> allianceMembers(String playerId, long now) {
        Optional<Alliance> found = store.allianceOf(playerId);
        if (found.isEmpty()) {
            return List.of();
        }
        Alliance alliance = found.get();
        List<String> memberIds = alliance.memberIds();
        // 走投影口而不是整档：成员行用的只有昵称、展示战力、最近活跃三项（收口清单 #425 的那一趟
        // 批量读省的是往返，这一格省的是 150 份整档的字节与反序列化）
        Map<String, PlayerBrief> briefs = players.findBriefs(memberIds);
        Map<String, Squad> squads = store.squadsOf(memberIds);
        List<AllianceMember> out = new ArrayList<>(memberIds.size());
        for (String memberId : memberIds) {
            PlayerBrief brief = briefs.get(memberId);
            Squad squad = squads.get(memberId);
            out.add(new AllianceMember(memberId,
                    brief == null ? memberId : brief.nickName(),
                    brief == null ? 0L : brief.displayPower(),
                    com.ironoath.web.dto.generated.AllianceRole.valueOf(
                            String.valueOf(alliance.roleOf(memberId))),
                    alliance.contributionOf(memberId),
                    brief == null ? now : brief.lastLoginAt(),
                    squad == null ? null : squad.id(),
                    // 名字跟着 id 一起下发：客户端没有小队表，只给 id 它就只能把 id 印给玩家（台账 #422）。
                    // 取不到小队就两个都是 null，不拿 id 顶一个"名字"上去。
                    squad == null ? null : squad.name()));
        }
        return out;
    }

    /**
     * 同一小队的其他成员（<b>不含自己</b>；没有小队时为空）。
     *
     * <p>给 B08 的复仇加成做「盟友」范围。只到小队而不到联盟：复仇账本记在每个玩家自己的存档上，
     * 查一个人就是一次读档，联盟上限 150 人会让每场 PVP 结算多出 150 次读档；
     * 而小队既是 C00 公理七那层「我和兄弟们」，也是 {@code notifyMemberAttacked} 的推送范围，
     * 语义上「一起挨过打的人」就是这一圈。理由写在 {@code CounterplayModifiers} 的调用点注释里。
     */
    public List<String> squadMateIds(String playerId) {
        Squad squad = store.squadOf(playerId).orElse(null);
        if (squad == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(squad.memberCount());
        for (String member : squad.memberIds()) {
            if (!member.equals(playerId)) {
                out.add(member);
            }
        }
        return out;
    }

    private SocialEventView toEventView(SocialStore.SocialEvent record, long now) {
        return new SocialEventView(record.eventId(),
                com.ironoath.web.dto.generated.SocialEventType.valueOf(record.type()),
                record.title(), record.body(),
                record.coordX() == null || record.coordY() == null
                        ? null : new SocialCoord(Math.toIntExact(record.coordX()),
                                Math.toIntExact(record.coordY())),
                record.relatedId(), record.occurredAt(), record.expiredAt(now));
    }

    private static ChatMessageView toMessageView(SocialStore.ChatMessage message) {
        return new ChatMessageView(message.messageId(),
                ChatChannel.valueOf(message.channel()), message.senderId(),
                message.senderName(), message.content(), message.sentAt());
    }

    // ================= 内部 =================

    private void pushToSquad(Squad squad, String excludeId, String type, String title,
                             String relatedId, long now) {
        for (String member : squad.memberIds()) {
            if (!member.equals(excludeId)) {
                store.pushEvent(member, event(type, title, null, relatedId, now));
            }
        }
    }

    private static SocialStore.SocialEvent event(String type, String title, String body,
                                                        String relatedId, long now) {
        return new SocialStore.SocialEvent(
                SocialEventIds.of("evt_" + type + "_" + relatedId + "_" + now), type, title, body,
                null, null, relatedId, now, now + EVENT_TTL_MILLIS);
    }

    /** 入盟/退盟时同步小队的分队身份（关键设计点 1）。 */
    private void detachSquad(String playerId, String allianceId) {
        store.squadOf(playerId).ifPresent(squad -> {
            if (allianceId.equals(squad.allianceId())) {
                long expectedSquadVersion = squad.version();
                squad.detachFromAlliance();
                store.saveSquad(squad, expectedSquadVersion);
            }
        });
    }

    /**
     * 联盟不复存在时把它从所属国家的成员表里摘掉（B13 §二冲突规则：联盟 ⊂ 国家）。
     *
     * <p><b>不能省</b>：国家成员表的键是 allianceId，而 {@code Nation.admitAlliance} 用
     * "在册联盟数 &lt; 上限"判名额。一个已经解散的联盟继续挂在册，等于<b>永久占掉一个联盟名额</b>
     * （上限本来是给活着的联盟用的），而它的议员席会一直挂在一个已经不存在的盟主身上。
     *
     * <p><b>冷却与主动退出国是同一条规则</b>：走 {@code Nation.removeAlliance(expelled=false)}，
     * 于是这个联盟的成员跟着 B13 验收 2 的入籍冷却 —— 这是对的：联盟没了导致全盟失去国籍，
     * 与联盟自己退出国，对下游国家来说后果本该一致。
     *
     * <p><b>写回失败就让整次解散失败</b>：这里不吞异常也不重试。吞掉的表现为"解散成功、国家里多个幽灵"，
     * 而那种状态没人会去清。
     */
    private void detachFromNation(String allianceId, java.util.Collection<String> allianceMemberIds,
                                  String actorId, long now) {
        nations.findByAlliance(allianceId).ifPresent(loaded -> {
            Nation nation = nationLeaders.bind(loaded);
            nation.removeAlliance(allianceId, false, actorId, now);
            // 结清排在国家写回<b>之前</b>（台账 #831 的口径：先结清再写，撞锁就整笔失败）：
            // 这条路径不持各成员的玩家锁，反过来写会留下「联盟已脱离、兵已退、集结还挂着」的半状态。
            // 亡国 ⇒ 整国取消；否则 ⇒ 只结清这一盟的成员（成员表必须在联盟自身解散之前取）。
            int settled = nation.isDisbanded()
                    ? cancelNationalRalliesOf(nation.id(), now)
                    : settleNationalRalliesForMembers(nation.id(), allianceMemberIds, now);
            nations.save(nation, nation.version());
            LOG.info("联盟解散连带出国家 allianceId={} nationId={} 原因=联盟已解散 剩余成员联盟={} 入籍冷却至={} 国家是否随之解散={} 结清集结={} 条",
                    allianceId, nation.id(), nation.memberAllianceCount(),
                    nation.joinCooldownUntil(allianceId), nation.isDisbanded(), settled);
        });
    }

    private int pendingInvitesOf(String playerId) {
        int count = 0;
        for (SocialStore.SocialEvent record : store.unreadEvents(playerId)) {
            if ("ALLIANCE_APPLIED".equals(record.type()) || "RALLY_INVITED".equals(record.type())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 有没有能帮的忙（红点树的注册点读它）。
     *
     * <p>刻意只做"委托 + 比大小"：判定全部在 {@link #helpBoard} 里。在这儿再写一遍循环
     * 就是刚在 #42 收掉的那种双份真相，而它的表现是「徽标亮着、点进去没事做」。
     */
    public boolean hasHelpable(String playerId, long now) {
        return helpBoard(playerId, now).pending() > 0;
    }

    /** 有没有待处理的邀请／申请（红点树注册点读它，同上）。 */
    public boolean hasPendingInvite(String playerId) {
        return pendingInvitesOf(playerId) > 0;
    }

    /** 有没有未读社交事件（红点树注册点读它，与摘要共用同一本未读账）。 */
    public boolean hasUnreadEvents(String playerId) {
        return !store.unreadEvents(playerId).isEmpty();
    }

    private void requirePermission(PermissionMatrix.Scope scope, Object role, String permission) {
        PermissionMatrix.Tier tier = tierOf(scope, String.valueOf(role));
        if (tier == null || !rules.permissions().allows(scope, tier, permission)) {
            // msg 不写「需要什么职位」：那等于在代码里硬编码一份权限（B10 禁止项）。
            // 缺哪个权限位放进 detail，那里是查表得出的
            throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED,
                    "scope=" + scope + " role=" + role + " 缺少权限位 " + permission);
        }
    }

    /**
     * 职位名 → 权限档位。角色枚举在 core 里，这里只按名字认，避免 web 层再依赖一次枚举类型。
     *
     * <p>认不出来的职位串一律按最保守的「无档位」处理，但必须留下 WARN：读路径上它会表现成
     * 「队长的权限面板空了」，而没有任何一处说明为什么；写路径上它原先会以
     * {@code IllegalArgumentException} 冲出 {@code requirePermission}，玩家看到的是 500 而不是
     * 权限拒绝。两个 scope 现在走同一条口径。
     */
    static PermissionMatrix.Tier tierOf(PermissionMatrix.Scope scope, String role) {
        if (role == null || "NONE".equals(role) || "null".equals(role)) {
            return null;
        }
        if (scope == PermissionMatrix.Scope.NATION) {
            // 国家职位 = 官职名；"在国里但没官职"报 MEMBER（这一档在 role_permission 表里就叫 member），
            // 不在任何国家里报 NONE。**联盟的职位串在这里认不出来**（映射在 NationPermissions）
            return com.ironoath.core.nation.NationPermissions.tierOfName(role);
        }
        try {
            return scope == PermissionMatrix.Scope.SQUAD
                    ? SquadRole.valueOf(role).tier()
                    : AllianceRole.valueOf(role).tier();
        } catch (IllegalArgumentException e) {
            LOG.warn("职位串无法识别，按无权限处理 scope={} role=\"{}\"（枚举改名或存档里残留旧值："
                    + "这条如果不响，症状是「队长的权限面板空了」，而没人知道为什么）", scope, role);
            return null;
        }
    }

    private Squad requireSquad(String playerId) {
        return store.squadOf(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.SQUAD_NOT_FOUND, "playerId=" + playerId));
    }

    private Alliance requireAlliance(String allianceId) {
        return store.allianceById(allianceId)
                .orElseThrow(() -> new BizException(ErrorCode.ALLIANCE_NOT_FOUND, "allianceId=" + allianceId));
    }

    private Alliance requireAllianceOf(String playerId) {
        return store.allianceOf(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.ALLIANCE_NOT_FOUND, "playerId=" + playerId));
    }

    private PlayerSave requirePlayer(String playerId) {
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId=" + playerId));
    }

    private int cityLevelOf(String playerId) {
        return players.findByPlayerId(playerId).map(PlayerSave::cityLevel).orElse(0);
    }

    /**
     * 昵称查询的对外出口。**给战报域上报击杀时借一个名字用**（榜上要显示昵称，而战报域
     * 没有玩家账户——再建一份查询就是同一件事的第二个家）。
     */
    public String nicknameOf(String playerId) {
        return nickname(playerId);
    }

    private String nickname(String playerId) {
        return players.findByPlayerId(playerId).map(PlayerSave::nickName).orElse(playerId);
    }

    /**
     * 开服天数。唯一实现在 {@link ServerCalendar} —— 这里原先与 {@code NationAppService}
     * 各持有一份逐字拷贝（已记录的欠账），漂移的症状是「联盟 D3 解锁而国家 D14 解锁」
     * 这两道门槛按不同口径生效。未配置 {@code SERVER_OPEN_AT} 时放行，理由见 ServerCalendar。
     */
    private long dayOffset(long now) {
        return ServerCalendar.daysSinceOpen(configs, now);
    }

    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "社交域的写操作必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ================= 集结（B10 §5） =================

    /**
     * 发起小队集结。
     *
     * <p><b>发起人的兵力在创建时就承诺并锁定</b>：{@code Rally.initiate} 需要发起人的兵才能建出
     * 第一个参与者，而发起人一旦成为参与者就不能再 join 自己的集结
     * （领域层会以「重复加入会让同一个人的兵被算两遍」拒绝）—— 所以创建请求里的 troops
     * 是发起人承诺兵力的唯一入口，这也是它必填的原因。
     */
    public RallyResp squadRally(String playerId, SquadRallyReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Squad squad = store.squadOf(playerId).orElseThrow(() -> new BizException(
                        ErrorCode.SQUAD_NOT_FOUND, "你不在任何小队里，无法发起小队集结"));
                requirePermission(PermissionMatrix.Scope.SQUAD,
                        squad.leaderId().equals(playerId) ? "LEADER" : "MEMBER",
                        "START_RALLY");
                int squadSize = squad.memberIds().size();
                int maxSize = Math.min(rules.rallyMaxSize(Rally.Scope.SQUAD), squadSize);
                Rally rally = initiateRally(playerId, Rally.Scope.SQUAD, squad.id(),
                        req.targetCoord(), req.targetType(), troopsOf(req.troops()),
                        req.heroes(), maxSize, 0L, now);
                LOG.info("小队集结已发起 rallyId={} squadId={} 发起人={} 承诺兵力={} 人数上限={}",
                        rally.rallyId(), squad.id(), playerId, rally.totalTroops(), maxSize);
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * GET /rally/policy（B26 S13 + V22-a）：小队、联盟、国家三份集结政策一次给全。
     *
     * <p>存在的理由与 {@code /social/createPolicy} 同一条：集结要收 {@code maxMembers}
     * 与 {@code prepareMinutes} 两个数，而它们的上下界都在 global 表里。客户端不许抄表，
     * 也不许自己挑默认值 —— 否则滑条显示 30 人、服务端悄悄夹成 4 人，玩家以为自己设过了。
     *
     * <p>{@code maxMembers} 给的是<b>此刻</b>的上限：取「配置上限」与「我这个组织实际人数」
     * 的小值 —— 与 {@link #allianceRally} 里那句夹取同一个式子。写口夹什么，读口就说什么，
     * 两条不一致时界面上亮着的数字就是假的。国家那一层的同一个式子收在
     * {@link #nationRallyCap(String)} 这一个折叠点里，读口与写口都调它。
     */
    public RallyPolicyResp rallyPolicy(String playerId, long now) {
        return new RallyPolicyResp(squadPolicy(playerId, now), alliancePolicy(playerId),
                nationPolicy(playerId), now);
    }

    /** 小队那一层的政策。组织不在、职位不够、人数不够，都在这一个地方说成一句人话。 */
    private RallyPolicyView squadPolicy(String playerId, long now) {
        Rally.Rules rulesOf = this.rules.squadRallyRules();
        int cap = this.rules.rallyMaxSize(Rally.Scope.SQUAD);
        Squad squad = store.squadOf(playerId).orElse(null);
        if (squad == null) {
            return policyView(rulesOf, cap, false, "你还没有小队，先加入或建一支再发起集结");
        }
        if (!mayStartRally(PermissionMatrix.Scope.SQUAD, String.valueOf(squad.roleOf(playerId)))) {
            return policyView(rulesOf, cap, false, "你当前的职位不能发起集结");
        }
        int room = Math.min(cap, squad.memberCount());
        if (room < rulesOf.minMembers()) {
            return policyView(rulesOf, cap, false,
                    "小队现在只有 " + squad.memberCount() + " 个人，凑不满一次集结的最少人数");
        }
        return policyView(rulesOf, room, true, null);
    }

    /** 联盟那一层。口径与小队完全一致：先看在不在，再看职位，最后看人数。 */
    private RallyPolicyView alliancePolicy(String playerId) {
        Rally.Rules rulesOf = this.rules.allianceRallyRules();
        int cap = this.rules.rallyMaxSize(Rally.Scope.ALLIANCE);
        Alliance alliance = store.allianceOf(playerId).orElse(null);
        if (alliance == null) {
            return policyView(rulesOf, cap, false, "你还没有联盟，先申请加入或建一个再发起集结");
        }
        if (!mayStartRally(PermissionMatrix.Scope.ALLIANCE, String.valueOf(alliance.roleOf(playerId)))) {
            return policyView(rulesOf, cap, false, "你当前的职位不能发起集结");
        }
        int room = Math.min(cap, alliance.memberIds().size());
        if (room < rulesOf.minMembers()) {
            return policyView(rulesOf, cap, false,
                    "联盟现在只有 " + alliance.memberIds().size() + " 个人，凑不满一次集结的最少人数");
        }
        return policyView(rulesOf, room, true, null);
    }

    /** 权限位查表，与写口用的是同一份 role_permission（B10 验收 4：新增角色只改配置）。 */
    private boolean mayStartRally(PermissionMatrix.Scope scope, String role) {
        PermissionMatrix.Tier tier = tierOf(scope, role);
        return tier != null && rules.permissions().allows(scope, tier, "START_RALLY");
    }

    private static RallyPolicyView policyView(Rally.Rules rulesOf, int maxMembers, boolean canStart,
                                             String reason) {
        long minMinutes = rulesOf.prepareMinMillis() / 60_000L;
        long maxMinutes = rulesOf.prepareMaxMillis() / 60_000L;
        // 起始值给最长那一档：集结成败取决于等人，配置的最大窗口就是设计者认定的「值得等」的上限
        return new RallyPolicyView(rulesOf.minMembers(), Math.max(maxMembers, rulesOf.minMembers()),
                (int) minMinutes, (int) maxMinutes, (int) maxMinutes, canStart, reason);
    }

    /** 此刻这个玩家所属的、还没解散的国家。亡国在这一处就断掉：它已经不是能发起集结的组织。 */
    private java.util.Optional<Nation> activeNationOf(String playerId) {
        return this.membership.ofPlayer(playerId).filter(nation -> !nation.isDisbanded());
    }

    /** 写口用的国家档：不在任何国家（或国家已解散）就是要报的错，不是"上限 0"。 */
    private Nation requireNationOf(String playerId) {
        return activeNationOf(playerId).orElseThrow(() ->
                new BizException(ErrorCode.NATION_NOT_FOUND,
                        "playerId=" + playerId + " 不在任何国家里，或所属国家已解散"));
    }

    /**
     * 国家层集结人数上限的<b>唯一折叠点</b>（V24-D1：上限是一个函数，不是三个数）。
     *
     * <p>式子 = {@code min(配置上限 RALLY_MAX_SIZE_NATION, 本国此刻实有人数)}。
     * 写口（{@link #nationRally} 发起时夹 maxMembers）与读口（{@link #nationPolicy} 亮给玩家的那个
     * 数字）<b>都调这一个方法</b>，两边各算一遍就会分叉 —— 上面 {@code rallyPolicy} 的注释写的
     * 「写口夹什么，读口就说什么」在这里是靠这一个方法成立的，不是靠两边写得像。
     *
     * <p>⚠️ 本格（V22）到此为止；V24 要在<b>这一个点</b>上继续叠「科技万分比」与「已购永久格」，
     * 所以这里不许出现字面量 50。
     *
     * <p><b>留给 V24 的一处已知不一致</b>（不在本格修，因为改了会把"能不能发起"的既有口径整层挪动）：
     * {@link #nationPolicy} 的三条<b>灰键分支</b>仍单独读 {@code rallyMaxSize(NATION)} 拿配置原值 ——
     * 这是照小队与联盟两层写的（那两层的失败分支同样给配置原值而不是折叠值）。灰键时那个数不可操作，
     * 所以现在没有症状；但 V24 把上限抬起来之后，"职位不够的国民看到的上限"会与"他升到官职后真能设的
     * 上限"分叉，而<b>现有用例抓不到这一条</b>（那时 canStart=false，界面上的数字不参与任何断言）。
     *
     * <p>国家人数走 {@code NationMembership.playerIdsOf}：那是全国花名册的唯一实现（国战发奖与
     * 击杀归属都调它），代价是一次全联盟批量读。在本类里另写一遍盟→人的展开，症状是
     * 「集结上限用的国人数与发奖用的国人数不是一个口径」—— {@code alliance_tech.json:5} 记的
     * 那起「一个数字两个家」事故正是这个形状。
     *
     * @return 此刻可给这个玩家的国家集结用的人数上限；不在任何国家时回 0
     */
    int nationRallyCap(String playerId) {
        return activeNationOf(playerId)
                .map(nation -> Math.min(this.rules.rallyMaxSize(Rally.Scope.NATION),
                        this.membership.playerIdsOf(nation).size()))
                .orElse(0);
    }

    /**
     * 国家那一层的政策。口径与小队/联盟完全一致：先看在不在，再看职位，最后看人数。
     *
     * <p>职位读的是 {@link #nationRoleOf}（它会先 {@code NationLeaders.bind} 再问官职），
     * 而不是直接问官职 —— 议员是派生席位，少一次 bind 时议员会掉回 MEMBER 档，
     * 而两者权限恰好相同，症状只在按钮亮不亮上，很难归因。
     */
    private RallyPolicyView nationPolicy(String playerId) {
        Rally.Rules rulesOf = this.rules.nationRallyRules();
        int cap = this.rules.rallyMaxSize(Rally.Scope.NATION);
        if (activeNationOf(playerId).isEmpty()) {
            return policyView(rulesOf, cap, false, "你还没有国家，先让联盟加入一个国家再发起集结");
        }
        if (!mayStartRally(PermissionMatrix.Scope.NATION, nationRoleOf(playerId))) {
            return policyView(rulesOf, cap, false, "你当前的职位不能发起集结");
        }
        int room = nationRallyCap(playerId);
        if (room < rulesOf.minMembers()) {
            return policyView(rulesOf, cap, false,
                    "国家现在只有 " + room + " 个人，凑不满一次集结的最少人数");
        }
        return policyView(rulesOf, room, true, null);
    }

    /** 发起联盟集结。人数上限与准备时长都按配置夹住，越界不拒绝（理由见 Rally.initiate 的注释）。 */
    public RallyResp allianceRally(String playerId, AllianceRallyReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Alliance alliance = requireAllianceOf(playerId);
                long expectedAllianceVersion = alliance.version();
                requirePermission(PermissionMatrix.Scope.ALLIANCE, alliance.roleOf(playerId),
                        "START_RALLY");
                int maxSize = Math.min(Math.max(req.maxMembers(), rules.allianceRallyRules().minMembers()),
                        Math.min(rules.rallyMaxSize(Rally.Scope.ALLIANCE), alliance.memberIds().size()));
                Rally rally = initiateRally(playerId, Rally.Scope.ALLIANCE, alliance.id(),
                        req.targetCoord(), req.targetType(), troopsOf(req.troops()),
                        req.heroes(), maxSize, req.prepareMinutes() * 60_000L, now);
                LOG.info("联盟集结已发起 rallyId={} allianceId={} 发起人={} 承诺兵力={} 人数上限={} 准备={}分钟",
                        rally.rallyId(), alliance.id(), playerId, rally.totalTroops(), maxSize,
                        rally.prepareMillis() / 60_000L);
                // 事件触发的聊天（B11 §四 RALLY_CALL）：真的开出集结了，发起人喊一句。
                // 发在 return 之前而不是之后：之后那句在锁外，而事件监听器要走一次 chatSend
                // （自身不带锁，但它读联盟成员表，越早发越不容易和锁竞争）
                events.publishEvent(new com.ironoath.web.bot.BotChatEvent(playerId, playerId,
                        com.ironoath.core.bot.BotChatBook.Scene.RALLY_CALL, now));
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 发起国家层集结（V22-a，B13 §46 大将军「发起国战、调动集结」）。
     *
     * <p>形状与 {@link #allianceRally} 一致，三处不同：权限读 NATION 档的 {@code START_RALLY}、
     * groupId 用国家 id、人数上限从 {@link #nationRallyCap} 取而不是在这里再算一遍。
     */
    public RallyResp nationRally(String playerId, NationRallyReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Nation nation = requireNationOf(playerId);
                requirePermission(PermissionMatrix.Scope.NATION, nationRoleOf(playerId),
                        "START_RALLY");
                int maxSize = Math.min(Math.max(req.maxMembers(), rules.nationRallyRules().minMembers()),
                        nationRallyCap(playerId));
                Rally rally = initiateRally(playerId, Rally.Scope.NATION, nation.id(),
                        req.targetCoord(), req.targetType(), troopsOf(req.troops()),
                        req.heroes(), maxSize, req.prepareMinutes() * 60_000L, now);
                LOG.info("国家集结已发起 rallyId={} nationId={} 发起人={} 承诺兵力={} 人数上限={} 准备={}分钟",
                        rally.rallyId(), nation.id(), playerId, rally.totalTroops(), maxSize,
                        rally.prepareMillis() / 60_000L);
                // 与联盟层同一句 RALLY_CALL：集结开出来了，发起人在自己的频道喊一句
                events.publishEvent(new com.ironoath.web.bot.BotChatEvent(playerId, playerId,
                        com.ironoath.core.bot.BotChatBook.Scene.RALLY_CALL, now));
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 加入集结并承诺兵力（承诺即锁定：当场从城内军队扣除）。 */
    public RallyResp rallyJoin(String playerId, RallyJoinReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Rally rally = requireRally(req.rallyId(), now);
                long expectedRallyVersion = rally.version();
                Map<String, Long> troops = troopsOf(req.troops());
                if (troops.isEmpty()) {
                    throw new BizException(ErrorCode.RALLY_NO_TROOP, "加入集结必须承诺兵力");
                }
                // 与个人出征共用同一个校验入口：归属、去重、上限。必须在扣兵之前
                heroAppService.requireOnMarch(playerId, req.heroes());
                requireMembership(playerId, rally);
                requireJoinPermission(playerId, rally);
                commitTroops(playerId, troops);
                try {
                    rally.join(playerId, troops, req.heroes());
                } catch (IllegalStateException e) {
                    // 领域层拒绝（已满 / 重复加入）：扣掉的兵必须退回来，
                    // 否则玩家会看到「兵少了但集结里没有我」
                    refundTroops(playerId, troops);
                    throw new BizException(errorOfJoinFailure(e.getMessage()), e.getMessage());
                }
                store.saveRally(rally, expectedRallyVersion);
                LOG.info("加入集结 rallyId={} playerId={} 承诺兵力={} 当前人数={}/{}",
                        rally.rallyId(), playerId, troops, rally.joinedCount(), rally.maxMembers());
                // 参战算一次（B12 §1 的 JOIN_RALLY）：周常要的正是"组织行为"这件事
                questEvents.progress(playerId, com.ironoath.core.quest.GoalType.JOIN_RALLY, null, 1L, now);
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 退出集结，承诺的兵力原路退回。发起人退出等于取消（没有人能替他指出兵）。 */
    public RallyResp rallyQuit(String playerId, RallyJoinReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Rally rally = requireRally(req.rallyId(), now);
                long expectedRallyVersion = rally.version();
                Rally.Participant mine = rally.participant(playerId);
                if (mine == null) {
                    throw new BizException(ErrorCode.RALLY_NOT_FOUND, "你不是这次集结的参与者");
                }
                boolean wasInitiator = rally.initiatorId().equals(playerId);
                rally.quit(playerId);
                if (wasInitiator) {
                    // 发起人退出 ⇒ 整个集结取消 ⇒ 所有人的兵都要退
                    refundAll(rally);
                } else {
                    refundTroops(playerId, mine.troops());
                }
                store.saveRally(rally, expectedRallyVersion);
                LOG.info("退出集结 rallyId={} playerId={} 发起人退出={} 退回兵力={} 新状态={}",
                        rally.rallyId(), playerId, wasInitiator, mine.troops(), rally.status());
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 取消集结（只有发起人）。所有参与者的兵力原路退回。 */
    public RallyResp rallyCancel(String playerId, RallyJoinReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                Rally rally = requireRally(req.rallyId(), now);
                long expectedRallyVersion = rally.version();
                if (!rally.initiatorId().equals(playerId)) {
                    throw new BizException(ErrorCode.RALLY_NOT_INITIATOR, "只有发起人能取消这次集结");
                }
                rally.cancel(playerId);
                refundAll(rally);
                store.saveRally(rally, expectedRallyVersion);
                LOG.info("集结已取消 rallyId={} 发起人={} 退回人数={}",
                        rally.rallyId(), playerId, rally.memberIds().size());
                return new RallyResp(toRallyView(rally, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 结清一支准备中的集结：状态迁移 + 带版本写回 + 退兵；撞乐观锁时<b>重读重试一次</b>。
     *（裁决 2026-10-08，台账 #831：结清要么成、要么整笔失败，不许留下「兵已回家、
     * 集结还挂在面板上」这种半状态 —— 这条路径不持玩家锁，而 {@code refundTroops} 与
     * {@code store.saveRally} 都是带版本写，同一玩家的另一条请求插进来就会撞。）
     *
     * <p><b>退款排在写回成功之后</b>：写回抛了就整笔不动；重试那份是新读到的副本，所以不会重复入账。
     * 第二次仍撞锁就让异常冒出去，由调用方那一笔请求整体失败（调用方已把结清排在国家写回之前）。
     */
    private void settleOneRally(String rallyId, Rally rally, String quitterId, long now) {
        Rally current = rally;
        for (int attempt = 1; ; attempt++) {
            Rally.Participant mine = quitterId == null ? null : current.participant(quitterId);
            if (quitterId != null && mine == null) {
                return; // 重试那一趟里他已被取消动作清掉，不该再退一次
            }
            boolean asInitiator = quitterId == null || current.initiatorId().equals(quitterId);
            long expectedVersion = current.version();
            if (asInitiator) {
                current.cancel(current.initiatorId());
            } else {
                current.quit(quitterId);
            }
            try {
                store.saveRally(current, expectedVersion);
            } catch (IllegalStateException conflict) {
                if (attempt >= 2) {
                    throw conflict;
                }
                current = requireRally(rallyId, now);
                LOG.warn("集结结清撞乐观锁，重读后重试 rallyId={} 第{}趟", rallyId, attempt);
                continue;
            }
            if (asInitiator) {
                refundAll(current);
            } else {
                refundTroops(quitterId, mine.troops());
            }
            return;
        }
    }

    /**
     * （V22 验收④，2026-10-08 裁决「亡国即取消并退兵」）。
     *
     * <p><b>为什么由 {@code NationAppService.disband} 显式调，而不是靠惰性扫描</b>：
     * {@link #expireIfDue} 只在有人去读/驱动那一支集结时才跑。亡国是一个**已经发生的确定事件**，
     * 靠惰性扫描的结果是：兵一直锁着，既不回家也不出发，直到某个玩家恰好又打开集结面板为止 ——
     * 而亡国之后那个面板也再也列不出这一支（{@code activeNationOf} 会过滤掉已解散的国），
     * 于是它变成一个**没人能看见、却占着玩家兵力**的第三种状态。服务端禁常驻定时器（红线），
     * 所以确定事件必须由触发它的那一手处理。
     *
     * <p>取消与退款都走既有那一条路（{@code rally.cancel} + {@link #refundAll}，与发起人主动取消
     * 同一份实现），这里不重算退款口径；操作者传发起人本人 —— 这不是玩家动作，
     * 而是国家这个组织已经不存在了，所以不走"只有发起人能取消"那道权限判断。
     *
     * @return 被取消的集结数（给日志与用例断言用；0 是正常情况）
     */
    public int cancelNationalRalliesOf(String nationId, long now) {
        int cancelled = 0;
        for (Rally rally : List.copyOf(store.preparingRalliesOf(nationId))) {
            if (rally.scope() != Rally.Scope.NATION) {
                continue;
            }
            settleOneRally(rally.rallyId(), rally, null, now);
            cancelled += 1;
            LOG.info("亡国取消集结 rallyId={} nationId={} 发起人={} 退回人数={} 时刻={}",
                    rally.rallyId(), nationId, rally.initiatorId(), rally.memberIds().size(), now);
        }
        return cancelled;
    }

    /**
     * 退国结算：把这批玩家在该国「准备中」的国家集结结清（裁决 2026-10-08：只补退国，被开除留后）。
     *
     * <p><b>为什么不能整国取消</b>：退国走的是「一个联盟退出国家」，而这个国里可能还有别的联盟；
     * 把别人的集结一起取消等于让一次退国伤到无关的人 —— 那与亡国（国家已不存在）不是同一种情况。
     *
     * <p><b>两种身份分两条退法</b>（与 {@code /rally/quit} 那条端点同形，保证两处行为一致）：
     * 发起人退出 ⇒ 整个集结取消、所有人的兵都退；普通参与者退出 ⇒ 只退他这一份，集结继续等人。
     */
    public int settleNationalRalliesForMembers(String nationId, java.util.Collection<String> playerIds,
                                               long now) {
        int settled = 0;
        for (String playerId : playerIds) {
            for (Rally rally : List.copyOf(store.preparingRalliesOf(nationId))) {
                if (rally.scope() != Rally.Scope.NATION) {
                    continue;
                }
                Rally.Participant mine = rally.participant(playerId);
                if (mine == null) {
                    continue;
                }
                boolean wasInitiator = rally.initiatorId().equals(playerId);
                settleOneRally(rally.rallyId(), rally, playerId, now);
                settled += 1;
                LOG.info("退国结清集结 rallyId={} 国家={} 玩家={} 本人是发起人={} 退回兵力={} 新状态={}",
                        rally.rallyId(), nationId, playerId, wasInitiator, mine.troops(), rally.status());
            }
        }
        return settled;
    }

    /** 集结详情。到点的集结会先被处理（见 {@link #expireIfDue}），所以这里返回的状态永远是当前的。 */
    public RallyResp rallyView(String playerId, String rallyId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            Rally rally = requireRally(rallyId, now);
            return new RallyResp(toRallyView(rally, now), now);
        });
    }

    /**
     * 某个组织（小队 / 联盟 / 国家）里进行中的集结，给面板列表用。
     *
     * <p><b>三支都要有</b>：这一份是面板唯一的列表来源，少一支的症状不是报错而是"看不见的集结"——
     * 国家层能发起（{@code /rally/nation} 返回 200 与 rallyId），但面板永远列不出那一次，
     * 玩家会以为集结没发出去、再发一次，兵也被再锁一次。
     */
    public RallyListResp preparingRallies(String playerId) {
        long now = timeService.serverNow();
        List<RallyView> out = new ArrayList<>();
        store.squadOf(playerId).ifPresent(squad -> {
            for (Rally rally : store.preparingRalliesOf(squad.id())) {
                expireIfDue(rally, now);
                out.add(toRallyView(rally, now));
            }
        });
        store.allianceOf(playerId).ifPresent(alliance -> {
            for (Rally rally : store.preparingRalliesOf(alliance.id())) {
                expireIfDue(rally, now);
                out.add(toRallyView(rally, now));
            }
        });
        activeNationOf(playerId).ifPresent(nation -> {
            for (Rally rally : store.preparingRalliesOf(nation.id())) {
                expireIfDue(rally, now);
                out.add(toRallyView(rally, now));
            }
        });
        return new RallyListResp(List.copyOf(out), now);
    }

    // ---------- 集结内部 ----------

    private Rally initiateRally(String playerId, Rally.Scope scope, String groupId, SocialCoord coord,
                               SocialTargetType targetType, Map<String, Long> troops,
                               List<String> heroes, int maxSize, long requestedPrepareMillis, long now) {
        if (troops.isEmpty()) {
            throw new BizException(ErrorCode.RALLY_NO_TROOP, "发起集结必须承诺兵力");
        }
        if (coord == null || targetType == null) {
            throw new BizException(ErrorCode.RALLY_PREPARE_INVALID, "集结必须指定目标坐标与目标类型");
        }
        // 武将校验排在扣兵之前：被拒时一个兵都不该动（与个人出征同一条纪律）
        heroAppService.requireOnMarch(playerId, heroes);
        // 三档各取自己那一份规则，一次算好往下传。原先这里写的是
        // `scope == SQUAD ? squad : alliance` 两分支，国家层会静默拿到联盟的下限与准备时长
        // —— 不报错，只是用错一档，且两处（下限、Rally.initiate）都错。
        Rally.Rules rulesOf = switch (scope) {
            case SQUAD -> rules.squadRallyRules();
            case ALLIANCE -> rules.allianceRallyRules();
            case NATION -> rules.nationRallyRules();
        };
        int minMembers = rulesOf.minMembers();
        if (maxSize < minMembers) {
            // 组织本身就没那么多人：这种情况必须说清楚，而不是建一个永远出不了发的集结
            throw new BizException(ErrorCode.RALLY_MEMBER_NOT_ENOUGH,
                    "集结至少需要 " + minMembers + " 人，而当前组织只有 " + maxSize + " 人");
        }
        // 圈层校验按集结人数放宽（B08 的 √N）：此刻还不知道最终会有几个人，
        // 所以按上限算 —— 上限来自配置而不是发起人填的数字，否则填个大数就能绕过圈层
        // 协议侧已经对齐成与 world 的 Coord 同一个 int32（由 SocialContractParityTest 钉住），
        // 所以这里不再需要窄化；世界只有 512×512，越界坐标由 guardRally 判掉
        attackGuard.guardRally(playerId,
                com.ironoath.core.world.Coord.of(coord.x(), coord.y()), maxSize, now);
        commitTroops(playerId, troops);
        Rally rally;
        try {
            rally = Rally.initiate(rallyIdOf(playerId, now), scope, groupId, playerId,
                    troops, heroes, maxSize, requestedPrepareMillis, now,
                    rulesOf,
                    coord.x(), coord.y(), targetType.name());
        } catch (IllegalArgumentException e) {
            // 只有这一支才是"发起人填的数不合法"（准备时长、人数下限、缺目标）—— 保持原错误码
            refundTroops(playerId, troops);
            throw new BizException(ErrorCode.RALLY_PREPARE_INVALID, e.getMessage());
        }
        try {
            // 创建集结：expectedVersion=0，库里已有同 id 才是冲突。
            // 原先的注释写着"rallyId 里已带 now，正常不会撞"—— 那句话是错的：同一名玩家在同一毫秒
            // 内连发两次（双击、或连着发两个目标）算出来的 id 一模一样，撞号后抛
            // IllegalStateException，被旧写法兜成 10057「准备时长不在允许区间内」⇒
            // 玩家听到的理由和被拒的真实原因不是一句话（台账 #802，红证据见 NationRallyEndpointTest）。
            store.saveRally(rally, 0L);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 建档冲突是内部一致性问题，不是业务校验失败 ⇒ 报"系统繁忙"这一档，别借 10057 那张嘴
            // （借来的那句会让玩家去改准备时长，而改多少都没用）。
            // 句式和 ExileAppService:150 / SeasonSettlementService:207 那两处 SYSTEM_ERROR 保持一致：
            // 先给人话与"请重试"，内部原因跟在后面给工单与日志用。
            LOG.warn("集结建档冲突（内部 id 撞号）playerId={} 原因={}", playerId, e.getMessage());
            refundTroops(playerId, troops);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "这次集结没能开出来，请重试一次（内部原因：" + e.getMessage() + "）");
        }
        return rally;
    }

    /**
     * 集结 id：**唯一性不靠时间戳**。
     *
     * <p>原先是 {@code rally_<playerId>_<now>}，同一玩家同一毫秒内两次发起就会算出同一个 id，
     * 建档那一侧（{@code expectedVersion<=0}）当场拒绝 ⇒ 第二个人（或他自己的第二次）被误报成业务错。
     * 毫秒仍然留在 id 里，因为日志与工单都按它 grep；后面接一段随机尾保证唯一
     * （同一份文件里 {@code msg_} / {@code report_} 早就是这么做的，见 :1587 与 :1774）。
     *
     * <p>{@code public} 只为了跨包可测（用例在 {@code com.ironoath.web}，本类在 {@code ...web.service}）：
     * 这是一个纯函数，暴露它比用反射撬开更诚实，且"同输入必须不同输出"这条不变量正是要被钉住的东西。
     */
    public static String rallyIdOf(String playerId, long now) {
        return "rally_" + playerId + "_" + now + "_"
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 到点处理的<b>一半</b>：人数不足 ⇒ 退款并取消；人数够 ⇒ 什么都不做。
     *
     * <p><b>为什么人数够时不再取消</b>：出发要建一支合并行军，而行军归 {@code MarchAppService} 管
     * （速度、负载、时长、到期登记、迷雾解锁都在它那里）。本类去建行的话就成了
     * SocialAppService → MarchAppService → SocialAppService 的环，Spring 起不来。
     * 所以出发的驱动点在 {@code MarchAppService.departDueRallies}，这里只留下「凑不够人」这一支。
     *
     * <p>保持 PREPARING 不是漏处理：成员的兵已经锁定，等下一次行军到期扫描就会出发。
     * 面板上这次集结会显示为「倒计时已归零但仍准备中」，那正是「正在集合」的语义。
     */
    private void expireIfDue(Rally rally, long now) {
        if (rally.status() != Rally.Status.PREPARING || !rally.dueAt(now)) {
            return;
        }
        if (rally.joinedCount() >= rally.minMembersRequired()) {
            return;
        }
        refundInsufficient(rally);
    }

    /**
     * 结算一次到点的集结，供行军域在出发时调用（B10 验收 11：倒计时结束统一出发、兵力合并）。
     *
     * <p><b>返回 empty 的三种情况都必须什么都不建</b>：没到点、人数不足（已退款取消）、
     * 以及并发下别的线程已经把它出发（{@code depart} 会以状态冲突拒绝第二次）。
     * 少判一种就会出现「同一个集结出两支合并行军」，而成员的兵只被扣了一次。
     *
     * @return 出发结果（合并后的兵力）；本次没有出发时为 empty
     */
    public java.util.Optional<Rally.Departure> settleDueRally(Rally rally, long now) {
        long expectedRallyVersion = rally.version();
        if (rally.status() != Rally.Status.PREPARING || !rally.dueAt(now)) {
            return java.util.Optional.empty();
        }
        if (rally.joinedCount() < rally.minMembersRequired()) {
            refundInsufficient(rally);
            return java.util.Optional.empty();
        }
        try {
            Rally.Departure departure = rally.depart(now);
            store.saveRally(rally, expectedRallyVersion);
            LOG.info("集结出发 rallyId={} 发起人={} 参与人数={} 合并兵力={} 目标=({},{}) 类型={}",
                    rally.rallyId(), rally.initiatorId(), departure.memberCount(),
                    departure.mergedTroops(), rally.targetX(), rally.targetY(), rally.targetType());
            return java.util.Optional.of(departure);
        } catch (IllegalStateException e) {
            // 只可能是并发下另一条线程已经出发过：那次的行军由它负责建，这里静默让路。
            // 绝不能在这里退款 —— 兵已经跟着那一支行军出门了
            LOG.info("集结已被另一次扫描出发，本次让路 rallyId={} 原因={}", rally.rallyId(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 撤销一次出发：合并行军没建起来时把成员的兵原路退回。
     *
     * <p><b>这是最后一道防线，正常情况下不会被调用</b>。没有它的话，
     * 「出发成功但建行军失败」会把成员的兵永久锁在一次没有行军的集结里 ——
     * 集结已 DEPARTED，quit/cancel/退款三条路都只认 PREPARING，一个都走不到。
     */
    public void abortDeparture(String rallyId) {
        Rally rally = store.rallyOf(rallyId).orElse(null);
        if (rally == null) {
            return;
        }
        long expectedRallyVersion = rally.version();
        refundAll(rally);
        rally.abortDeparted();
        store.saveRally(rally, expectedRallyVersion);
        LOG.error("集结 {} 已出发但合并行军未建立，已撤销出发并退回 {} 名成员的兵力",
                rallyId, rally.memberIds().size());
    }

    /** 人数不足到点：所有人的兵原路退回，集结取消（发起人自己的兵也在 participants 里，一起退）。 */
    private void refundInsufficient(Rally rally) {
        long expectedRallyVersion = rally.version();
        int members = rally.memberIds().size();
        refundAll(rally);
        rally.cancel(rally.initiatorId());
        store.saveRally(rally, expectedRallyVersion);
        LOG.warn("集结到点时人数不足：{} 人（下限 {}），已把各人承诺的兵力原路退回并取消 rallyId={}",
                members, rally.minMembersRequired(), rally.rallyId());
    }

    private void refundAll(Rally rally) {
        for (String member : rally.memberIds()) {
            Rally.Participant participant = rally.participant(member);
            if (participant != null) {
                refundTroops(member, participant.troops());
            }
        }
    }

    /**
     * 承诺兵力：当场从城内军队扣除。
     *
     * <p><b>先全量校验再扣</b>：扣到一半才发现第二种兵不够，玩家就会看到
     * 「第一种兵少了，集结里却没有我」。{@code ArmyState.deduct} 是逐个 unitId 扣的，
     * 没有事务，所以校验必须发生在第一次扣之前。
     */
    private void commitTroops(String playerId, Map<String, Long> troops) {
        com.ironoath.core.army.ArmyState army = armies.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.RALLY_NO_TROOP, "你还没有军队，无法承诺兵力"));
        for (Map.Entry<String, Long> entry : troops.entrySet()) {
            long have = army.countOf(entry.getKey());
            if (have < entry.getValue()) {
                throw new BizException(ErrorCode.RALLY_NO_TROOP,
                        "兵力不足：" + entry.getKey() + " 需要 " + entry.getValue() + "，当前 " + have);
            }
        }
        long version = armies.versionOf(playerId);
        troops.forEach(army::deduct);
        armies.save(playerId, army, version);
    }

    /** 退回承诺的兵力（退出 / 取消 / 到点未出发）。 */
    private void refundTroops(String playerId, Map<String, Long> troops) {
        if (troops == null || troops.isEmpty()) {
            return;
        }
        com.ironoath.core.army.ArmyState army = armies.findByPlayerId(playerId).orElse(null);
        if (army == null) {
            army = new com.ironoath.core.army.ArmyState();
            armies.insertIfAbsent(playerId, army);
            army = armies.findByPlayerId(playerId).orElseThrow();
        }
        long version = armies.versionOf(playerId);
        troops.forEach(army::add);
        armies.save(playerId, army, version);
    }

    private Rally requireRally(String rallyId, long now) {
        if (rallyId == null || rallyId.isBlank()) {
            throw new BizException(ErrorCode.RALLY_NOT_FOUND, "rallyId 不得为空");
        }
        Rally rally = store.rallyOf(rallyId)
                .orElseThrow(() -> new BizException(ErrorCode.RALLY_NOT_FOUND, "集结不存在或已结束: " + rallyId));
        expireIfDue(rally, now);
        if (rally.status() == Rally.Status.CANCELLED) {
            throw new BizException(ErrorCode.RALLY_NOT_FOUND, "集结已取消: " + rallyId);
        }
        return rally;
    }

    /** 只有同一个组织的成员能加入：否则任何人构造一个 rallyId 就能把兵塞进别人的集结。 */
    private void requireMembership(String playerId, Rally rally) {
        boolean member = switch (rally.scope()) {
            case SQUAD -> store.squadOf(playerId)
                    .map(squad -> squad.id().equals(rally.groupId())).orElse(false);
            case ALLIANCE -> store.allianceOf(playerId)
                    .map(alliance -> alliance.id().equals(rally.groupId())).orElse(false);
            // 国家层的归属 = 我这个联盟所属的国家，正好是那次集结的 groupId。
            // 这一支原先写死 false：国家集结建得出来，但<b>任何人都加不进去</b>
            // （含发起人自己重进面板），症状是"发出去了却没人能进"，而且不报错。
            // 亡国按"不在国家里"处理，与 nationRoleOf 同一口径。
            case NATION -> activeNationOf(playerId)
                    .map(nation -> nation.id().equals(rally.groupId())).orElse(false);
        };
        if (!member) {
            throw new BizException(ErrorCode.RALLY_NOT_FOUND,
                    "这次集结属于另一个组织，你不能加入（scope=" + rally.scope() + "）");
        }
    }

    /**
     * 加入资格里的权限位那一半（V22-a 只动国家层）。
     *
     * <p>小队与联盟层<b>没有</b>「参加集结」这一位（role_permission 表里只有 NATION 档有
     * {@code JOIN_NATIONAL_RALLY}），那两档的加入资格就是"是不是这个组织的成员"，
     * 由 {@link #requireMembership} 判完。国家层多一道位是因为它摇动的是全国兵力：
     * 国籍之外还要看职位档位（三档全开，所以实际是"在国就能进"）。
     *
     * <p>为什么这一位必须接上：它 {@code allow*} 三档全 true，<b>当前行为与不判一样</b>，
     * 所以漏掉它不会有任何用例变红 —— 而它一旦有人收紧（比如只给官职档），没接上的那一半
     * 就是"表说不能进、服务端照收"。本仓头号缺陷形状正是「判定写了没接上」。
     */
    private void requireJoinPermission(String playerId, Rally rally) {
        if (rally.scope() == Rally.Scope.NATION) {
            requirePermission(PermissionMatrix.Scope.NATION, nationRoleOf(playerId),
                    "JOIN_NATIONAL_RALLY");
        }
    }

    private static ErrorCode errorOfJoinFailure(String message) {
        if (message != null && message.contains("已满")) {
            return ErrorCode.RALLY_FULL;
        }
        if (message != null && message.contains("已经加入")) {
            return ErrorCode.RALLY_ALREADY_JOINED;
        }
        if (message != null && message.contains(Rally.Status.DEPARTED.name())) {
            // 不打到兜底的 RALLY_PREPARE_INVALID：那条文案是「准备时长不在允许区间内」，
            // 玩家看了会去改准备时长，而真问题是「这队已经飞出去了」——
            // 提示的价值在于它能不能指引下一步动作。CANCELLED 走不到这里（requireRally 先拒）
            return ErrorCode.RALLY_ALREADY_DEPARTED;
        }
        return ErrorCode.RALLY_PREPARE_INVALID;
    }

    private static Map<String, Long> troopsOf(List<RallyTroop> troops) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (troops != null) {
            for (RallyTroop troop : troops) {
                if (troop == null || troop.unitId() == null || troop.unitId().isBlank()) {
                    throw new BizException(ErrorCode.RALLY_NO_TROOP, "承诺兵力的 unitId 不得为空");
                }
                if (troop.count() <= 0L) {
                    throw new BizException(ErrorCode.RALLY_NO_TROOP,
                            "承诺兵力的数量必须为正：" + troop.unitId() + "=" + troop.count());
                }
                out.merge(troop.unitId(), troop.count(), Long::sum);
            }
        }
        return out;
    }

    private RallyView toRallyView(Rally rally, long now) {
        // 武将位（含落选的）全部下发：成员点完「加入」只看到自己加成功了，
        // 如果面板不说他的武将落选以及为什么，他会以为加成生效了
        List<RallyHeroSlotView> slots = new ArrayList<>();
        int cap = (int) configs.longParam("LINEUP_HERO_COUNT");
        for (Rally.HeroSlot slot : rally.heroSlots(cap)) {
            slots.add(new RallyHeroSlotView(slot.playerId(), slot.heroId(),
                    configs.get(com.ironoath.config.cfg.HeroCfg.class, slot.heroId()).name(),
                    RallyHeroSlotState.valueOf(slot.state().name()), slot.selected()));
        }
        return new RallyView(rally.rallyId(),
                RallyScope.valueOf(rally.scope().name()),
                rally.groupId(),
                rally.initiatorId(),
                new SocialCoord(Math.toIntExact(rally.targetX()), Math.toIntExact(rally.targetY())),
                rally.targetType() == null ? null : SocialTargetType.valueOf(rally.targetType()),
                rally.maxMembers(),
                rally.joinedCount(),
                rally.totalTroops(),
                rally.prepareUntil(),
                rally.prepareUntil(),
                RallyStatus.valueOf(rally.status().name()),
                rally.memberIds(),
                now,
                List.copyOf(slots));
    }

    // ---------- 钱包 ----------

    /**
     * 金币的资源 id。字面量的唯一归属在 {@code core.resource.ResourceIds}（原先在服务端
     * 散落了二十来处、金币就有四处，改口径只能靠全局搜 {@code "GOLD"} 这个到处都是的词）。
     * 这里保留一个本地别名只是因为调用点读起来更明确。
     */
    private static final String GOLD_RESOURCE_ID = ResourceIds.GOLD;

    /**
     * 结算后的可用余额。<b>必须走钱包而不是直接读存档的 current</b>：
     * 存档里的 current 是上次结算时的值，挂机期间的产出还没进去，
     * 直接读它会让一个明明够钱的玩家被判成「金币不足」（B00 禁止「先查后改扣资源」，
     * 而「先查」这一步查错值是同一条禁止项的另一种表现）。
     */
    private long balanceOf(String playerId, String resourceType, long now) {
        return wallet.available(playerId, resourceType, now);
    }

    /**
     * 扣款。
     *
     * <p>{@code deduct} 是<b>「不足则完全不扣」的全有或全无语义</b>，
     * 所以返回 false 时一分钱都没动，调用方不需要退款。
     * 这条语义很重要：扣一半会让玩家处于「资源没了但东西也没拿到」的状态。
     */
    private boolean spend(String playerId, String resourceType, long amount, long now, String reason) {
        if (amount <= 0L) {
            return true;
        }
        long taken = wallet.deduct(playerId, resourceType, amount, now);
        if (taken < amount) {
            LOG.warn("扣款未足额（{} playerId={} {}={} 实扣={}）：检查与扣款之间余额被其它路径改动了",
                    reason, playerId, resourceType, amount, taken);
            return false;
        }
        LOG.info("已扣款：{} playerId={} {}={}", reason, playerId, resourceType, amount);
        return true;
    }

    /**
     * 退款。只在「已扣款但后续步骤失败」时调用。
     *
     * <p>退不满是<b>需要人工介入的事故</b>，所以用 error 级别：
     * grant 会按仓库容量截断，而刚刚才从这里扣出去的量本该有位置放回去 ——
     * 除非期间有别的入账把仓库填满了。那种情况下差额不能凭空消失，必须留痕。
     */
    private void refund(String playerId, String resourceType, long amount, long now, String reason) {
        if (amount <= 0L) {
            return;
        }
        long back = wallet.grant(playerId, resourceType, amount, now);
        if (back < amount) {
            LOG.error("退款未足额（{} playerId={} {}={} 实退={} 差额={}）：需要人工补偿，"
                            + "玩家的钱不能凭空消失",
                    reason, playerId, resourceType, amount, back, amount - back);
        }
    }
}
