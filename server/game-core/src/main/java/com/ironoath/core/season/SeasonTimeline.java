package com.ironoath.core.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ironoath.common.time.DayKey;

/**
 * 职责：赛季时间轴 —— 阶段定义、阶段切换、日期到阶段的映射（B14 §1，验收 8）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>禁止项：不要把赛季时间轴硬编码在代码里</b>。所以本类不持有任何具体天数 ——
 * 阶段列表由 {@link Rules} 从 season 表灌进来，换赛季只改配置（验收 5）。
 * 本类只提供「给定阶段表，某一天属于哪个阶段」这一套机制。
 *
 * <p><b>验收 8 的边界语义在构造期就钉死</b>：阶段区间是<b>左闭右开</b>
 * （[startDayOffset, startDayOffset + durationDays)）。
 * 于是「第 3 天 23:59:59」属于第 3 天（dayIndex=2），「第 4 天 00:00:00」属于第 4 天（dayIndex=3），
 * 两者恰好落在相邻两个阶段。若区间写成左闭右闭，第 4 天会同时属于两个阶段，
 * 而「同时属于两个阶段」的表现是切换时刻的行为取决于遍历顺序 —— 那是最难复现的一类 bug。
 *
 * <p><b>阶段之间不允许有空洞或重叠</b>，两条都在构造期校验：
 * 空洞意味着某些天没有阶段（那天该发什么奖励？该禁战还是开放 PVP？），
 * 重叠意味着一天有两个阶段。两种都会让「赛季进行到第 N 天」这个问题没有唯一答案。
 */
public final class SeasonTimeline {

    /**
     * 赛季阶段。取值与 B14 §二 的 SeasonPhase 一致。
     *
     * <p><b>阶段的语义差异必须在数据层强制</b>，不能只写在文档里：
     * PREPARE 禁战、EXPAND 开放 PVP、CAPITAL_WAR 限时开王城、SETTLE 发奖与段位重置、
     * REST 只展示。{@link #allowsPvp} 与 {@link #allowsCapitalWar} 把这些语义变成方法，
     * 于是「备战期能不能打人」这个问题只有一个答案，而不是散落在各个 service 里的 if。
     */
    public enum Phase {
        /** 备战期：开服保护、新手加成、禁战 */
        PREPARE,
        /** 扩张期：开放 PVP、领地争夺、联盟排名赛 */
        EXPAND,
        /** 王城战：限时开启中央王城，联盟集结攻防，结算归属 */
        CAPITAL_WAR,
        /** 结算期：按贡献发奖、排行、段位重置 */
        SETTLE,
        /** 休赛期：展示荣耀、预览下赛季规则变更 */
        REST;

        /** 本阶段是否允许 PVP。备战期禁战、休赛期与结算期也不开新的战端。 */
        public boolean allowsPvp() {
            return this == EXPAND || this == CAPITAL_WAR;
        }

        /** 本阶段是否开启中央王城。只有王城战阶段开。 */
        public boolean allowsCapitalWar() {
            return this == CAPITAL_WAR;
        }

        /** 本阶段是否执行结算（发奖 + 段位重置 + 归档）。 */
        public boolean settles() {
            return this == SETTLE;
        }

        /** 本阶段是否只读（展示荣耀、预览下赛季），不接受任何改变赛季状态的操作。 */
        public boolean readOnly() {
            return this == REST;
        }
    }

    /**
     * 一个阶段（= season 表的一行）。
     *
     * @param phaseNo        阶段序号，从 1 起连续
     * @param phase          阶段类型
     * @param startDayOffset 起始天（0-based，0 = 赛季第一天）
     * @param durationDays   持续天数
     */
    public record Stage(int phaseNo, Phase phase, long startDayOffset, long durationDays) {
        public Stage {
            if (phaseNo < 1) {
                throw new IllegalArgumentException("phaseNo 必须 >= 1，实际=" + phaseNo);
            }
            if (phase == null) {
                throw new IllegalArgumentException("phase 不得为 null");
            }
            if (startDayOffset < 0) {
                throw new IllegalArgumentException("startDayOffset 不得为负，实际=" + startDayOffset);
            }
            if (durationDays < 1) {
                throw new IllegalArgumentException("durationDays 必须 >= 1，实际=" + durationDays
                        + "。持续 0 天的阶段永远不会被命中，留着它只会让读表的人以为它存在");
            }
        }

        /** 结束天（不含）。左闭右开区间的右端。 */
        public long endDayOffsetExclusive() {
            return startDayOffset + durationDays;
        }

        /** 某一天是否落在本阶段内（左闭右开）。 */
        public boolean contains(long dayOffset) {
            return dayOffset >= startDayOffset && dayOffset < endDayOffsetExclusive();
        }
    }

    /**
     * @param stages   阶段列表，按 phaseNo 升序
     * @param seasonId 赛季 id。归档集合名 {@code season_<id>} 由它拼出
     */
    public record Rules(List<Stage> stages, String seasonId) {
        public Rules {
            if (seasonId == null || seasonId.isBlank()) {
                throw new IllegalArgumentException("seasonId 不得为空：归档集合名 season_<id> 靠它拼");
            }
            if (stages == null || stages.isEmpty()) {
                throw new IllegalArgumentException("赛季阶段不得为空：没有时间轴的赛季无法判断「现在该做什么」");
            }
            List<Stage> copy = new ArrayList<>(stages);
            copy.sort((a, b) -> Integer.compare(a.phaseNo(), b.phaseNo()));
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).phaseNo() != i + 1) {
                    throw new IllegalArgumentException("阶段序号必须从 1 起连续，实际缺了 " + (i + 1));
                }
            }
            // 无空洞、无重叠：后一阶段的起点必须恰好等于前一阶段的终点
            for (int i = 1; i < copy.size(); i++) {
                Stage previous = copy.get(i - 1);
                Stage current = copy.get(i);
                if (current.startDayOffset() != previous.endDayOffsetExclusive()) {
                    throw new IllegalArgumentException("阶段 " + previous.phaseNo() + " 与 " + current.phaseNo()
                            + " 之间" + (current.startDayOffset() > previous.endDayOffsetExclusive()
                                    ? "有空洞：第 " + previous.endDayOffsetExclusive() + " 天起没有阶段"
                                    : "有重叠：第 " + current.startDayOffset() + " 天同时属于两个阶段")
                            + "。「赛季进行到第 N 天」必须有唯一答案");
                }
            }
            if (copy.get(0).startDayOffset() != 0) {
                throw new IllegalArgumentException("第一个阶段必须从第 0 天开始，实际从第 "
                        + copy.get(0).startDayOffset() + " 天开始：那之前没有阶段，开服首日无规则可依");
            }
            stages = Collections.unmodifiableList(copy);
        }

        /** 赛季总天数。 */
        public long totalDays() {
            return stages.get(stages.size() - 1).endDayOffsetExclusive();
        }

        /** 归档集合名（B14 §4：赛季数据独立存储，绝不混入玩家主存档）。 */
        public String archiveCollection() {
            return "season_" + seasonId;
        }
    }

    private final Rules rules;

    public SeasonTimeline(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /** 某一天属于哪个阶段（阶段对象）；超出赛季总天数返回 null。 */
    public Stage stageAt(long dayOffset) {
        if (dayOffset < 0) {
            throw new IllegalArgumentException("dayOffset 不得为负，实际=" + dayOffset);
        }
        for (Stage stage : rules.stages()) {
            if (stage.contains(dayOffset)) {
                return stage;
            }
        }
        return null;
    }

    /**
     * 某一天属于哪个阶段（规则语义）；<b>赛季结束之后就是 {@link Phase#REST}</b>。
     *
     * <p><b>为什么表里没有 REST 这一行</b>：休赛期不是「赛季内的第 N 个阶段」，而是
     * 「这一季打完了、下一季还没开」的那段时间。给它补一行会把刚定稿的 45 天赛季轴撑成
     * 46 天以上（B14 §一时长已裁决以表为准），而它也不需要目标与奖励 —— 那正是「只展示荣耀」的意思。
     *
     * <p>{@link #stageAt} 仍然返回 null：调用方要能区分「赛季内第几天」与「无赛季可依」，
     * 后者需要的是归档与开下一季，不是查某个阶段的规则。
     */
    public Phase phaseAt(long dayOffset) {
        Stage stage = stageAt(dayOffset);
        return stage == null ? Phase.REST : stage.phase();
    }

    /** 某个时刻属于哪个阶段（规则语义，含赛季结束后的休赛期）。 */
    public Phase phaseAtTime(long now, long seasonStart) {
        return phaseAt(dayIndexOf(now, seasonStart));
    }

    /**
     * 某个时刻属于哪个阶段。
     *
     * @param now         服务端当前时刻（毫秒）
     * @param seasonStart 赛季开始的服务端时刻
     */
    public Stage stageAtTime(long now, long seasonStart) {
        if (now < seasonStart) {
            throw new IllegalArgumentException("now(" + now + ") 早于赛季开始时刻(" + seasonStart
                    + ")：赛季还没开始，不该有人来问阶段");
        }
        return stageAt(dayIndexOf(now, seasonStart));
    }

    /**
     * 某个时刻是赛季第几天（0-based）。
     *
     * <p><b>验收 8 的落点</b>：按 UTC+8 自然日计算，所以第 3 天的 23:59:59 仍是 dayIndex=2，
     * 第 4 天的 00:00:00 才变成 3。若按「从赛季锚点起满 24 小时」计算，
     * 阶段日界会跟赛季开始钟点走，与每日重置、Bot 作息不在同一条日历轴上。
     */
    public static long dayIndexOf(long now, long seasonStart) {
        return Math.max(0L, DayKey.daysBetween(seasonStart, now));
    }

    /** 某一天该阶段的结束时刻（供 UI 画倒计时）。 */
    public long phaseEndAt(long now, long seasonStart) {
        Stage stage = stageAtTime(now, seasonStart);
        if (stage == null) {
            return DayKey.startOfDayPlusDays(seasonStart, rules.totalDays());
        }
        return DayKey.startOfDayPlusDays(seasonStart, stage.endDayOffsetExclusive());
    }

    /** 当前是否允许 PVP。 */
    public boolean allowsPvp(long now, long seasonStart) {
        Stage stage = stageAtTime(now, seasonStart);
        return stage != null && stage.phase().allowsPvp();
    }

    /** 当前是否开启王城战。 */
    public boolean allowsCapitalWar(long now, long seasonStart) {
        Stage stage = stageAtTime(now, seasonStart);
        return stage != null && stage.phase().allowsCapitalWar();
    }

    /**
     * 当前是否只读（休赛期）。
     *
     * <p><b>赛季结束之后就是只读</b>：这里原本写成 {@code stage != null && ...}，
     * 于是「第 45 天之后」反而变成<b>非</b>只读 —— 等于从赛季结束到下一季开始之间的整段窗口
     * 都开着写路径，而且让 {@link Phase#REST} 这个枚举分支永远走不到（死代码看起来像没实现，
     * 就会有人去补一行表，把 45 天轴撑长）。
     */
    public boolean readOnly(long now, long seasonStart) {
        Stage stage = stageAtTime(now, seasonStart);
        return stage == null || stage.phase().readOnly();
    }

    public Rules rules() {
        return rules;
    }

    public String seasonId() {
        return rules.seasonId();
    }

    /** 赛季总天数。 */
    public long totalDays() {
        return rules.totalDays();
    }
}
