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
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.web.dto.generated.WarDeclareReq;
import com.ironoath.web.dto.generated.WarNationScoreView;
import com.ironoath.web.dto.generated.WarPhase;
import com.ironoath.web.dto.generated.WarStatusResp;
import com.ironoath.web.nation.NationMembership;
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
 * <p><b>本类是承载，不是玩法</b>：这一切片没有任何写入路径（击杀累计、疲劳累积、开战与结算都未接线），
 * 所以生产上 {@code findLatest()} 恒空、视图恒回 {@code hasWar=false}。
 * 验收矩阵里 B13 的疲劳值上限与国家集结门槛两条因此继续挂 ⬜，<b>不因为这个端点存在而变</b> ——
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

    public WarAppService(WarStore wars, NationStore nations, WarRulesAssembler assembler,
                         TimeService timeService, NationMembership membership,
                         SocialRulesAssembler socialRules, PlayerLock playerLock,
                         IdempotencyStore idempotency, ConfigRegistry configs) {
        this.wars = wars;
        this.nations = nations;
        this.assembler = assembler;
        this.timeService = timeService;
        this.membership = membership;
        this.socialRules = socialRules;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.configs = configs;
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
        Optional<WarScoreBoard> latest = wars.findLatest();
        if (latest.isEmpty()) {
            // 无战事：积分与击杀给 0（那是"没有任何事发生过"的真值），而 phase/startedAt/占领者给 null
            // （那三项没有真值可给，填 0 会被读成"1970 年开过一场仗"）。
            // canMarch 在这里是 true —— 疲劳闸门只在国战里生效，没有仗就没有那道闸；
            // "有没有仗"由 hasWar 单独说，一个事实只用一种表示。
            return new WarStatusResp(false, null, null, 0L, rules.gateCount(),
                    null, null, List.of(), 0L, rules.serverGoalKills(), false,
                    0L, rules.fatigueMax(), true, now);
        }
        WarScoreBoard board = latest.get();
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
                board.fatigueOf(playerId), rules.fatigueMax(), board.canMarch(playerId), now);
    }

    /**
     * 宣战（B13 §一 §7 的开局那一步）：立一块积分板、把攻守两国登记成参战方，并把关系转成敌对。
     *
     * <p><b>前置的顺序是算过的</b>：本国存在 → 有 {@code DECLARE_WAR} 权限 → 不是打自己 →
     * 外交关系允许打 → 目标国存在且没解散 → 当前没有未结束的仗。
     * 把「没有未结束的仗」放在最后不是疏忽：前面几条都是<b>不写任何东西就能否掉</b>的，
     * 而"有没有仗"这条必须和插入收在同一个临界区里才成立（见 {@link WarStore#insertIfNoneActive}）——
     * 提前查它就等于用一次带竞争的读去决定要不要走后面那条无竞争的路径，白多一个窗口。
     *
     * <p><b>为什么这一格里还发不了奖、也没有王城</b>：关卡与王城今天不是地图上的可占领实体
     * （{@code WorldEntityType} 只有城/野怪/资源/行军/建筑），所以这块板会停在
     * {@code PREPARATION}，{@code beginSiege} 进不去，占领分与建筑分永远为 0，
     * 只有击杀（切片 2b）会动。验收矩阵 B13 的验收 6/7 因此仍挂 ⬜。
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

                WarScoreBoard board = new WarScoreBoard(assembler.rules(), now);
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
