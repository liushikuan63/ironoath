package com.ironoath.core.nation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 职责：国战积分板、疲劳值与全服目标（B13 §一 §7，验收 6/7/10）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>积分制存在的唯一理由是防「最后一秒偷家」</b>（验收 6 后半句）。
 * 若胜负只看「结束时谁占着王城」，那么整场战争的最优策略就是藏兵到最后一分钟再冲一次 ——
 * 打了 179 分钟的国家会因为最后 1 分钟没守住而全盘皆输。
 * 那种规则下没人愿意先投入，国战会退化成一场谁更能憋的比试。
 * 所以积分拆成三项：<b>占领时长</b>（每分钟计分，偷家只能拿到不到 1 分钟）、
 * <b>击杀</b>（打得凶也有回报）、<b>占领建筑</b>（让「先拿关卡再打王城」这条常规路线有分数）。
 *
 * <p><b>禁止项：不要让国战积分在数据库层聚合</b>。所以本类是纯内存对象，
 * 由 game-web 在国战开始时载入、结束时落盘一次。
 * 200 QPS 的行军事件若在数据库层做 SUM，MongoDB 会先于游戏逻辑崩掉。
 *
 * <p><b>疲劳值逼迫联盟调度而不是堆人数</b>（§7、验收 7）：
 * 行军与伤兵分别累积，超过上限后不能再行军。
 * 只有行军疲劳的话，玩家可以派一支小部队反复骚扰而不受惩罚；
 * 只有伤兵疲劳的话，玩家可以派大部队打一两次就休息。
 * 两条一起才让「轮换班次」成为唯一可持续的打法。
 *
 * <p><b>全服目标每人只领一次</b>（验收 10）：领取记录是一个 Set，
 * 而判定与写入在同一步完成 —— 分开的话并发两次领取会同时通过检查。
 */
public final class WarScoreBoard {

    /** 国战阶段。 */
    public enum Phase {
        /** 筹备：联盟争夺周边关卡以获得进攻资格 */
        PREPARATION,
        /** 王城战进行中 */
        SIEGE,
        /** 已结束，积分定格 */
        SETTLED
    }

    /**
     * @param durationMillis          王城战时长。来源 global.WAR_DURATION_HOURS
     * @param gateCount               王城周边关卡数。来源 global.WAR_GATE_COUNT
     * @param occupyScorePerMinute    占领王城每分钟得分。来源 global.WAR_SCORE_OCCUPY_PER_MINUTE
     * @param killScorePerUnit        每击杀一个单位得分。来源 global.WAR_SCORE_KILL_PER_UNIT
     * @param buildingScorePerCapture 每占领一座建筑得分。来源 global.WAR_SCORE_BUILDING_PER_CAPTURE
     * @param fatiguePerMarch         每次行军累积的疲劳。来源 global.WAR_FATIGUE_PER_MARCH
     * @param fatiguePerWounded       每个伤兵累积的疲劳。来源 global.WAR_FATIGUE_PER_WOUNDED
     * @param fatigueMax              疲劳上限。来源 global.WAR_FATIGUE_MAX
     * @param serverGoalKills         全服累计击杀目标。来源 global.WAR_SERVER_GOAL_KILLS
     */
    public record Rules(long durationMillis, int gateCount, long occupyScorePerMinute,
                        long killScorePerUnit, long buildingScorePerCapture,
                        long fatiguePerMarch, long fatiguePerWounded, long fatigueMax,
                        long serverGoalKills) {
        public Rules {
            if (durationMillis <= 0) {
                throw new IllegalArgumentException("国战时长必须为正，实际=" + durationMillis);
            }
            if (gateCount < 1) {
                throw new IllegalArgumentException("关卡数必须 >= 1，实际=" + gateCount);
            }
            if (occupyScorePerMinute <= 0) {
                throw new IllegalArgumentException("占领分必须为正，否则「守得住」没有回报，实际="
                        + occupyScorePerMinute);
            }
            if (killScorePerUnit <= 0 || buildingScorePerCapture <= 0) {
                throw new IllegalArgumentException("击杀分与建筑分都必须为正");
            }
            if (fatiguePerMarch <= 0 && fatiguePerWounded <= 0) {
                throw new IllegalArgumentException("两项疲劳增量不能同时为 0：那样疲劳值形同不存在，"
                        + "而 §7 的设计意图是「逼迫联盟调度而非堆人数」");
            }
            if (fatigueMax <= 0) {
                throw new IllegalArgumentException("疲劳上限必须为正，实际=" + fatigueMax);
            }
            if (serverGoalKills <= 0) {
                throw new IllegalArgumentException("全服目标必须为正，实际=" + serverGoalKills);
            }
        }
    }

    /** 一个参战方的积分明细。三类分开存，因为验收 6 要求「三类积分计算正确」可分别核对。 */
    public record Score(long occupyScore, long killScore, long buildingScore) {
        public long total() {
            return occupyScore + killScore + buildingScore;
        }
    }

    /** 结算结果。 */
    public record Result(String winnerId, Map<String, Score> scores, long totalKills,
                         boolean serverGoalReached) {
        public Result {
            scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        }
    }

    private final Rules rules;
    private final long startedAt;
    private Phase phase = Phase.PREPARATION;
    /** 参战国家 id → 积分 */
    private final Map<String, long[]> scores = new LinkedHashMap<>();
    /** 参战国家 id → 已占领的关卡 id 集合 */
    private final Map<String, Set<String>> gates = new LinkedHashMap<>();
    /** 当前占着王城的国家；null 表示无人占领 */
    private String capitalHolder;
    /** 当前这段占领的起点时刻 */
    private long capitalHeldSince;
    /** 玩家 id → 疲劳值 */
    private final Map<String, Long> fatigue = new LinkedHashMap<>();
    /** 已领取全服奖励的玩家 id */
    private final Set<String> goalClaimed = new LinkedHashSet<>();
    private long totalKills;

    public WarScoreBoard(Rules rules, long startedAt) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
        this.startedAt = startedAt;
    }

    /** 加入一个参战方。 */
    public void registerNation(String nationId) {
        if (nationId == null || nationId.isBlank()) {
            throw new IllegalArgumentException("nationId 不得为空");
        }
        scores.computeIfAbsent(nationId, k -> new long[3]);
        gates.computeIfAbsent(nationId, k -> new LinkedHashSet<>());
    }

    /** 进入王城战阶段。要求至少一方拿到了进攻资格（占有关卡）。 */
    public void beginSiege(long now) {
        if (phase != Phase.PREPARATION) {
            throw new IllegalStateException("当前阶段不允许开始王城战：" + phase);
        }
        boolean qualified = false;
        for (Set<String> held : gates.values()) {
            if (!held.isEmpty()) {
                qualified = true;
            }
        }
        if (!qualified) {
            throw new IllegalStateException("没有任何联盟占领关卡，进攻资格不成立（B13 §7）");
        }
        phase = Phase.SIEGE;
        capitalHeldSince = now;
    }

    /** 占领一座关卡（筹备阶段）。 */
    public void captureGate(String nationId, String gateId) {
        requireSiegeOrPrep();
        requireRegistered(nationId);
        if (gateId == null || gateId.isBlank()) {
            throw new IllegalArgumentException("gateId 不得为空");
        }
        // 关卡是排他的：一座关卡同时只能属于一方
        for (Map.Entry<String, Set<String>> entry : gates.entrySet()) {
            entry.getValue().remove(gateId);
        }
        gates.get(nationId).add(gateId);
        scores.get(nationId)[2] += rules.buildingScorePerCapture();
    }

    /** 占领王城。换手持有时结算上一段的占领时长积分。 */
    public void captureCapital(String nationId, long now) {
        requireSiegeOrPrep();
        requireRegistered(nationId);
        settleOccupation(now);
        capitalHolder = nationId;
        capitalHeldSince = now;
        scores.get(nationId)[2] += rules.buildingScorePerCapture();
    }

    /**
     * 结算到目前为止的占领时长积分。
     *
     * <p><b>这一项就是防偷家的全部机制</b>：占领分按分钟累积，
     * 最后一秒才占上去的国家只能拿到不到 1 分钟的分，
     * 而从头守到尾的拿满 180 分钟 —— 差 180 倍，翻盘无从谈起。
     */
    private void settleOccupation(long now) {
        if (capitalHolder == null) {
            return;
        }
        long minutes = Math.max(0L, (now - capitalHeldSince) / 60_000L);
        if (minutes > 0) {
            scores.get(capitalHolder)[0] += minutes * rules.occupyScorePerMinute();
        }
        // 起点推进到「已经结算到的整分钟」，避免不足一分钟的零头被反复计算或彻底丢掉
        capitalHeldSince += minutes * 60_000L;
    }

    /** 记一次击杀。同时累加全服击杀数（全服目标）。 */
    public void recordKill(String killerNationId, long units) {
        requireSiegeOrPrep();
        requireRegistered(killerNationId);
        if (units <= 0) {
            throw new IllegalArgumentException("击杀数必须为正，实际=" + units);
        }
        scores.get(killerNationId)[1] += units * rules.killScorePerUnit();
        totalKills += units;
    }

    /**
     * 累积疲劳（验收 7）。
     *
     * @return 累积后的疲劳值
     */
    public long addFatigue(String playerId, long marches, long wounded) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (marches < 0 || wounded < 0) {
            throw new IllegalArgumentException("行军次数与伤兵数都不得为负：marches=" + marches
                    + " wounded=" + wounded);
        }
        long added = marches * rules.fatiguePerMarch() + wounded * rules.fatiguePerWounded();
        long next = Math.min(rules.fatigueMax(), fatigue.getOrDefault(playerId, 0L) + added);
        fatigue.put(playerId, next);
        return next;
    }

    /** 某个玩家还能不能行军（验收 7：超过上限后无法继续行军）。 */
    public boolean canMarch(String playerId) {
        return fatigue.getOrDefault(playerId, 0L) < rules.fatigueMax();
    }

    /** 某个玩家的疲劳值。 */
    public long fatigueOf(String playerId) {
        return fatigue.getOrDefault(playerId, 0L);
    }

    /**
     * 全服目标是否达成。
     *
     * <p>全服累计击杀包含不打国战的人的贡献（打野、打关卡都算），
     * 这是 §7「让非参战玩家也有参与感」的落点。
     */
    public boolean serverGoalReached() {
        return totalKills >= rules.serverGoalKills();
    }

    /**
     * 领取全服奖励（验收 10：每人只领一次）。
     *
     * <p><b>判定与写入在同一步</b>：分成「先查有没有领过」和「领了再记」两步的话，
     * 并发两次领取会同时通过检查 —— 那是先查后改的经典变体，
     * 而全服奖励可重复领取就会变成国战期间的一个刷金入口。
     *
     * @return true 表示本次领取成功；false 表示已经领过或目标未达成
     */
    public boolean claimServerGoal(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (!serverGoalReached()) {
            return false;
        }
        return goalClaimed.add(playerId);
    }

    /** 已领取全服奖励的人数。 */
    public int serverGoalClaimed() {
        return goalClaimed.size();
    }

    /** 结束国战并结算（验收 6）。 */
    public Result settle(long now) {
        if (phase == Phase.SETTLED) {
            throw new IllegalStateException("国战已经结算过了，重复结算会让积分被算两遍");
        }
        settleOccupation(now);
        phase = Phase.SETTLED;

        Map<String, Score> finalScores = new LinkedHashMap<>();
        String winner = null;
        long best = -1L;
        for (Map.Entry<String, long[]> entry : scores.entrySet()) {
            long[] parts = entry.getValue();
            Score score = new Score(parts[0], parts[1], parts[2]);
            finalScores.put(entry.getKey(), score);
            // 平分时不给胜者：两国同分意味着谁都没有赢，
            // 硬挑一个出来（比如按 id 字典序）会让玩家觉得结果是被系统指定的
            if (score.total() > best) {
                best = score.total();
                winner = entry.getKey();
            } else if (score.total() == best) {
                winner = null;
            }
        }
        return new Result(winner, finalScores, totalKills, serverGoalReached());
    }

    // ---------- 只读访问 ----------

    public Phase phase() {
        return phase;
    }

    public long startedAt() {
        return startedAt;
    }

    /** 剩余秒数（验收用的 WarStatusResp.remainingSec）。已结束为 0，绝不为负。 */
    public long remainingSeconds(long now) {
        if (phase != Phase.SIEGE) {
            return 0L;
        }
        return Math.max(0L, (startedAt + rules.durationMillis() - now) / 1000L);
    }

    public String capitalHolder() {
        return capitalHolder;
    }

    public long totalKills() {
        return totalKills;
    }

    /** 某个国家已占领的关卡数。 */
    public int gateCount(String nationId) {
        Set<String> held = gates.get(nationId);
        return held == null ? 0 : held.size();
    }

    /** 某一方是否有进攻资格（占了至少一座关卡）。 */
    public boolean isQualified(String nationId) {
        return gateCount(nationId) > 0;
    }

    public Set<String> registeredNations() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(scores.keySet()));
    }

    public Rules rules() {
        return rules;
    }

    private void requireSiegeOrPrep() {
        if (phase == Phase.SETTLED) {
            throw new IllegalStateException("国战已结算，不能再改积分");
        }
    }

    private void requireRegistered(String nationId) {
        if (!scores.containsKey(nationId)) {
            throw new IllegalStateException("该国家未登记为参战方：" + nationId);
        }
    }

    /** 积分明细快照（供中途查询，不结算占领分）。 */
    public Map<String, Score> snapshot() {
        Map<String, Score> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : scores.entrySet()) {
            long[] parts = entry.getValue();
            out.put(entry.getKey(), new Score(parts[0], parts[1], parts[2]));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 供仓储重建。 */
    public static WarScoreBoard restore(Rules rules, long startedAt, Phase phase,
                                        Map<String, long[]> scores, Map<String, Set<String>> gates,
                                        String capitalHolder, long capitalHeldSince,
                                        Map<String, Long> fatigue, Set<String> goalClaimed,
                                        long totalKills) {
        WarScoreBoard board = new WarScoreBoard(rules, startedAt);
        board.phase = phase;
        board.scores.putAll(scores);
        for (Map.Entry<String, Set<String>> entry : gates.entrySet()) {
            board.gates.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        board.capitalHolder = capitalHolder;
        board.capitalHeldSince = capitalHeldSince;
        board.fatigue.putAll(fatigue);
        board.goalClaimed.addAll(goalClaimed);
        board.totalKills = totalKills;
        return board;
    }
}
