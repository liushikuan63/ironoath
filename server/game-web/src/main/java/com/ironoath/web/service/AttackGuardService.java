package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.PowerBandGuard;
import com.ironoath.core.power.Protection;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 职责：PVP 攻击的统一前置校验 —— 攻方保护、守方保护、战力圈层（B08 §2 + §7）。
 * 依赖：玩家仓储、世界仓储、{@link PowerService}（口径装配）、{@link PowerRefreshService}（攻方战力当场重算）。
 *
 * <p><b>抽成独立的一个类，是为了让「三个入口共用一份校验」成为结构上的事实</b>。
 * B08 §2 要求发起攻击 / 搜索目标 / 发起集结三个入口统一校验，禁止项写着
 * 「不要新增绕过统一中间件校验的代码路径」。把校验放进 MarchAppService 里，
 * B10 的集结入口就必须再抄一遍 —— 而抄第二遍的那一刻，两份实现就开始分叉了。
 * 现在集结入口（B10）要做的只是调这个类，并且传一个不同的 rallySize。
 *
 * <p><b>校验顺序是「自己 → 对方 → 圈层」，这个顺序不能换</b>（针对 PVP 目标而言 ——
 * 打野 / 打空地 / 拦截采集不构成 PVP，见 {@link #guardRally}，直接放行）：
 * 玩家在护盾里点了攻击，他需要听到的是「你在免战期间」，
 * 而不是「对方实力远超于你」—— 后者会让他以为换个目标就行了，
 * 于是他会去搜索、去挑目标、去点第二次，最后才发现根本出不了兵。
 * 错误提示的价值在于它能不能指引下一步动作。
 *
 * <p><b>攻方战力当场重算，守方战力读存档</b>，与 {@link TargetSearchService} 同一套理由：
 * 攻方是判定基准，而且这次请求本身就要扣他的兵，多一次重算不算额外成本；
 * 守方可能在全服任何地方，逐个重算等于一次攻击打出好几份存档读。
 * 守方的存档战力由 {@code PowerRefreshInterceptor} 在每次写操作后刷新，因此是新鲜的。
 */
@Service
public class AttackGuardService {

    private static final Logger LOG = LoggerFactory.getLogger(AttackGuardService.class);

    private final PlayerRepository players;
    private final WorldRepository world;
    private final PowerService powerService;
    private final PowerRefreshService powerRefreshService;
    /**
     * 赛季阶段判定（备战期禁战、休赛期禁战）。
     *
     * <p>接在这里而不是各个入口各自判一次：本类是铁律 11 要求的那个唯一漏斗，
     * 单人攻击、集结攻击、拦截采集队都经过它。漏接的入口不会报错，只会在战报里
     * 多出一堆本该不存在的仗 —— 那正是「禁战期」最容易被静默绕过的形状。
     */
    private final com.ironoath.web.season.SeasonAppService seasons;
    /**
     * 「玩家 → 所属国家」这条链的两端：国籍跟随联盟，所以要先查联盟再查国家。
     *
     * <p>接在<b>本类</b>而不是各入口各自判：铁律 11 要求单人攻击、集结、拦截采集共用同一个漏斗，
     * 而 B13 验收 12 要的是"改一次外交立刻改变谁能打谁"。少接一个入口的表现不是报错，
     * 是那条外交关系在某些攻击路径上根本不存在。
     */
    private final com.ironoath.web.store.memory.InMemorySocialStore social;
    private final com.ironoath.web.nation.NationStore nations;
    /**
     * 攻击频控（B11 §五：同一真人 24h 内被托管账号攻击 ≤ N 次）。
     *
     * <p>接在<b>本类</b>而不是各入口各自判：铁律 11 要求单人与集结共用同一个漏斗，
     * 而频控与护盾、圈层一样是「发起攻击」这件事的前置条件。少接一个入口的表现不是报错，
     * 是某条路径上的人被 Bot 反复打 —— 正是这条限额存在的理由。
     */
    private final com.ironoath.web.bot.BotAttackLimiter botAttackLimiter;

    public AttackGuardService(PlayerRepository players, WorldRepository world,
                              PowerService powerService, PowerRefreshService powerRefreshService,
                              com.ironoath.web.season.SeasonAppService seasons,
                              com.ironoath.web.store.memory.InMemorySocialStore social,
                              com.ironoath.web.nation.NationStore nations,
                              com.ironoath.web.bot.BotAttackLimiter botAttackLimiter) {
        this.players = players;
        this.world = world;
        this.powerService = powerService;
        this.powerRefreshService = powerRefreshService;
        this.seasons = seasons;
        this.social = social;
        this.nations = nations;
        this.botAttackLimiter = botAttackLimiter;
    }

    /**
     * 校验一次单人攻击（rallySize = 1）。集结入口请用 {@link #guardRally}。
     *
     * @param attackerId 攻方玩家 id
     * @param target     目标坐标。<b>空地 / 野怪 / 资源点不构成 PVP，直接放行</b> ——
     *                   保护与圈层只针对「打玩家」（B08 §7「不可攻击玩家」），理由见 {@link #guardRally}
     * @param now        服务端当前时刻
     * @throws BizException 校验不通过时抛出，带明确文案（B08：绝不静默失败）
     */
    public void guard(String attackerId, Coord target, long now) {
        guardRally(attackerId, target, 1, now);
    }

    /**
     * 校验一次集结攻击。
     *
     * <p>{@code rallySize} 决定区间的 √N 放宽（B08 §3：16 人可讨伐 8 倍目标）。
     * 这是弱者唯一的破圈手段，所以它必须与单人攻击走<b>同一个</b>校验器 ——
     * 两个校验器就意味着两套口径，而口径分叉的表现是「搜索里能选、集结时被打回」。
     *
     * <p><b>本类的全部校验（赛季禁战 + 三条保护 + 圈层）都只对 PVP 生效</b>，
     * 判定面是「目标坐标上有玩家城」。2026-09-12 修的一处缺陷：此前自保护校验排在目标判定
     * <b>之前</b>，于是新号 72 小时里连野怪都打不了 —— 而 B08 §7 原文写的是
     * 「新手保护期……不可攻击<b>玩家</b>」，B00 五分钟体验的「首战野怪全胜」与
     * B09 的「新号第一次打野必定打得动」都要求新号当天就能打野。
     *
     * @param rallySize 集结参与人数，单人 = 1。上限由 match_rule 的三档人数约束，
     *                  但那属于集结本身的校验（B10），不在本类职责内
     */
    public void guardRally(String attackerId, Coord target, int rallySize, long now) {
        if (attackerId == null || attackerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (target == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标坐标不得为空");
        }
        // 一、先判这一仗是不是 PVP：是就打下面的全套校验，不是（打野/打空地/拦截采集）直接放行。
        // 「打野不受限」与赛季禁战注释里那条理由是同一条 —— 把开服头几天的进攻性玩法全砍掉，
        // 只会让新号无事可做。
        String targetId = world.cityAt(target).orElse(null);
        if (targetId == null) {
            return;
        }
        // 赛季阶段（备战期 / 休赛期禁战）。放在读攻方存档之前：它是全服状态，
        // 与攻方是谁无关，越便宜的判断越该先做
        seasons.requirePvpAllowed(now);
        Protection.Rules protectionRules = powerService.protectionRules();
        PlayerSave attacker = players.findByPlayerId(attackerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND,
                        "存档不存在：" + attackerId));

        // 二、攻方自己是否被保护挡住（B08 §7 第三条：护盾期间不可主动攻击）。
        // 排在最前是刻意的：玩家在护盾里点了攻击，需要先听到「你在免战」，
        // 而不是「对方实力远超于你」—— 后者会让他去换目标、去搜索，白折腾三轮
        Protection.Status selfStatus = Protection.statusOf(now, attacker.cityLevel(),
                attacker.protectUntil(), attacker.pvp().victimShieldUntil(),
                attacker.pvp().peaceUntil(), protectionRules);
        if (selfStatus.blocksActiveAttack()) {
            LOG.info("攻击被拒：攻方处于保护期 playerId={} 种类={} 到期={}",
                    attackerId, selfStatus.kind(), selfStatus.untilMillis());
            throw new BizException(ErrorCode.MARCH_TARGET_PROTECTED, Protection.MESSAGE_SELF_BLOCKED);
        }

        PlayerSave targetSave = players.findByPlayerId(targetId).orElse(null);
        if (targetSave == null) {
            // 有城无人：删号不会同步清理世界坐标（那是 B14 赛季重置的事）。
            // 这里不拒绝也不放行到战斗，交给 B09 的结算去处理这种残局
            LOG.warn("目标坐标上有城但找不到存档，跳过圈层校验 target={} playerId={}", target, targetId);
            return;
        }

        // 国籍与外交（B13 §5、验收 12；同国不可互攻是 2026-09-11 的口径裁决）。排在守方护盾之前
        // 是刻意的：护盾会自己到期，等就行；条约与同胞关系不会 —— 先报护盾等于让玩家白等一个等不来的东西。
        requireNationAllows(attackerId, targetId);

        Protection.Status targetStatus = Protection.statusOf(now, targetSave.cityLevel(),
                targetSave.protectUntil(), targetSave.pvp().victimShieldUntil(),
                targetSave.pvp().peaceUntil(), protectionRules);
        if (targetStatus.isProtected()) {
            LOG.info("攻击被拒：守方处于保护期 attacker={} target={} 种类={} 到期={}",
                    attackerId, targetId, targetStatus.kind(), targetStatus.untilMillis());
            throw new BizException(ErrorCode.MARCH_TARGET_PROTECTED, targetStatus.message());
        }

        // 三、频控（B11 §五）：只约束「托管账号打真人」，判在本类、记账在战斗真的打起来时
        // （{@code MarchAppService.resolveAttack}）—— 出门又召回不该白吃真人的被攻击额度。
        // 排在护盾之后：护盾会自己到期（等就行），而额度是「今天已经挨够了」这个事实
        botAttackLimiter.requireQuota(attackerId, targetId, now);

        // 四、战力圈层。攻方当场重算（含峰值记忆，验收 3），守方读存档
        PlayerPower attackerPower = powerRefreshService.refresh(attackerId).power();
        long targetMatchPower = targetSave.power().matchPower();
        PowerBandGuard.BandCheckResult check = PowerBandGuard.check(
                attackerPower.matchPower(), targetMatchPower, rallySize, powerService.bandRules());
        if (!check.allowed()) {
            LOG.info("攻击被拒：超出战力圈层 attacker={} target={} 集结人数={} 攻方={} 守方={} 区间=[{},{}] 原因={}",
                    attackerId, targetId, rallySize, attackerPower.matchPower(), targetMatchPower,
                    check.lowerBound(), check.upperBound(), check.reason());
            // 文案后面补上实际区间：玩家对「我为什么打不了他」极度敏感（B08 §1），
            // 只给一句「实力远超于你」会让他去猜，而猜出来的答案往往是「这游戏有 bug」
            throw new BizException(ErrorCode.MARCH_POWER_OUT_OF_BAND, check.message()
                    + "（你的可攻击区间是 " + check.lowerBound() + " ~ " + check.upperBound()
                    + "，对方 " + targetMatchPower + "）");
        }
    }

    /**
     * 外交关系是否允许打这一仗（B13 §5、验收 12，以及禁止项
     * 「不要让小队私人关系凌驾于国家外交关系之上」）。
     *
     * <p><b>判定只长在 {@code Nation.mayAttackNation} 一处</b>，本方法只负责把两个玩家翻译成
     * 两个国家再问它一次 —— 那里已经写死了盟约与朝贡不可攻、中立与敌对可攻。
     *
     * <p><b>为什么要双向问</b>：B13 §5 的关系是<b>单方面宣布</b>的（见 {@code NationAppService#diplomacy}
     * 的理由：要求双方确认会让结盟在小服里几乎凑不齐人），所以它只记在宣布方那一侧。
     * 如果只查攻方，就会出现"A 宣布与 B 盟约，B 立刻照抢 A"—— 那等于对 B 而言这条盟约不存在，
     * 而宣布和平的一方反而比宣布前更不安全。协议原文写的是「盟约之间<b>不能互相</b>攻击」，
     * 所以任何一侧记着盟约或朝贡，这一仗就不许打。
     *
     * <p><b>同国不可互攻</b>（2026-09-11 口径裁决：国家内部不是无政府状态，国战对手只能是别的国）。
     * 原先这里放行 —— 但那不是决定，只是领域层"未登记关系算中立"的默认值被顺带当成了答案，
     * 所以要用一条<b>独立的错误码</b>把它显式化：同胞之间打不了是玩法常态，
     * 与"对方有约在先"（一个随时会被撕掉的临时状态）不是一句话，也不该是同一个码。
     *
     * <p><b>唯一仍然放行的情况是"有一方没有国家"</b>：这条规则管的是国家之间与国家内部的关系，
     * 无国籍玩家不在它的管辖范围内（否则散人无端多一条打不了人的限制）。
     */
    private void requireNationAllows(String attackerId, String targetId) {
        String attackerNation = nationIdOf(attackerId);
        String targetNation = nationIdOf(targetId);
        if (attackerNation == null || targetNation == null) {
            return;
        }
        if (attackerNation.equals(targetNation)) {
            LOG.info("攻击被拒：同国不可互攻 attacker={} target={} nationId={}",
                    attackerId, targetId, attackerNation);
            throw new BizException(ErrorCode.NATION_SAME_KINGDOM,
                    "你与对方同属一个国家（" + nationNameOf(attackerNation) + "），国战的对手只能是别的国");
        }
        Nation from = liveNation(attackerNation);
        Nation to = liveNation(targetNation);
        if (from == null || to == null) {
            // 亡国不参与关系（它已经不能被外交，也不能被入籍）
            return;
        }
        Nation.Diplomacy byAttacker = from.diplomacyWith(to.id());
        Nation.Diplomacy byTarget = to.diplomacyWith(from.id());
        if (from.mayAttackNation(to.id()) && to.mayAttackNation(from.id())) {
            return;
        }
        LOG.info("攻击被拒：外交关系禁止 attacker={} target={} 攻方国={}({}) 守方国={}({})",
                attackerId, targetId, attackerNation, byAttacker, targetNation, byTarget);
        throw new BizException(ErrorCode.NATION_TREATY_PROTECTED,
                treatyReason(byAttacker, byTarget));
    }

    /** 只为提示文案服务：读不到名字时宁可退回 id，也不为一句文案再抛一个错。 */
    private String nationNameOf(String nationId) {
        Nation nation = liveNation(nationId);
        return nation == null ? nationId : nation.name();
    }

    /**
     * 给玩家看的那句话。写成对枚举穷尽的 switch 而不是 if，是为了"以后加第五种关系"时
     * 这里会在编译期被要求表态 —— 漏一条的表现是新关系被静默解释成一句错话。
     */
    private static String treatyReason(Nation.Diplomacy byAttacker, Nation.Diplomacy byTarget) {
        Nation.Diplomacy blocking = isTreaty(byAttacker) ? byAttacker : byTarget;
        return switch (blocking) {
            case ALLIED -> "你们两个国家之间是盟约关系，盟约不可互攻（要打先由国王解除盟约）";
            case TRIBUTARY -> "你们两个国家之间是朝贡关系，朝贡国不可被宣战";
            // 这两档本身放行；走到这里说明是<b>对面</b>那一侧记着条约，所以文案按对面那句说
            case HOSTILE, NEUTRAL -> "对方国家认为你们之间有约在先，不能攻击";
        };
    }

    private static boolean isTreaty(Nation.Diplomacy relation) {
        return relation == Nation.Diplomacy.ALLIED || relation == Nation.Diplomacy.TRIBUTARY;
    }

    /** 玩家所属国家 id。国籍跟随联盟，所以是「联盟 → 国家」两跳；任一跳落空就没有国家。 */
    private String nationIdOf(String playerId) {
        return social.allianceOf(playerId)
                .flatMap(alliance -> nations.findByAlliance(alliance.id()))
                .map(Nation::id).orElse(null);
    }

    private Nation liveNation(String nationId) {
        return nations.findById(nationId).filter(n -> !n.isDisbanded()).orElse(null);
    }
}
