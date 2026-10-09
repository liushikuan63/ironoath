package com.ironoath.web.store.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;

/**
 * 职责：国战积分板的内存实现（dev / 单测零依赖启动）。
 * 依赖：{@link WarStore} 端口、{@link WarRulesAssembler}。
 *
 * <p>与其它内存存储同一套约定：进程重启即丢，生产必须设 {@code ironoath.storage=mongo}
 * （{@code WarBeansConfig} 会打进日志，{@code MongoStorageGuard} 会在 mongo 模式下拦住没换端口的情况）。
 *
 * <p><b>这里存的是 {@link WarScoreBoard.Snapshot} 而不是板子本身</b>：快照是不可变的，
 * 于是「读返回副本」这条约定不需要每次手写一份深拷贝就成立 —— {@link #findLatest} 每次都
 * {@code fromSnapshot} 重建一份新对象，调用方改完必须写回，改了不写回在 dev 下也看不见。
 * 与 {@code InMemoryNationStore} 存 {@code Nation}（可变）+ 读时重建相比，这一份少一个「忘记 detach」的口子。
 */
public final class InMemoryWarStore implements WarStore {

    /** 主键（见 {@link WarStore#documentIdOf}）→ 完整快照。用 LinkedHashMap 让 all 的顺序稳定。 */
    private final Map<String, WarScoreBoard.Snapshot> byId = new LinkedHashMap<>();
    private final Map<String, Long> versions = new LinkedHashMap<>();
    /** 规则不进快照（进了就等于把一次热更冻进存档），所以每次重建都要现取。 */
    private final WarRulesAssembler rules;

    public InMemoryWarStore(WarRulesAssembler rules) {
        this.rules = Objects.requireNonNull(rules, "rules 不得为 null");
    }

    @Override
    public synchronized boolean insertIfAbsent(WarScoreBoard board) {
        WarScoreBoard.Snapshot snapshot = requireBoard(board).toSnapshot();
        String id = WarStore.documentIdOf(board);
        if (byId.putIfAbsent(id, snapshot) != null) {
            return false;
        }
        versions.put(id, 0L);
        board.bindRepositoryVersion(0L);
        return true;
    }

    /**
     * 原子地开一场：判「有没有未结束的仗」与「插入」都在<b>同一把监视器里</b>（本方法的外层 monitor），
     * 所以两个国王在同一秒各自宣战时，第二个人一定拿到 false。
     *
     * <p>写成 {@code findLatest().isEmpty()} 再插入的话，内存版在 dev 照样全绿 ——
     * 因为测试都是单线程，而那对线程真正会撞上的窗口一次也没出现过。
     */
    @Override
    public synchronized boolean insertIfNoneActive(WarScoreBoard board) {
        requireBoard(board);
        for (WarScoreBoard.Snapshot stored : byId.values()) {
            if (stored.phase() != WarScoreBoard.Phase.SETTLED) {
                return false;
            }
        }
        return insertIfAbsent(board);
    }

    /**
     * 击杀归属。<b>整个「取最新 → 判参战 → 改 → 写回」在本对象的同一把监视器里完成</b>
     * （方法级 {@code synchronized} 与 {@link #insertIfNoneActive} 用的是同一把锁，
     * 所以"刚宣完战的第一场"与"同时打完的那一仗"不会互相踩）。
     *
     * <p>最新那一场若是 {@code SETTLED} 也按「没有活着的仗」处理：结算完的历史不许再被记分，
     * 否则重启后 {@code findLatest} 会把已结算的档当现役档继续加。
     */
    @Override
    public synchronized WarStore.KillResult recordKills(String killerNationId, String killerPlayerId,
                                                        long units, long now) {
        if (units <= 0L) {
            return WarStore.KillResult.SKIPPED;
        }
        WarScoreBoard.Snapshot stored = latestSnapshot().orElse(null);
        if (stored == null || stored.phase() == WarScoreBoard.Phase.SETTLED) {
            return WarStore.KillResult.NO_ACTIVE_WAR;
        }
        WarScoreBoard board = toDomain(stored);
        // 按时间算已经打完的：不记账（#754）。结算与发奖仍由下一次读面板来做 —— 见端口枚举那一段
        if (WarStore.dueToSettle(board, now)) {
            return WarStore.KillResult.EXPIRED;
        }
        WarStore.KillResult result = WarStore.applyKills(board, killerNationId, killerPlayerId, units);
        // 改的是重建出来的那份，必须整份写回；漏这一行的症状是"全服进度条永远不动"而不报错
        writeBound(board);
        return result;
    }

    /**
     * 记一笔疲劳（与 {@link #recordKills} 同一把锁、同一条纪律）。
     *
     * <p><b>两项增量都为 0 时直接返回</b>：连一次读都不做（未破墙、零伤兵都可能是 0，
     * 而"We 没发生"不该付一次重建板子的代价）。
     */
    @Override
    public synchronized WarStore.FatigueResult addFatigue(String fatigueNationId, String playerId,
                                                         long marches, long wounded) {
        if (marches <= 0L && wounded <= 0L) {
            return WarStore.FatigueResult.SKIPPED;
        }
        WarScoreBoard.Snapshot stored = latestSnapshot().orElse(null);
        if (stored == null || stored.phase() == WarScoreBoard.Phase.SETTLED) {
            return WarStore.FatigueResult.NO_ACTIVE_WAR;
        }
        WarScoreBoard board = toDomain(stored);
        WarStore.FatigueResult result =
                WarStore.applyFatigue(board, fatigueNationId, playerId, marches, wounded);
        if (result == WarStore.FatigueResult.APPLIED) {
            // 改的是重建出来的那份，必须整份写回；漏这一行的症状是"疲劳永远归零、闸门形同不存在"
            writeBound(board);
        }
        return result;
    }

    @Override
    public synchronized WarStore.FatigueResult addMarchFatigueOnce(String marchId, String fatigueNationId,
                                                                   String playerId, long departedAt) {
        WarScoreBoard.MarchFatigueReceipt receipt =
                new WarScoreBoard.MarchFatigueReceipt(marchId, fatigueNationId, playerId, departedAt);
        WarScoreBoard.Snapshot stored = byId.values().stream()
                .filter(snapshot -> snapshot.startedAt() <= departedAt)
                .max(Comparator.comparingLong(WarScoreBoard.Snapshot::startedAt)).orElse(null);
        if (stored == null) {
            return WarStore.FatigueResult.NO_ACTIVE_WAR;
        }
        WarScoreBoard board = toDomain(stored);
        boolean previouslyRecorded = board.marchFatigueReceipt(marchId) != null;
        WarStore.FatigueResult result = WarStore.applyMarchFatigue(board, receipt);
        if (result == WarStore.FatigueResult.APPLIED && !previouslyRecorded) {
            writeBound(board);
        }
        return result;
    }

    /**
     * 领一次全服奖励：判定与标记在<b>本对象的同一把监视器</b>里做完（与 recordKills 共用一把锁）——
     * 拆成"先查有没有领过、再记"两步的话，两个人同时点领取会各自读到名单里没有自己、各自写回，
     * 后写的那份把前一份盖掉：那正是"每人只领一次"在并发下失效的形状。
     *
     * <p><b>不排除 SETTLED 的板子</b>（与 recordKills 刻意相反）：目标达成与领取都发生在仗打完之后
     * 更常见 —— 结算把 phase 定格，但那份全服进度与领取名单仍然有效。
     */
    @Override
    public synchronized WarStore.GoalClaimResult claimServerGoal(String playerId) {
        WarScoreBoard.Snapshot stored = latestSnapshot().orElse(null);
        if (stored == null) {
            return WarStore.GoalClaimResult.NO_WAR;
        }
        WarScoreBoard board = toDomain(stored);
        WarStore.GoalClaimResult result = WarStore.applyGoalClaim(board, playerId);
        if (result == WarStore.GoalClaimResult.CLAIMED) {
            // 名单在板子上：标了不写回，下一次读还是"没领过"，于是这份奖励可以反复领
            writeBound(board);
        }
        return result;
    }

    @Override
    public synchronized void save(WarScoreBoard board) {
        String id = WarStore.documentIdOf(requireBoard(board));
        if (!byId.containsKey(id)) {
            throw new IllegalStateException("战事不存在，无法落盘：warId=" + id
                    + "。建档请走 insertIfAbsent —— save 静默插入会让并发建档插出两份同开场的档，"
                    + "而两份各自算各自的积分与疲劳");
        }
        writeBound(board);
    }

    @Override
    public synchronized Optional<WarScoreBoard> findLatest() {
        return latestSnapshot().map(this::toDomain);
    }

    /**
     * 惰性结算。<b>{@code synchronized} 不是装饰</b>：判到期、{@code settle()}、整份写回三步必须在
     * 本对象的同一把监视器里，与 {@link #insertIfNoneActive}／{@link #recordKills} 共用同一把锁 ——
     * 否则「两个人同时打开面板」会各推一次结算（第二次落在内核护栏上抛 {@code IllegalStateException}），
     * 而 dev 下单线程用例全绿，看不见这个窗口。
     */
    @Override
    public synchronized Optional<WarStore.Settlement> settleIfExpired(long now) {
        WarScoreBoard.Snapshot stored = latestSnapshot().orElse(null);
        if (stored == null) {
            return Optional.empty();
        }
        WarScoreBoard board = toDomain(stored);
        boolean settledNow = WarStore.dueToSettle(board, now);
        WarScoreBoard.Result result = null;
        if (settledNow) {
            result = board.settle(now);
            // 主键按 startedAt 推导，settle 不动它 ⇒ 写回必然落在同一档上（历史不会被"挪个位置"）
            writeBound(board);
        }
        return Optional.of(new WarStore.Settlement(board, settledNow, result));
    }

    /**
     * 按 {@code startedAt} 取最近那一份。<b>不依赖插入顺序</b>：内存 map 的顺序是写入顺序，
     * 而「先落盘的旧一场、后建档的新一场」完全可能让两者不一致 —— 拿顺序当时间就会在
     * Mongo 版（按 startedAt 排序）那边分家，等价测试正是为拦这个而存在。
     */
    private Optional<WarScoreBoard.Snapshot> latestSnapshot() {
        List<WarScoreBoard.Snapshot> snapshots = new ArrayList<>(byId.values());
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshots.stream()
                .max(Comparator.comparingLong(WarScoreBoard.Snapshot::startedAt))
                .orElseThrow());
    }

    /**
     * 这一对两国最近那一场。<b>与 Mongo 版是两种机制</b>（这里在 Java 侧筛行，那里用
     * {@code $and} 打在嵌套数组上），所以"两边给出同一场"这件事必须由
     * {@code WarStoreEquivalenceTest} 现证 —— 数组查询的语义（两个条件各自独立地对数组求值，
     * 因此要求<b>两个元素</b>都在）正是最容易在一侧写错成「一个元素满足任一条件」的地方。
     */
    @Override
    public synchronized Optional<WarScoreBoard> findLatestBetween(String nationA, String nationB) {
        return byId.values().stream()
                .filter(stored -> holdsBoth(stored, nationA, nationB))
                .max(Comparator.comparingLong(WarScoreBoard.Snapshot::startedAt))
                .map(this::toDomain);
    }

    /** 参战方那几行里是否同时出现这两个国家 id。 */
    private static boolean holdsBoth(WarScoreBoard.Snapshot stored, String nationA, String nationB) {
        return stored.nations().stream().anyMatch(row -> row.nationId().equals(nationA))
                && stored.nations().stream().anyMatch(row -> row.nationId().equals(nationB));
    }

    @Override
    public synchronized void clear() {
        byId.clear();
        versions.clear();
    }

    private WarScoreBoard toDomain(WarScoreBoard.Snapshot snapshot) {
        WarScoreBoard board = WarScoreBoard.fromSnapshot(snapshot, rules.rules());
        board.bindRepositoryVersion(versions.get(WarStore.documentIdOf(board)));
        return board;
    }

    private void writeBound(WarScoreBoard board) {
        String id = WarStore.documentIdOf(board);
        long current = versions.get(id);
        if (board.repositoryVersion() != current) {
            throw new IllegalStateException("战事快照版本冲突，请重读后重试：warId=" + id);
        }
        byId.put(id, board.toSnapshot());
        versions.put(id, current + 1L);
        board.bindRepositoryVersion(current + 1L);
    }

    private static WarScoreBoard requireBoard(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就没有可落盘的状态");
        }
        return board;
    }
}
