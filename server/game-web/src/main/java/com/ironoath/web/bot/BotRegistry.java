package com.ironoath.web.bot;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotTuning;

/**
 * 职责：「谁是 Bot」的唯一权威（B11）。
 * 依赖：game-core 的 {@link BotProfile} 与 {@link BotTuning}，以及 {@link BotRulesAssembler}
 *      （红线判定所需的规则来源；每次现装配，与其余装配器同一条"不缓存"纪律）。
 *
 * <p><b>为什么需要一个注册表，而不是在 PlayerSave 上加一个 isBot 字段</b>：
 * {@code scripts/check-no-bot-privilege.sh} 禁止生产代码里出现 {@code isBot} 字段或分支 ——
 * Bot 的差异必须表达为参数（成长系数 / 延迟 / 失误率）而不是代码路径。
 * 把身份收在一个注册表里，游戏逻辑就<b>拿不到</b>这个信息，也就没法拿它开捷径：
 * 想给 Bot 特殊照顾，得先注入这个注册表，而那会在代码审查里立刻显形。
 *
 * <p><b>合规红线也靠它落地</b>：Bot 不得担任国家官职（B13 验收 4）、不得占排行榜前 3 名的奖励坑位、
 * 不得出现在付费弹窗场景。这三条都需要一个「这个 id 是不是 Bot」的判定，
 * 而判定的入口只能有一个 —— 两处各判一次的话，其中一处迟早会漏。
 *
 * <p><b>但注意：判定函数只有一个，调用点可以有多个</b>。官职那条的口径住在
 * {@link BotTuning#mayHoldOffice}（§六 的三级社交分布表），本类只做「真人不问、Bot 转问它」
 * 的合成；任命与派生席位都走 {@link #mayHoldOffice}，不再各写一套"Bot 一刀切拒绝"的说法。
 *
 * <p>内存实现：Bot 画像由孵化流程写入，进程重启后需要重新孵化。
 * 生产应当落库（与其它存储同一笔 MongoDB 债务，见上线检查清单 §四 2）。
 */
@Component
public class BotRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BotRegistry.class);

    private final Map<String, BotProfile> profiles = new ConcurrentHashMap<>();
    private final BotRulesAssembler assembler;

    public BotRegistry(BotRulesAssembler assembler) {
        this.assembler = assembler;
    }

    /** 登记一个 Bot（孵化完成时调用）。重复登记会覆盖并打 WARN —— 那说明孵化流程重复了。 */
    public void register(BotProfile profile) {
        if (profile == null || profile.botId() == null || profile.botId().isBlank()) {
            throw new IllegalArgumentException("profile 与 profile.botId 都不得为空");
        }
        BotProfile previous = profiles.put(profile.botId(), profile);
        if (previous != null) {
            LOG.warn("Bot {} 被重复登记（原型 {} → {}）：孵化流程可能对同一个 id 跑了两次",
                    profile.botId(), previous.archetypeId(), profile.archetypeId());
        }
    }

    /** @return 画像；<b>不是 Bot 时返回 null</b>（与 {@code Optional} 相比，这里 null 是常态而不是异常） */
    public BotProfile profileOf(String playerId) {
        return playerId == null ? null : profiles.get(playerId);
    }

    /**
     * 这个 id 是不是 Bot。<b>合规判定的唯一入口</b>：
     * 官职任命、排行榜奖励、付费弹窗三处都必须问它，不许各自另判一次。
     *
     * <p><b>但业务代码应当优先用本类的闸门方法</b>（{@link #mayHoldOffice} 等），理由见它们的注释。
     */
    public boolean isBot(String playerId) {
        return playerId != null && profiles.containsKey(playerId);
    }

    /**
     * 合规闸门（B11 §六 / §七，验收 5）：某个组织职位<b>对这个人</b>开放吗。
     *
     * <p><b>真人一律开放；Bot 的答案来自 {@link BotTuning#mayHoldOffice}</b> ——
     * 「小队可任普通成员、联盟可任普通成员但不得任盟主、国家只能补位普通成员」这条口径
     * 只有一个家，任命路径与派生席位（议员）都问这里，不再各写一套"Bot 一刀切拒绝"的说法。
     * 一刀切的两种坏法：拒得过宽会误伤（§六 明写 Bot 可以当小队普通成员），
     * 拒得过窄则漏红线（比如只挡任命、挡不住派生席位）。
     *
     * <p>判定仍然只长在注册表里（这正是 {@code check-no-bot-privilege.sh} 要的形状：
     * 不许业务代码写 {@code if (isBot)}，只许委托）。
     *
     * @param scope    SQUAD / ALLIANCE / NATION（未知层级默认拒绝，见 {@link BotTuning}）
     * @param isLeader 是否是首领位（队长 / 盟主 / 国主）
     * @param isOffice 是否是官职（副盟主、长老、国家官员）
     */
    public boolean mayHoldOffice(String playerId, String scope, boolean isLeader, boolean isOffice) {
        if (!isBot(playerId)) {
            return true;
        }
        // 现装配而不是缓存：与 BotRulesAssembler 同一条纪律（配置可热更）。
        // mayHoldOffice 今天不读 rules，但把装配时机交给调用点比对着一份可能过期的规则判红线更安全
        return new BotTuning(assembler.tuningRules()).mayHoldOffice(scope, isLeader, isOffice);
    }

    /**
     * 场景白名单闸门（B11 §七：不得出现在付费弹窗、限时礼包倒计时、官方公告、客服、私聊真人）。
     *
     * <p>为什么收在注册表：与 {@link #mayHoldOffice} 同一条纪律 —— 判定本体住在
     * {@link BotTuning#mayAppearIn}（默认拒绝，新增场景忘了登记就落在拒绝侧），
     * 而"谁该被挡"这类身份问题只有一个家。它今天唯一的生产调用点是聊天发送
     * （{@code BotWorldAdapter#chat}：先把"这条消息会出现在哪个场景"翻译成这里的场景名再问）。
     *
     * @param scene 场景名，取 {@link BotTuning#mayAppearIn} 的白名单取值
     */
    public boolean mayAppearIn(String scene) {
        // 现装配而不是缓存：与 mayHoldOffice 同一条理由（配置可热更）
        return new BotTuning(assembler.tuningRules()).mayAppearIn(scene);
    }

    /**
     * 合规闸门（B13 §4 / B11 §七）：<b>国策提案与投票只对真人开放</b>。
     *
     * <p><b>为什么需要一条新闸门而不能复用 {@link #mayHoldOffice}</b>：
     * 提案权来自官职（{@code SET_NATIONAL_POLICY}），所以提案那一半可以问 {@code mayHoldOffice}；
     * 但投票权是 <b>2026-09-30 裁决 A2 定的「每成员一票」</b>，任何官职都不参与 ——
     * 于是没有 {@code isLeader/isOffice} 可传，{@code mayHoldOffice} 在这一格给不出答案。
     * 硬把投票说成「官职」是给规则套一个错的壳，而错壳会在下一次改权限时被继承下去。
     *
     * <p><b>判定本体住在 {@link BotTuning#mayTakeNationalPolicyAction()}</b>（恒为 false，
     * 与 {@code mayAppearIn} 那族同一条「默认拒绝」的纪律），
     * 而「谁该被挡」这个身份问题只有一个家 —— 这正是 {@code check-no-bot-privilege.sh} 要的形状：
     * <b>不许业务代码写 {@code if (isBot)}，只许委托</b>。本类的第一版没有这一格，
     * {@code NationAppService} 里就出现了三处 {@code bots.isBot(...)} 并被门禁判红；
     * 那是门禁对的形状错了业务，而不是门禁该放宽。
     *
     * @param what 动作名（用于日志与提示文案，例如「国策提案」）
     * @return 放行与否。真人恒为 true
     */
    public boolean mayTakeNationalPolicyAction(String playerId) {
        if (!isBot(playerId)) {
            return true;
        }
        return new BotTuning(assembler.tuningRules()).mayTakeNationalPolicyAction();
    }

    /**
     * 拒绝式形状：<b>这个位置只对真人开放，被挡住是一次要被拒绝的操作</b>。
     *
     * <p>为什么不让调用方自己 {@code if (mayHoldOffice(...)) throw}：与
     * {@link #mayHoldOffice} 同一条纪律 —— 调用方声明「这里是个国家官职」，
     * 由注册表决定怎么拒、错误码与文案也只在一个地方拼。
     *
     * @param what 这个位置是什么（用于提示文案与日志，例如「国家官职」）
     * @throws com.ironoath.common.BizException 当判定不放行时（Bot 且该职位不开放）
     */
    public void requireMayHoldOffice(String playerId, String scope, boolean isLeader, boolean isOffice,
                                     String what) {
        if (mayHoldOffice(playerId, scope, isLeader, isOffice)) {
            return;
        }
        LOG.info("合规拦截：Bot {} 不得担任{}（B11 §六 §七 / B13 §2 / B16 上线清单 §七 4）",
                playerId, what);
        throw new com.ironoath.common.BizException(
                com.ironoath.common.ErrorCode.BOT_NOT_ELIGIBLE,
                what + "只对真人玩家开放");
    }

    /**
     * 派生式形状：<b>这个位置只允许真人，而落选不是错误</b>。真人才返回该 id，Bot 返回 null。
     *
     * <p>与 {@link #requireMayHoldOffice} 的分工看"被挡住时该说什么"：任命一个 Bot 当官是一次
     * <b>要被拒绝的操作</b>（玩家要看到 BOT_NOT_ELIGIBLE），而两处<b>无人操作的落选</b>走这一支 ——
     * 国家议员是「每盟主 1 席」的<b>派生席位</b>（一个 Bot 盟主存在时，正确的做法是这一席不给，
     * 而不是让每一个查看这个国家的人都吃一个错），赛季榜是「Bot 不进奖励坑位」的入口
     * （B16 §七 2：Bot 被摘出来是常态，不是异常）。
     *
     * <p>判定仍然只长在注册表里（这正是 {@code check-no-bot-privilege.sh} 要的形状：
     * 不许业务代码写 {@code if (isBot)}，只许委托）。<b>这里刻意不打日志</b>：两处调用点
     * 都在高频读/写路径上（每次读国家、每次战力重算上报），逐次记一条会在真正需要看的
     * 那几条合规拦截里淹掉信号 —— 表现本身就是"这一席是空的 / 这人不在榜上"。
     *
     * @param what 这个位置是什么（只为把调用意图留在签名上，便于 grep 谁在用）
     * @return 真人的 id；是 Bot 时返回 null
     */
    public String humanOnly(String playerId, String what) {
        return isBot(playerId) ? null : playerId;
    }

    public Collection<String> botIds() {
        return profiles.keySet();
    }

    /**
     * 「这次攻击属于托管账号打真人吗」—— B11 §五 攻击频控的适用范围判定。
     *
     * <p><b>为什么这个三元判断也收在注册表里</b>：它问的是同一个身份问题（谁被托管、谁是真人），
     * 而 {@code check-no-bot-privilege.sh} 只允许注册表回答它（业务代码里不许出现那个字段或分支）。
     * 频控本身（24h 几次、怎么记、怎么清）住在 {@code BotAttackLimiter} 里 ——
     * 本方法只回答「这对组合要不要受限」。
     *
     * <p><b>为什么只约束「Bot → 真人」</b>：限额保护的是「某个真人被 Bot 围殴到退游」。
     * Bot 之间的攻伐（§六 的邻里关系）与真人之间的攻防都不在这个目的之内 ——
     * 给它们加限制，就是把「保护」变成了对玩法本身的限制。
     */
    public boolean botOnHuman(String attackerId, String victimId) {
        return isBot(attackerId) && !isBot(victimId);
    }

    /**
     * 从一批 id 里筛出真人（Bot 剔除）。
     *
     * <p>给<b>统计</b>用：B11 §五 的战力校准要按「服务器真人 matchPower 均值」定标，
     * 而这个均值一旦把 Bot 自己算进去就成了自指（Bot 越多，均值越接近 Bot 的目标值，
     * 下一批 Bot 又按这个值孵化 —— 一次正反馈，最后与真人脱节）。
     *
     * <p><b>为什么要有这个方法，而不是让调用方自己问</b>：{@code check-no-bot-privilege.sh}
     * 只允许注册表问「这是不是 Bot」，所以调用方拿到的必须是<b>名单</b>而不是答案 ——
     * 与 {@link #humanOnly} 同一条纪律，只是这一批筛的是样本集而不是某一个位置。
     */
    public java.util.List<String> humansIn(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> humans = new java.util.ArrayList<>(playerIds.size());
        for (String playerId : playerIds) {
            if (playerId != null && !profiles.containsKey(playerId)) {
                humans.add(playerId);
            }
        }
        return humans;
    }

    public int size() {
        return profiles.size();
    }

    /** 不再是 Bot（清理或转真人）。调度器会在下一次 tick 时把它从队列里摘掉。 */
    public void unregister(String playerId) {
        if (profiles.remove(playerId) != null) {
            LOG.info("Bot {} 已从注册表移除", playerId);
        }
    }

    /** 测试辅助：清空。 */
    public void clear() {
        profiles.clear();
    }
}
