package com.ironoath.core.bot;

import java.util.HashMap;
import java.util.Map;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：Bot 的数量、强度与频控调节（B11 §五），以及合规红线的可执行判定（§六/§七，验收 3/4/5）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架）。
 *
 * <p><b>本类是「Bot 不许比真人强」这条设计意图的唯一执行点</b>。三处约束在这里汇合：
 * <ol>
 *   <li><b>战力校准</b>（验收 3）：目标战力 = 真人均值 × archetype 系数 × (0.80~0.95)，
 *       永远略低于真人均值 —— 保证真人打得赢、有成就感，但不会白给</li>
 *   <li><b>攻击频控</b>（验收 4）：同一真人 24 小时内被 Bot 攻击 ≤ 3 次，超出则全局降权。
 *       没有频控的话，一个刚建号的新人可能被三个劫掠者轮流打到退游，
 *       而他还查不出原因（Bot 无标识）</li>
 *   <li><b>密度调节</b>（§五）：Bot 目标数 = clamp(目标密度 − 在线真人数, 下限, 上限)，
 *       密度曲线 D1 20:1 → D7 5:1 → D30 1.5:1</li>
 * </ol>
 *
 * <p><b>合规判定写成可执行的方法而不是文档</b>（验收 5）：
 * §七 的红线是「违反即视为任务失败」，而红线写在文档里的下场是某次重构悄悄绕过它。
 * 所以 {@link #mayHoldOffice}、{@link #mayEnterRankTop}、{@link #mayAppearInPaymentScene}
 * 三个方法必须被调用方真的调用 —— 而 check-no-bot-privilege.sh 会检查有没有 {@code if (isBot)}
 * 的反向分支（验收 8），两条一起才构成闭环。
 */
public final class BotTuning {

    /**
     * @param powerRatioMinFixed  目标战力相对真人均值的下界（定点）。来源 global.BOT_POWER_RATIO_MIN
     * @param powerRatioMaxFixed  上界。来源 global.BOT_POWER_RATIO_MAX
     * @param checkRatioMinFixed  验收 3 的容忍带下界（0.70）。来源 global.BOT_POWER_CHECK_MIN
     * @param checkRatioMaxFixed  验收 3 的容忍带上界（1.00）。来源 global.BOT_POWER_CHECK_MAX
     * @param attackLimitPer24h   同一真人 24h 内被 Bot 攻击的次数上限。来源 global.BOT_ATTACK_LIMIT_PER_24H
     * @param maxPerServer        单服 Bot 上限。来源 global.BOT_MAX_PER_SERVER
     * @param densityD1Fixed      开服 D1 的 Bot:真人 比（定点）。来源 global.BOT_DENSITY_RATIO_D1
     * @param densityD7Fixed      第 7 天。来源 global.BOT_DENSITY_RATIO_D7
     * @param densityD30Fixed     第 30 天。来源 global.BOT_DENSITY_RATIO_D30
     */
    public record Rules(long powerRatioMinFixed, long powerRatioMaxFixed,
                        long checkRatioMinFixed, long checkRatioMaxFixed,
                        int attackLimitPer24h, int maxPerServer,
                        long densityD1Fixed, long densityD7Fixed, long densityD30Fixed) {
        public Rules {
            requirePositiveRatio(powerRatioMinFixed, "powerRatioMin");
            requirePositiveRatio(powerRatioMaxFixed, "powerRatioMax");
            if (powerRatioMaxFixed < powerRatioMinFixed) {
                throw new IllegalArgumentException("战力系数区间非法：min=" + powerRatioMinFixed
                        + " > max=" + powerRatioMaxFixed);
            }
            if (powerRatioMaxFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("战力系数上界不得超过 1.0（" + powerRatioMaxFixed
                        + "）：Bot 强过真人均值会让 B08 的圈层规则失去意义 —— "
                        + "圈层的存在是为了让真人打得赢同层对手，而 Bot 不是同层对手，是填充物");
            }
            // 验收容忍带必须比校准目标带宽，否则正常的成长参差就会让验收失败
            if (checkRatioMinFixed > powerRatioMinFixed || checkRatioMaxFixed < powerRatioMaxFixed) {
                throw new IllegalArgumentException("验收容忍带 [" + checkRatioMinFixed + "," + checkRatioMaxFixed
                        + "] 必须覆盖校准目标带 [" + powerRatioMinFixed + "," + powerRatioMaxFixed
                        + "]，否则按目标校准出来的 Bot 会通不过自己的验收");
            }
            if (attackLimitPer24h < 1) {
                throw new IllegalArgumentException("attackLimitPer24h 必须 >= 1，实际=" + attackLimitPer24h
                        + "。设为 0 等于禁止 Bot 攻击真人，而 B11 §一 的劫掠者原型就失去了意义");
            }
            if (maxPerServer < 1) {
                throw new IllegalArgumentException("maxPerServer 必须 >= 1，实际=" + maxPerServer);
            }
            requirePositive(densityD1Fixed, "densityD1");
            requirePositive(densityD7Fixed, "densityD7");
            requirePositive(densityD30Fixed, "densityD30");
            if (densityD30Fixed > densityD7Fixed || densityD7Fixed > densityD1Fixed) {
                throw new IllegalArgumentException("密度曲线必须随时间递减（D1=" + densityD1Fixed
                        + " D7=" + densityD7Fixed + " D30=" + densityD30Fixed
                        + "）：Bot 是给真人让位的，越到后期越少");
            }
        }
    }

    private final Rules rules;
    /** victimPlayerId → 最近 24h 内被 Bot 攻击的时间戳环形记录 */
    private final Map<String, long[]> attackLog = new HashMap<>();

    public BotTuning(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    // ---------- 战力校准（验收 3） ----------

    /**
     * 某个 Bot 的目标战力。
     *
     * <p><b>成长系数不参与本公式</b>。§五 的公式是
     * 「真人均值 × archetype 系数 × (0.8~0.95)」，里面没有 0.7~1.3 的成长系数 ——
     * 后者描述的是「长得多快」，不是「长到多高」。把两者相乘会得到
     * 0.7×0.8=0.56 到 1.3×0.95=1.235 的区间，两头都超出验收 3 的 [0.7, 1.0] ——
     * 上端超出意味着 Bot 强过真人均值（圈层规则失效），
     * 下端超出意味着 Bot 弱到白给（§五 明写「不会白给」）。
     * 成长系数应当作用在 tick 频率上（见 BotDecisionTree.catchUpTickMultiplier），
     * 让长得慢的 Bot 更勤快，而不是直接抬高它的终点。
     *
     * <p><b>结果夹进验收带</b>：archetype 系数可以把军阀抬高、把陪跑者压低，
     * 但不得穿出 [checkRatioMin, checkRatioMax]。这不是「把数据修好看」：
     * 验收 3 是硬约束，而原型差异是软目标，硬约束优先。
     *
     * @param humanAverageMatchPower 服务器真人的平均 matchPower（**不是展示战力** ——
     *                               B08 的圈层用 matchPower，用错口径会让校准整体偏移一个峰值记忆的系数）
     * @param archetypeFactorFixed   原型系数（定点）。军阀 > 1、陪跑者 < 1
     */
    public long targetPower(long humanAverageMatchPower, long archetypeFactorFixed) {
        if (humanAverageMatchPower < 0) {
            throw new IllegalArgumentException("真人均值战力不得为负，实际=" + humanAverageMatchPower);
        }
        if (humanAverageMatchPower == 0) {
            // 开服初期可能一个真人都没有（或都还没算过战力）。
            // 返回 0 而不是抛错：Bot 会停在初始状态，等有了真人均值再被下一次校准拉起
            return 0L;
        }
        long midpoint = (rules.powerRatioMinFixed() + rules.powerRatioMaxFixed()) / 2L;
        long scaled = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(humanAverageMatchPower), midpoint));
        scaled = FixedPoint.round(FixedPoint.mul(FixedPoint.of(scaled), archetypeFactorFixed));
        long floor = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(humanAverageMatchPower), rules.checkRatioMinFixed()));
        long ceiling = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(humanAverageMatchPower), rules.checkRatioMaxFixed()));
        return Math.max(floor, Math.min(ceiling, scaled));
    }

    /**
     * 验收 3 的判定：这个 Bot 的 matchPower 与真人均值之比是否落在 [0.7, 1.0]。
     *
     * @return 比值（定点）。调用方与 {@link Rules#checkRatioMinFixed()} /
     *         {@link Rules#checkRatioMaxFixed()} 比较
     */
    public long powerRatioFixed(long botMatchPower, long humanAverageMatchPower) {
        if (humanAverageMatchPower <= 0) {
            // 均值为 0 时比值无定义。返回 0 会让所有 Bot 都「不达标」，
            // 而真实情况是「还没有可比较的对象」—— 用 -1 把这两种情况区分开
            return -1L;
        }
        return FixedPoint.div(FixedPoint.of(botMatchPower), FixedPoint.of(humanAverageMatchPower));
    }

    /** 验收 3 是否通过。均值为 0 时视为通过（没有可比较的对象不算违规）。 */
    public boolean passesPowerCheck(long botMatchPower, long humanAverageMatchPower) {
        long ratio = powerRatioFixed(botMatchPower, humanAverageMatchPower);
        if (ratio < 0) {
            return true;
        }
        return ratio >= rules.checkRatioMinFixed() && ratio <= rules.checkRatioMaxFixed();
    }

    // ---------- 攻击频控（验收 4） ----------

    /**
     * 这个真人还能不能被 Bot 攻击。
     *
     * <p><b>判定与记账分成两个方法是有意的</b>：{@code mayAttack} 只读，
     * {@code recordAttack} 才写。合在一起的话，「先查一下会不会被限流」这个纯查询
     * 就会把配额吃掉一次 —— 那是先查后改的经典变体。
     */
    public boolean mayAttack(String victimPlayerId, long now) {
        return attacksIn24h(victimPlayerId, now) < rules.attackLimitPer24h();
    }

    /** 记一次攻击。返回记账后该真人 24h 内的累计次数。 */
    public int recordAttack(String victimPlayerId, long now) {
        long[] stamps = attackLog.computeIfAbsent(victimPlayerId,
                k -> new long[rules.attackLimitPer24h() + 1]);
        // 环形写入：数组长度比上限多 1，所以「最旧的一条」总能在覆盖前被读到
        int slot = (int) (countOf(stamps) % stamps.length);
        stamps[slot] = now;
        return attacksIn24h(victimPlayerId, now);
    }

    /** 最近 24 小时内该真人被 Bot 攻击的次数。 */
    public int attacksIn24h(String victimPlayerId, long now) {
        long[] stamps = attackLog.get(victimPlayerId);
        if (stamps == null) {
            return 0;
        }
        long windowStart = now - 24L * 3600L * 1000L;
        int count = 0;
        for (long stamp : stamps) {
            if (stamp > 0 && stamp > windowStart) {
                count++;
            }
        }
        return count;
    }

    /** 清理超过 24h 的记录，避免这张表只增不减（内存泄漏而不是缓存）。 */
    public void evict(long now) {
        long windowStart = now - 24L * 3600L * 1000L;
        attackLog.entrySet().removeIf(entry -> {
            for (long stamp : entry.getValue()) {
                if (stamp > windowStart) {
                    return false;
                }
            }
            return true;
        });
    }

    private static long countOf(long[] stamps) {
        long count = 0;
        for (long stamp : stamps) {
            if (stamp > 0) {
                count++;
            }
        }
        return count;
    }

    // ---------- 密度调节（§五） ----------

    /**
     * Bot 目标数 = clamp(目标密度 × 在线真人数 − 在线真人数, 下限, 上限)。
     *
     * <p>§五 的公式写的是「目标密度 − 在线真人数」，但密度是**比值**（20:1）而不是数量，
     * 直接相减量纲不对。按比值展开就是「真人数 × 比值」才是 Bot 的目标数量。
     *
     * @param onlineHumans   在线真人数
     * @param dayOffset      开服第几天（0-based），用于在密度曲线上插值
     * @param floor          下限。真人为 0 时也要有 Bot，否则新服第一张地图是空的
     */
    public int targetBotCount(int onlineHumans, long dayOffset, int floor) {
        if (onlineHumans < 0) {
            throw new IllegalArgumentException("在线真人数不得为负，实际=" + onlineHumans);
        }
        if (floor < 0) {
            throw new IllegalArgumentException("下限不得为负，实际=" + floor);
        }
        long density = densityAt(dayOffset);
        long wanted = FixedPoint.round(FixedPoint.mul(FixedPoint.of(onlineHumans), density));
        return (int) Math.min(rules.maxPerServer(), Math.max(floor, wanted));
    }

    /**
     * 某一天的密度比值（定点）。
     *
     * <p>D1 / D7 / D30 三点之间线性插值，D30 之后取 D30 的值（稳态「按需补位」）。
     * 插值而不是阶梯：阶梯会让第 7 天零点那一瞬间 Bot 数量掉一大截，
     * 而玩家会精确地感觉到「昨天还热闹的地图今天空了」。
     */
    public long densityAt(long dayOffset) {
        if (dayOffset <= 0) {
            return rules.densityD1Fixed();
        }
        if (dayOffset >= 30) {
            return rules.densityD30Fixed();
        }
        if (dayOffset < 7) {
            return lerp(rules.densityD1Fixed(), rules.densityD7Fixed(), dayOffset, 7);
        }
        return lerp(rules.densityD7Fixed(), rules.densityD30Fixed(), dayOffset - 7, 23);
    }

    private static long lerp(long from, long to, long step, long span) {
        return from + (to - from) * step / span;
    }

    // ---------- 合规红线（§六 / §七，验收 5） ----------

    /**
     * Bot 能否担任某个组织职位。
     *
     * <p>§七 红线：禁止 Bot 担任国家官职或联盟盟主（官职是真人荣誉的核心载体）。
     * §六 三级社交分布表把这条细化成：小队可任普通成员；联盟可任普通成员但**不得任盟主**；
     * 国家可补位普通成员但**不得任任何官职**。
     *
     * @param scope    SQUAD / ALLIANCE / NATION
     * @param isLeader 是否是首领位（队长 / 盟主 / 国主）
     * @param isOffice 是否是官职（副盟主、长老、国家官员）
     */
    public boolean mayHoldOffice(String scope, boolean isLeader, boolean isOffice) {
        if (scope == null) {
            return false;
        }
        switch (scope) {
            case "SQUAD":
                // 小队只有队长与队员两档，而 §六 说 Bot「可任普通成员」——
                // 所以队长位也不行：一个 Bot 队长会替真人做「踢谁」这种组织决定
                return !isLeader;
            case "ALLIANCE":
                return !isLeader && !isOffice;
            case "NATION":
                return !isLeader && !isOffice;
            default:
                return false;
        }
    }

    /**
     * Bot 能否进入排行榜前 N 名（§七 红线：禁止占据需真人竞争的前 3 名奖励坑位）。
     *
     * <p><b>生产实际执行的是更严的一条</b>：榜的两侧都用 {@code BotRegistry.humanOnly} 把 Bot 从
     * <b>整张榜</b>摘掉（{@code RankBoardService.rankedEntries} 读侧 + 上报入口写侧，
     * 赛季结算 {@code SeasonSettlementService} 同一形状）。所以这个方法<b>没有生产调用点</b>，
     * 它留在这里是因为它是那条红线的<b>成文表述</b>（{@code B23_排行榜} / {@code C06_人机的拟人化与合规边界}
     * 都指向它，{@code BotSystemTest} 验收 5 逐名次断言它）。
     * 别把它当死代码删掉，也别以为改它就能改变榜的行为——要改榜上有没有 Bot，去看 {@code humanOnly} 那两处。
     *
     * @param rank 名次，1 起
     * @param rewardedTopN 有奖励的前 N 名
     */
    public boolean mayEnterRankTop(int rank, int rewardedTopN) {
        if (rank < 1) {
            throw new IllegalArgumentException("名次从 1 起，实际=" + rank);
        }
        return rank > rewardedTopN;
    }

    /**
     * Bot 能否出现在某个场景（§七 红线：禁止出现在付费弹窗、限时礼包倒计时；
     * 禁止冒充官方/客服/GM 发言）。
     *
     * <p><b>默认拒绝</b>：只放行明确列出的场景。反过来的写法（默认放行、黑名单排除）
     * 会在有人新增一个付费场景时静默漏掉 —— 而新增付费场景恰恰是最频繁的那类改动。
     */
    public boolean mayAppearIn(String scene) {
        if (scene == null) {
            return false;
        }
        switch (scene) {
            case "WORLD_MAP":
            case "ALLIANCE_CHAT":
            case "SQUAD_CHAT":
            case "WORLD_CHAT":
            case "RALLY":
            case "BATTLE":
            case "SCOUT_REPORT":
                return true;
            case "PAYMENT_POPUP":
            case "LIMITED_PACK_COUNTDOWN":
            case "OFFICIAL_ANNOUNCEMENT":
            case "CUSTOMER_SERVICE":
            case "PRIVATE_CHAT_TO_HUMAN":
            default:
                return false;
        }
    }

    public Rules rules() {
        return rules;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正定点数，实际=" + value);
        }
    }

    private static void requirePositiveRatio(long value, String field) {
        if (value <= 0 || value > FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 (0, 1.0] 的定点区间，实际=" + value);
        }
    }
}
