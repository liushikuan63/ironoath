package com.ironoath.core.nation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    /**
     * 发起这一场的国家 id；{@code null} 表示这一场没有发起方记录（历史档，或用例直接建的板子）。
     *
     * <p><b>为什么这件事必须记在板子上，而不是结算时推出来</b>：宣战与结算是<b>两次请求</b>，
     * 中间只有这一块板子活着（{@code WarStore} 的既定形状是"开战载入、结束落盘一次"），
     * 而今天没有任何一处存着"这一场谁先动的手"：
     * <ul>
     *   <li>{@code WarStore#findLatestBetween} 是<b>刻意对称</b>的（只挡发起国的话，被打的一方可以
     *       立刻反宣、把击杀刷满），拿它当发起方来源会先把这条防刷破掉；</li>
     *   <li>{@code Nation.diplomacyOf} 里的 HOSTILE 行记的是"我敌视他"，<b>跨场留存</b> —— 上一场由对面
     *       发起，这一场两边就都是敌对行，推不出谁先动手。</li>
     * </ul>
     *
     * <p><b>为什么不按"参战方里登记顺序的第一行"猜</b>：内核 {@link #settle(long)} 的平分判定确实按
     * 行序遍历（见 {@link Snapshot} 的注释），但那是同一次遍历里的<b>比较</b>，不是把行序当业务身份。
     * 把"第一行 = 发起国"当成发钱依据，任何一次改登记顺序（将来让盟友参战就会改）都会
     * <b>静默把加成发给另一个国家</b>，而这条错误既不报错也不留日志。
     *
     * <p>为 {@code null} 留着的后果是安全的：{@code WAR_SEASON_INITIATOR_BONUS} 那一档不发而已
     * （少发可以补，错发给别国再回收就是事故）。
     */
    private final String initiatorNationId;
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
    /**
     * 玩家 id → 这一场里他消灭的单位数。<b>赛季积分要按人发，所以"谁打的"必须留在这块板上</b>：
     * 只记国家维度的击杀，到了赛季结算就分不出该给谁加分，届时唯一的补救办法是回头翻战报 ——
     * 而战报有 {@code BATTLE_REPORT_TTL_SECONDS} 的 TTL（`BattleReportService` 落的就是这份档），
     * <b>过期就查无此账</b>。用一份会过期的档去推一份要永久计分的账，正是"账本住在易失的盒子里"那一族。
     */
    private final Map<String, Long> playerKills = new LinkedHashMap<>();
    /** 已领取全服奖励的玩家 id */
    private final Set<String> goalClaimed = new LinkedHashSet<>();
    private long totalKills;

    /** 建一块<b>没有发起方记录</b>的板子（{@code null} 那一档的含义见 {@link #initiatorNationId}）。 */
    public WarScoreBoard(Rules rules, long startedAt) {
        this(rules, startedAt, null);
    }

    /**
     * @param initiatorNationId 发起这一场的国家 id；{@code null} 表示没有发起方记录。
     *                        <b>宣战那条路必须传</b>（{@code WarAppService#declare}），否则
     *                        {@code WAR_SEASON_INITIATOR_BONUS} 永远发不出去，而它恰恰是 V18 那节的
     *                        <b>主钩子</b>（"发动成本在发起方"）—— 这一档为 null 的表现不是报错，
     *                        而是"宣战的人拿不到发起加成"，玩家只会觉得这游戏不公平。
     *                        不校验它已经在 {@code scores} 里：登记参战方是调用方下一步的事，
     *                        构造顺序反过来就会在这里抛，而那是夹具顺序问题不是数据问题。
     */
    public WarScoreBoard(Rules rules, long startedAt, String initiatorNationId) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
        this.startedAt = startedAt;
        this.initiatorNationId = initiatorNationId == null || initiatorNationId.isBlank()
                ? null : initiatorNationId;
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

    /**
     * 记一次击杀（不知道或不该知道是谁打的时使用 —— 例如据点守军的自然减员）。
     *
     * <p>它只是 {@link #recordKill(String, String, long)} 传 null 的名字：<b>不记玩家维度账</b>，
     * 因此这一笔不会进任何人的赛季分。有真实击杀者的人请调三参版，
     * 否则赛季榜会少一块分而全服目标照样涨 —— 那种"两个数各对一半"最难查。
     */
    public void recordKill(String killerNationId, long units) {
        recordKill(killerNationId, null, units);
    }

    /**
     * 记一次击杀，并记下<b>是谁打的</b>。同时累加全服击杀数（全服目标）。
     *
     * <p><b>为什么国家分与个人账要在同一个方法里加</b>：这两个数必须永远同源。
     * 分成 {@code recordKill} 与 {@code addPlayerKills} 两个调用的话，任何一条调用路径漏抄第二个，
     * 症状就是"国家加了分、那个人在赛季榜上没动"，而不报错。
     *
     * @param killerPlayerId 击杀者的玩家 id；null 表示无主击杀（不计入赛季分）
     */
    public void recordKill(String killerNationId, String killerPlayerId, long units) {
        requireSiegeOrPrep();
        requireRegistered(killerNationId);
        if (units <= 0) {
            throw new IllegalArgumentException("击杀数必须为正，实际=" + units);
        }
        scores.get(killerNationId)[1] += units * rules.killScorePerUnit();
        totalKills += units;
        if (killerPlayerId != null && !killerPlayerId.isBlank()) {
            playerKills.merge(killerPlayerId, units, Long::sum);
        }
    }

    /**
     * 全服目标的另一种记法：<b>计入全服进度与个人账，但不给任何国家加分</b>。
     *
     * <p>为什么需要它而不是让调用方"自己判断要不要调 {@link #recordKill}"：B13 §7 明写
     * 「全服累计击杀包含不打国战的人的贡献（打野、打关卡都算）」，这是那一节唯一一条
     * 「不参战也有收益」的设计。若参战国之外的人干脆不上报，全服进度条就会在国战期间
     * <b>只涨于参战国的人</b>，而那句话正是这个玩法的存在理由。
     *
     * <p>同一条理由也决定了它<b>不能</b>顺手给国家加分：未参战的国家没有这一行的账，
     * {@code scores.get(nationId)} 会直接 NPE —— 那是"少一条判据就崩"的形状，不是设计。
     */
    public void recordServerKill(String killerPlayerId, long units) {
        requireSiegeOrPrep();
        if (units <= 0) {
            throw new IllegalArgumentException("击杀数必须为正，实际=" + units);
        }
        totalKills += units;
        if (killerPlayerId != null && !killerPlayerId.isBlank()) {
            playerKills.merge(killerPlayerId, units, Long::sum);
        }
    }

    /**
     * 某个玩家在这一场里的击杀数（赛季分的输入，见 {@link #playerKills} 那段）。
     * 没打过或无主击杀都回 0 —— 这条不抛，因为视图要能显示"你还没动手"而不是一句错误。
     */
    public long killsBy(String playerId) {
        return playerId == null ? 0L : playerKills.getOrDefault(playerId, 0L);
    }

    /** 这一场里所有有主击杀的账（不可变视图）。 */
    public Map<String, Long> playerKillLedger() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(playerKills));
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

    /** 发起这一场的国家 id；没有发起方记录时为 {@code null}（含义见 {@link #initiatorNationId}）。 */
    public String initiatorNationId() {
        return initiatorNationId;
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

    /**
     * 落盘用的完整快照：除 {@code rules} 之外，{@link #restore} 要吃的每一项都在这里。
     *
     * <p><b>为什么已有的 {@link #snapshot()} 不够</b>：那份只回积分明细，是给面板中途查询用的。
     * 重建一块板还必须知道 phase、关卡归属、王城持有者、<b>当前这段占领的起点</b>、疲劳表、
     * 已领取名单、全服击杀数。少带任何一项的表现都不是报错，而是<b>复活出一个假状态</b>：
     * <ul>
     *   <li>少 {@code capitalHeldSince}：同一段占领会被重新累积一遍（占领分翻倍）；</li>
     *   <li>少 {@code goalClaimed}：验收 10 的「每人只领一次」变成「重启就能再领一次」——那是刷金入口；</li>
     *   <li>少 {@code fatigue}：验收 7 的疲劳上限每次重启清零，「逼迫联盟调度」这条设计当场作废；</li>
     *   <li>少 {@code phase}：一场已结算的仗会被当成还在打，而 {@link #settle} 会再结算一次。</li>
     * </ul>
     *
     * <p><b>{@code rules} 刻意不进快照</b>，与 {@code Nation.Snapshot} 同一条理由：规则来自配置表且会热更，
     * 把某一次的值冻进存档，症状是改了 {@code global} 的 WAR_* 那几行对已有战事不生效且不报错。
     * 所以重建必须现取规则 —— 见 {@code WarRulesAssembler}。
     *
     * <p><b>{@code initiatorNationId} 反过来说要进快照</b>：它是<b>这一场的事实</b>而不是规则，
     * 而结算发发起加成发生在<b>另一次请求</b>里（宣战那次不会发奖）。不带它的表现是
     * "内存板上有、落盘再读回来就没了" —— 于是 dev 上一切正常、生产上发起方永远领不到加成。
     *
     * <p><b>为什么这里用 {@code List<NationRow>} 而不是内部的 {@code Map<String, long[]>}</b>：
     * 落盘形状只允许用<b>已经在 Mongo 那边验证过</b>的容器（List、Map&lt;String,Long&gt;、嵌套 record），
     * 原始类型数组进档在本仓没有先例，而这类转换问题只在真 Mongo 上炸、单测在内存实现上全绿
     * （同族前例：投影打在 record 文档上抛 {@code avatarId must not be null}）。
     * 顺带把「行序」变成显式的：{@link #settle} 的平分判定依赖遍历顺序，List 比 map 更诚实。
     */
    public record Snapshot(long startedAt, Phase phase, String initiatorNationId,
                           List<NationRow> nations,
                           String capitalHolder, long capitalHeldSince,
                           Map<String, Long> fatigue, Map<String, Long> playerKills,
                           List<String> goalClaimed, long totalKills) {

        public Snapshot {
            if (phase == null) {
                throw new IllegalArgumentException("phase 不得为 null：没有 phase 就不知道该不该继续记分");
            }
            nations = List.copyOf(nations == null ? List.of() : nations);
            fatigue = Collections.unmodifiableMap(new LinkedHashMap<>(
                    fatigue == null ? Map.of() : fatigue));
            playerKills = Collections.unmodifiableMap(new LinkedHashMap<>(
                    playerKills == null ? Map.of() : playerKills));
            goalClaimed = List.copyOf(goalClaimed == null ? List.of() : goalClaimed);
        }
    }

    /**
     * 快照里一个参战方的那一行：积分三项 + 它已占领的关卡。
     *
     * <p>关卡用 {@code List} 而不是 {@code Set}：落盘要保序，而「这座关卡归谁」本来就是排他的
     * （见 {@link #captureGate}），重复关卡是脏数据而不是另一种状态。
     */
    public record NationRow(String nationId, Score score, List<String> gates) {

        public NationRow {
            if (nationId == null || nationId.isBlank()) {
                throw new IllegalArgumentException("nationId 不得为空：空 id 的参战行谁都查不到");
            }
            if (score == null) {
                throw new IllegalArgumentException("score 不得为 null：少一份积分就没有那一方的名次");
            }
            gates = List.copyOf(gates == null ? List.of() : gates);
        }
    }

    /** 完整快照（落盘用）。与 {@link #snapshot()} 那份积分明细不是一件事，理由见 {@link Snapshot}。 */
    public Snapshot toSnapshot() {
        List<NationRow> rows = new ArrayList<>(scores.size());
        for (Map.Entry<String, long[]> entry : scores.entrySet()) {
            long[] parts = entry.getValue();
            Set<String> held = gates.get(entry.getKey());
            rows.add(new NationRow(entry.getKey(),
                    new Score(parts[0], parts[1], parts[2]),
                    held == null ? List.of() : List.copyOf(held)));
        }
        return new Snapshot(startedAt, phase, initiatorNationId, rows, capitalHolder, capitalHeldSince,
                new LinkedHashMap<>(fatigue), new LinkedHashMap<>(playerKills),
                List.copyOf(goalClaimed), totalKills);
    }

    /**
     * 由完整快照重建。
     *
     * @param rules 当前配置下的国战规则。<b>必须由调用方注入</b>：它不进快照（理由见 {@link Snapshot}），
     *              没有它就连「还剩多少秒」「还能不能再行军」都算不出来
     */
    public static WarScoreBoard fromSnapshot(Snapshot snapshot, Rules rules) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        Map<String, long[]> scoreMap = new LinkedHashMap<>();
        Map<String, Set<String>> gateMap = new LinkedHashMap<>();
        for (NationRow row : snapshot.nations()) {
            Score score = row.score();
            scoreMap.put(row.nationId(),
                    new long[]{score.occupyScore(), score.killScore(), score.buildingScore()});
            gateMap.put(row.nationId(), new LinkedHashSet<>(row.gates()));
        }
        return restore(rules, snapshot.startedAt(), snapshot.phase(),
                snapshot.initiatorNationId(), scoreMap, gateMap,
                snapshot.capitalHolder(), snapshot.capitalHeldSince(), snapshot.fatigue(),
                snapshot.playerKills(), new LinkedHashSet<>(snapshot.goalClaimed()),
                snapshot.totalKills());
    }

    /** 供仓储重建。 */
    public static WarScoreBoard restore(Rules rules, long startedAt, Phase phase,
                                        String initiatorNationId,
                                        Map<String, long[]> scores, Map<String, Set<String>> gates,
                                        String capitalHolder, long capitalHeldSince,
                                        Map<String, Long> fatigue, Map<String, Long> playerKills,
                                        Set<String> goalClaimed, long totalKills) {
        WarScoreBoard board = new WarScoreBoard(rules, startedAt, initiatorNationId);
        board.phase = phase;
        // 逐份 clone 数组：传进来的那份 map 常常就是调用方手里的活对象，共享数组等于读返回别名
        for (Map.Entry<String, long[]> entry : scores.entrySet()) {
            board.scores.put(entry.getKey(), entry.getValue().clone());
        }
        for (Map.Entry<String, Set<String>> entry : gates.entrySet()) {
            board.gates.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        board.capitalHolder = capitalHolder;
        board.capitalHeldSince = capitalHeldSince;
        board.fatigue.putAll(fatigue);
        board.playerKills.putAll(playerKills);
        board.goalClaimed.addAll(goalClaimed);
        board.totalKills = totalKills;
        return board;
    }
}
