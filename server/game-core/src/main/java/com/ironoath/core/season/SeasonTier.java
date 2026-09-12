package com.ironoath.core.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 职责：赛季段位 —— 按 MatchPower 划档、赛季结束降段（B14 §2，验收 6）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>段位按 matchPower 而不是 displayPower 划分</b>（与 B08 分域联动）：
 * displayPower 含峰值记忆与全部已拥有武将，反映的是「我练了多少」；
 * matchPower 才是圈层校验用的那个数，反映的是「我现在能打谁」。
 * 用 displayPower 分段的话，一个卸了兵的玩家会掉段 —— 而他卸兵正是为了压分，
 * 于是「压分」这个 B08 明确要堵的行为在赛季里反而有了收益。
 *
 * <p><b>降段保留部分进度</b>（§2：通常降 1~2 段，保留部分进度降低挫败感）：
 * 掉到目标段位后，段位内的进度取原进度的一部分而不是清零。
 * 清零的表现是「王者段位赛季结束变成黄金 0 分」，玩家会觉得一个月的努力被抹掉了；
 * 而全保留又等于没有降段。所以两者之间取一个可配置的比例。
 *
 * <p><b>段位门槛必须严格递增</b>，构造期校验：非递增的门槛会让同一个战力
 * 同时满足两个段位，取哪一个取决于遍历顺序 —— 那种 bug 在改表之后才出现，
 * 而且每次重启可能不一样。
 */
public final class SeasonTier {

    /** 六档段位（B14 §2）。顺序即从低到高，ordinal 用于降段计算。 */
    public enum Tier {
        BRONZE, SILVER, GOLD, PLATINUM, DIAMOND, KING;

        /** 中文名。段位是玩家之间互相报的称呼，所以要有中文。 */
        public String displayName() {
            return switch (this) {
                case BRONZE -> "青铜";
                case SILVER -> "白银";
                case GOLD -> "黄金";
                case PLATINUM -> "铂金";
                case DIAMOND -> "钻石";
                case KING -> "王者";
            };
        }
    }

    /**
     * @param thresholds       每个段位的门槛（matchPower），长度必须等于段位数量，按 Tier 顺序
     * @param demoteSteps      赛季结束降几段。来源 global.SEASON_DEMOTE_STEPS
     * @param progressKeepFixed 降段后保留的段内进度比例（定点 0~1.0）。来源 global.SEASON_PROGRESS_KEEP
     */
    public record Rules(long[] thresholds, int demoteSteps, long progressKeepFixed) {
        public Rules {
            if (thresholds == null || thresholds.length != Tier.values().length) {
                throw new IllegalArgumentException("段位门槛必须有 " + Tier.values().length
                        + " 个（每个段位一个），实际=" + (thresholds == null ? "null" : thresholds.length));
            }
            long[] copy = thresholds.clone();
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] < 0) {
                    throw new IllegalArgumentException("段位门槛不得为负，实际=" + copy[i]);
                }
                if (i > 0 && copy[i] <= copy[i - 1]) {
                    throw new IllegalArgumentException("段位门槛必须严格递增：" + Tier.values()[i - 1]
                            + "=" + copy[i - 1] + " 而 " + Tier.values()[i] + "=" + copy[i]
                            + "。非递增会让同一个战力同时满足两个段位，取哪个取决于遍历顺序");
                }
            }
            if (copy[0] != 0) {
                throw new IllegalArgumentException("最低段位的门槛必须为 0，否则战力低于它的玩家没有段位，实际="
                        + copy[0]);
            }
            thresholds = copy;
            if (demoteSteps < 0) {
                throw new IllegalArgumentException("demoteSteps 不得为负，实际=" + demoteSteps
                        + "。升段不是赛季结束该做的事");
            }
            if (demoteSteps >= Tier.values().length) {
                throw new IllegalArgumentException("demoteSteps 不得大于等于段位总数（" + Tier.values().length
                        + "），实际=" + demoteSteps + "。降那么多等于把所有人清零，而 §2 要求「保留部分进度」");
            }
            if (progressKeepFixed < 0 || progressKeepFixed > 10_000L) {
                throw new IllegalArgumentException("progressKeep 必须落在 [0, 1.0] 的定点区间，实际="
                        + progressKeepFixed);
            }
        }
    }

    /** 一次段位判定的结果。 */
    public record Placement(Tier tier, long progressInTier, long nextThreshold) {
        /** 距下一段位还差多少 matchPower。已是最高段位时为 0。 */
        public long powerToNext() {
            return Math.max(0L, nextThreshold);
        }
    }

    private final Rules rules;

    public SeasonTier(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 按 matchPower 判段位。
     *
     * @param matchPower 匹配战力（**不是展示战力**，理由见类注释）
     */
    public Placement place(long matchPower) {
        if (matchPower < 0) {
            throw new IllegalArgumentException("matchPower 不得为负，实际=" + matchPower);
        }
        Tier tier = Tier.BRONZE;
        for (Tier candidate : Tier.values()) {
            if (matchPower >= rules.thresholds()[candidate.ordinal()]) {
                tier = candidate;
            }
        }
        int index = tier.ordinal();
        long floor = rules.thresholds()[index];
        long next = index + 1 < rules.thresholds().length ? rules.thresholds()[index + 1] : floor;
        return new Placement(tier, matchPower - floor, Math.max(0L, next - matchPower));
    }

    /**
     * 赛季结束的段位重置（验收 6：降段但保留部分进度）。
     *
     * @param current  赛季结束时的段位与进度
     * @return 下赛季的起始段位与进度
     */
    public Placement demote(Placement current) {
        if (current == null) {
            throw new IllegalArgumentException("current 不得为 null");
        }
        int target = Math.max(0, current.tier().ordinal() - rules.demoteSteps());
        Tier tier = Tier.values()[target];
        // 段内进度按配置比例保留，并且不能超过目标段位的跨度
        // （超过的话就等于「降了段但进度比升段前还高」，那是升段不是降段）
        long span = spanOf(tier);
        long kept = current.progressInTier() * rules.progressKeepFixed() / 10_000L;
        long progress = Math.max(0L, Math.min(span > 0 ? span - 1 : 0, kept));
        long floor = rules.thresholds()[target];
        long next = target + 1 < rules.thresholds().length ? rules.thresholds()[target + 1] : floor;
        return new Placement(tier, progress, Math.max(0L, next - (floor + progress)));
    }

    /** 某个段位的跨度（到下一段位门槛的距离）。最高段位没有跨度，返回 0。 */
    private long spanOf(Tier tier) {
        int index = tier.ordinal();
        if (index + 1 >= rules.thresholds().length) {
            return 0L;
        }
        return rules.thresholds()[index + 1] - rules.thresholds()[index];
    }

    /** 全部段位从低到高。供 UI 画段位条。 */
    public List<Tier> tiersInOrder() {
        List<Tier> out = new ArrayList<>();
        Collections.addAll(out, Tier.values());
        return Collections.unmodifiableList(out);
    }

    public Rules rules() {
        return rules;
    }
}
