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
    /** 规则不进快照（进了就等于把一次热更冻进存档），所以每次重建都要现取。 */
    private final WarRulesAssembler rules;

    public InMemoryWarStore(WarRulesAssembler rules) {
        this.rules = Objects.requireNonNull(rules, "rules 不得为 null");
    }

    @Override
    public synchronized boolean insertIfAbsent(WarScoreBoard board) {
        WarScoreBoard.Snapshot snapshot = requireBoard(board).toSnapshot();
        return byId.putIfAbsent(WarStore.documentIdOf(board), snapshot) == null;
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
        WarScoreBoard.Snapshot snapshot = requireBoard(board).toSnapshot();
        for (WarScoreBoard.Snapshot stored : byId.values()) {
            if (stored.phase() != WarScoreBoard.Phase.SETTLED) {
                return false;
            }
        }
        byId.put(WarStore.documentIdOf(board), snapshot);
        return true;
    }

    @Override
    public synchronized void save(WarScoreBoard board) {
        String id = WarStore.documentIdOf(requireBoard(board));
        if (!byId.containsKey(id)) {
            throw new IllegalStateException("战事不存在，无法落盘：warId=" + id
                    + "。建档请走 insertIfAbsent —— save 静默插入会让并发建档插出两份同开场的档，"
                    + "而两份各自算各自的积分与疲劳");
        }
        byId.put(id, board.toSnapshot());
    }

    @Override
    public synchronized Optional<WarScoreBoard> findLatest() {
        return latestSnapshot().map(s -> WarScoreBoard.fromSnapshot(s, rules.rules()));
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

    @Override
    public synchronized void clear() {
        byId.clear();
    }

    private static WarScoreBoard requireBoard(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就没有可落盘的状态");
        }
        return board;
    }
}
