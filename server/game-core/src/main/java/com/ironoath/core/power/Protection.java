package com.ironoath.core.power;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：B08 §7 的三条基础保护 —— 新手保护、连续受害护盾、护盾期禁主动攻击。
 * 依赖：无（纯 Java，零框架、零配置依赖；规则由调用方从 global / match_rule 表装配后传入）。
 *
 * <p><b>只有三条，第四条不许加</b>（B08 禁止项 + C00 公理二）。
 * 公理二的可执行版本是：「数一数保护规则的数量，超过三条就要问自己这条规则会阻止哪个社交行为」。
 * {@code match_rule.json} 的 PROTECTION 行恰好 3 条，由单测断言行数 ——
 * 想加第四条保护，必须同时改表、改这里、改那条单测，三处一起改才动得了。
 *
 * <p><b>三条保护的共同点是 {@code blocksActiveAttack = true}</b>：护盾必须付对价。
 * 没有这条对价，护盾就是免费的进攻准备期（躲起来攒兵、护盾一结束就打人），
 * 保护机制会被反向利用，而「被打了也没有代价」会让主动攻击变成纯收益 ——
 * 那正是 C01 说的「杀死社交起点」的另一种写法。
 *
 * <p><b>三条保护的共同点之二是都有明确的到期条件</b>：新手保护 72h 或主城 8 级先到者生效，
 * 受害护盾 4h/12h 后自然消失。永久保护等于把玩家从生态里摘出去，
 * 而摘出去的人既不会被打也不会打人，他的存在对全服没有任何信息量。
 */
public final class Protection {

    /**
     * 保护种类。前三个名称与 match_rule.json 的三条 PROTECTION 行一一对应 ——
     * 那条「只保留三条必要保护」的公理自检数的就是这三行。
     */
    public enum Kind {
        /** 无任何保护。 */
        NONE,
        /** 新手保护（mr_protection_newcomer）。 */
        NEWBIE,
        /** 连续受害护盾（mr_protection_victim_shield）。 */
        VICTIM_SHIELD,
        /**
         * 自愿停战（免战牌）。
         *
         * <p><b>它不是公理二说的「第四条保护」</b>：公理二管的是<b>系统白送</b>的保护规则
         * （谁都能拿到、无需代价，所以每一加都要问「它会阻止哪个社交行为」）；
         * 这一种由玩家主动消耗道具换取、时长确定、到期自动结束，属于「有代价的选择」而不是
         * 「保护」。所以 match_rule 的三条行数不变，{@code Protection.Rules} 也不为它加参数。
         */
        PEACE
    }

    /**
     * 保护规则。全部来自配置表（铁律 1：不硬编码），装配处在 game-web 的 PowerService。
     *
     * <p><b>这里没有「新手保护时长」</b>：时长由 PlayerInitService 在建号时写成
     * {@code protectUntil = createdAt + NEWCOMER_PROTECT_SECONDS}，本类只看那个到期时刻。
     * 两处都存时长就意味着同一个数字有两个家，而改了一处忘另一处的表现是
     * 「配置写了 48h，实际保护 72h」—— 没有任何测试会变红。
     *
     * @param newbieCityLevel     新手保护的等级解除线（NEWCOMER_PROTECT_CITY_LEVEL = 8）
     * @param windowMillis        受害计数窗口（VICTIM_SHIELD_WINDOW_SECONDS × 1000 = 24h）
     * @param triggerCountTier1   第一档触发人数（VICTIM_SHIELD_TRIGGER_COUNT_TIER1 = 3）
     * @param shieldMillisTier1   第一档护盾时长（VICTIM_SHIELD_HOURS_TIER1 = 4h）
     * @param triggerCountTier2   第二档触发人数（VICTIM_SHIELD_TRIGGER_COUNT_TIER2 = 5）
     * @param shieldMillisTier2   第二档护盾时长（VICTIM_SHIELD_HOURS_TIER2 = 12h）
     */
    public record Rules(int newbieCityLevel,
                        long windowMillis,
                        int triggerCountTier1,
                        long shieldMillisTier1,
                        int triggerCountTier2,
                        long shieldMillisTier2) {

        public Rules {
            if (newbieCityLevel < 1) {
                throw new IllegalArgumentException("等级解除线必须 >= 1（主城最低 1 级），实际="
                        + newbieCityLevel);
            }
            if (windowMillis <= 0L) {
                // 窗口为 0 意味着计数永远清空，护盾永远触发不了 —— 第二条保护形同虚设
                throw new IllegalArgumentException("受害计数窗口必须为正，否则护盾永不触发：" + windowMillis);
            }
            if (triggerCountTier1 < 1 || triggerCountTier2 <= triggerCountTier1) {
                throw new IllegalArgumentException("两档触发人数必须为正且严格递增：tier1="
                        + triggerCountTier1 + ", tier2=" + triggerCountTier2);
            }
            if (shieldMillisTier1 <= 0L || shieldMillisTier2 <= shieldMillisTier1) {
                // 第二档必须更长：如果 5 人打的护盾和 3 人打的一样长，
                // 打人方就无法从护盾时长上感知到「我打得太狠了」，升级台阶失去意义
                throw new IllegalArgumentException("两档护盾时长必须为正且严格递增：tier1="
                        + shieldMillisTier1 + ", tier2=" + shieldMillisTier2);
            }
        }
    }

    /**
     * 当前保护状态。
     *
     * @param kind        生效中的保护种类；{@link Kind#NONE} 表示可被攻击
     * @param untilMillis 到期时刻（服务端毫秒时间戳）；NONE 时为 0
     * @param message     给玩家看的文案。拒绝一次攻击时必须带明确文案，绝不静默失败
     */
    public record Status(Kind kind, long untilMillis, String message) {

        public Status {
            if (kind == null) {
                throw new IllegalArgumentException("kind 不得为 null");
            }
            if (kind == Kind.NONE) {
                if (untilMillis != 0L) {
                    throw new IllegalArgumentException("无保护时到期时刻必须为 0，实际=" + untilMillis);
                }
                if (message != null && !message.isBlank()) {
                    throw new IllegalArgumentException("无保护时不该带文案，实际=" + message);
                }
            } else {
                if (untilMillis <= 0L) {
                    throw new IllegalArgumentException("保护生效时必须给出正的到期时刻，实际=" + untilMillis);
                }
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException("保护生效时必须带文案（B08：绝不静默失败）");
                }
            }
        }

        /** 无保护。 */
        public static Status none() {
            return new Status(Kind.NONE, 0L, null);
        }

        public boolean isProtected() {
            return kind != Kind.NONE;
        }

        /**
         * 该状态是否禁止主动攻击（B08 §7 第三条 + match_rule 的 blocksActiveAttack）。
         *
         * <p>三条保护全部禁攻。新手保护禁攻是为了让「72h 内先被打再叫人」这个社交链条成立 ——
         * 一个能主动出手的新号不再是新号；受害护盾禁攻是对价，见类注释。
         */
        public boolean blocksActiveAttack() {
            return isProtected();
        }
    }

    /** 新手保护文案。 */
    public static final String MESSAGE_NEWBIE = "对方处于新手保护期，无法进攻";

    /** 受害护盾文案。 */
    public static final String MESSAGE_VICTIM_SHIELD = "对方处于免战护盾中，无法进攻";
    /** 免战牌：来源是玩家自己用的道具，文案要说清这一点，别让人以为是系统把他保护起来了。 */
    public static final String MESSAGE_PEACE = "对方处于自愿停战期（免战牌），无法进攻";

    /** 自己在保护期却想主动攻击时的文案（验收 14：护盾期间发起攻击被拒绝且给明确文案）。 */
    public static final String MESSAGE_SELF_BLOCKED = "免战期间无法主动出击";

    private Protection() {
    }

    /**
     * 算一名玩家当前的保护状态。
     *
     * <p>新手保护的到期是<b>两个条件先到者生效</b>（B08 验收 15）：注册满 72h，或主城到 8 级。
     * 时间那一半由建号时写入的 {@code protectUntil} 表达，等级这一半在这里按当前 cityLevel
     * 实时判定而不是写回存档 —— 实时判定天然满足「先到」，
     * 而写回需要一个监听升级的路径，漏一处就变成「等级到了但保护还在」。
     *
     * @param newbieUntil       存档里的新手保护到期时刻。<b>null 表示无保护</b>，
     *                          包括「玩家主动攻击后被解除」这种情况（B08 §7）
     * @param victimShieldUntil 存档里的受害护盾到期时刻，null 表示无
     * @param peaceUntil        存档里的自愿停战（免战牌）到期时刻，null 表示无。
     *                          <b>必须与受害护盾分开传</b>，理由见 {@code PlayerPvp.peaceUntil}
     */
    public static Status statusOf(long now, int cityLevel,
                                  Long newbieUntil, Long victimShieldUntil, Long peaceUntil,
                                  Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (now <= 0L) {
            throw new IllegalArgumentException("now 必须为正的服务端时间戳，实际=" + now);
        }
        // 顺序即优先级：三者行为相同（不可被攻击 + 不可主动出击），区别只在文案要说清来源。
        // 新手在前是因为它是唯一会被「主动攻击」解除的那种，玩家更需要在第一次看到它
        if (newbieProtected(now, cityLevel, newbieUntil, rules)) {
            return new Status(Kind.NEWBIE, newbieUntil, MESSAGE_NEWBIE);
        }
        if (victimShieldUntil != null && victimShieldUntil > now) {
            return new Status(Kind.VICTIM_SHIELD, victimShieldUntil, MESSAGE_VICTIM_SHIELD);
        }
        if (peaceUntil != null && peaceUntil > now) {
            return new Status(Kind.PEACE, peaceUntil, MESSAGE_PEACE);
        }
        return Status.none();
    }

    /**
     * 新手保护是否仍然生效。
     *
     * <p>时间没到<b>且</b>等级没到才算生效。{@code protectUntil} 为 null 一律视为无保护 ——
     * 这既是老存档的读法（B08 之前建的号本来就不该再享受新手保护），
     * 也是「主动攻击则解除」的写法（解除就是把它置 null）。
     * 用 createdAt 兜底重算看似更保险，实际会把已经放弃的保护又还回去，
     * 那个「二次确认」弹窗就成了骗局。
     */
    public static boolean newbieProtected(long now, int cityLevel, Long newbieUntil, Rules rules) {
        if (newbieUntil == null || now >= newbieUntil) {
            return false;
        }
        return cityLevel < rules.newbieCityLevel();
    }

    /**
     * 被攻击一次后的受害护盾推进结果。
     *
     * @param retainedHits      写回存档的「攻击者 → 最近一次攻击时刻」，已剔除窗口外的项，含本次攻击者
     * @param distinctAttackers 窗口内的不同攻击者人数（含本次），等于 retainedHits 的大小
     * @param shieldUntil       写回存档的护盾到期时刻。本次未触发时沿用原值（可能已过期，读取端按 now 判定）
     * @param shieldRaised      护盾到期时刻是否被真的推后（已处于更长护盾中时为 false）
     */
    public record VictimOutcome(Map<String, Long> retainedHits,
                                int distinctAttackers,
                                Long shieldUntil,
                                boolean shieldRaised) {

        public VictimOutcome {
            if (retainedHits == null) {
                throw new IllegalArgumentException("retainedHits 不得为 null");
            }
            retainedHits = Map.copyOf(retainedHits);
            if (distinctAttackers < 0 || distinctAttackers != retainedHits.size()) {
                throw new IllegalArgumentException("不同攻击者人数必须与保留的命中记录数一致：count="
                        + distinctAttackers + ", size=" + retainedHits.size());
            }
            if (shieldRaised && shieldUntil == null) {
                throw new IllegalArgumentException("shieldRaised 为 true 时必须给出 shieldUntil");
            }
        }
    }

    /**
     * 记录一次被攻击，推进连续受害护盾（B08 §7 第二条）。
     *
     * <p><b>按不同攻击者计数，不按攻击次数</b>（match_rule.json 的裁定）：
     * 同一个人连打三次不该触发护盾，否则大佬可以靠单人压制把目标永久锁进护盾里，
     * 那等于变相禁止攻击，直接违反 C00 公理一。
     *
     * <p><b>护盾只推后不缩短</b>：已经处于 12h 护盾中又被第 6 个人打，
     * 不该把剩余时间重置成 4h（那样打人方能靠控制人数把护盾「调短」）。
     *
     * @param attackerHits 存档里的「攻击者 → 最近一次攻击时刻」
     * @param attackerId   本次攻击者
     * @param currentShieldUntil 存档里的护盾到期时刻，null 表示当前无护盾
     */
    public static VictimOutcome onAttacked(Map<String, Long> attackerHits, String attackerId,
                                           long now, Long currentShieldUntil, Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (attackerId == null || attackerId.isBlank()) {
            throw new IllegalArgumentException("attackerId 不得为空");
        }
        if (now <= 0L) {
            throw new IllegalArgumentException("now 必须为正的服务端时间戳，实际=" + now);
        }
        Map<String, Long> retained = new LinkedHashMap<>();
        if (attackerHits != null) {
            attackerHits.forEach((id, at) -> {
                if (id == null || at == null) {
                    return;
                }
                // 窗口外的记录直接丢弃。不清理的话这个 map 会单调增长，
                // 而它存在玩家存档里 —— 一个被全服打过的玩家会拖着一个几百项的 map 到处走
                if (now - at < rules.windowMillis()) {
                    retained.put(id, at);
                }
            });
        }
        retained.put(attackerId, now);
        // 封顶在「第二档门槛 + 1」而不是门槛本身：必须能区分「正好达到门槛」（授予护盾）
        // 与「已经超过门槛」（不再授予），否则护盾会被每一个新攻击者续期，见下面的判定
        while (retained.size() > rules.triggerCountTier2() + 1) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Long> entry : retained.entrySet()) {
                if (entry.getValue() < oldestAt) {
                    oldestAt = entry.getValue();
                    oldest = entry.getKey();
                }
            }
            retained.remove(oldest);
        }

        // 只在「人数正好达到某一档」时授予一次护盾。
        // 若每个新攻击者都刷新，被全服围殴的人会把 12h 护盾一直续下去 —— 他永远打不动、
        // 也永远出不了兵（护盾禁主动攻击），等于被冻在游戏之外。那不是保护，是流放。
        // B08 §7 的原文也是「第 5 次 → 12h」，是一次性的台阶，不是持续刷新
        int distinct = retained.size();
        Long triggered = null;
        if (distinct == rules.triggerCountTier2()) {
            triggered = now + rules.shieldMillisTier2();
        } else if (distinct == rules.triggerCountTier1()) {
            triggered = now + rules.shieldMillisTier1();
        }
        if (triggered == null) {
            return new VictimOutcome(retained, distinct, currentShieldUntil, false);
        }
        // 取较晚者：计数因记录过期回落到第一档时，不该把仍在生效的第二档护盾缩短
        boolean raised = currentShieldUntil == null || triggered > currentShieldUntil;
        return new VictimOutcome(retained, distinct, raised ? triggered : currentShieldUntil, raised);
    }
}
