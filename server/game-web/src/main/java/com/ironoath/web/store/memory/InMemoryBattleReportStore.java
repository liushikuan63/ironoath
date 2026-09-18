package com.ironoath.web.store.memory;

import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：战报存储的内存实现 —— 供 dev 零依赖启动与全部单测使用。
 * 依赖：{@link BattleReportStore} 端口。
 *
 * <p>重启即丢。战报丢了的后果比资源丢了轻（不影响数值正确性），
 * 但玩家会失去「昨天那场是怎么输的」这条线索 —— 而复盘失败原因正是他变强的路径，
 * 所以生产必须落库（B16）。
 *
 * <p><b>不做按时间的定时清理</b>：{@link #purgeExpired} 由读路径惰性调用，
 * 与侦查报告、行军到期是同一套纪律（B00 陷阱 2：服务端无常驻定时器）。
 */
public final class InMemoryBattleReportStore implements BattleReportStore {

    private final Map<String, BattleReport> byId = new ConcurrentHashMap<>();

    /**
     * reportId → 被分享到的频道键（B22 §一 2）。**另存一份账而不是塞进战报记录**：
     * 记录不可变、`save` 又是"同 id 不覆盖"，把追加事实塞进去就要整份重写（含完整战果）。
     */
    private final Map<String, Set<String>> sharedTo = new ConcurrentHashMap<>();

    @Override
    public void save(BattleReport report) {
        if (report == null) {
            throw new IllegalArgumentException("战报不得为 null");
        }
        // putIfAbsent 而不是 put：reportId 由服务端生成，重复只可能是重放，
        // 覆盖会让「同一场战斗」在两次请求之间变成两份不同的记录
        byId.putIfAbsent(report.reportId(), report);
    }

    @Override
    public Optional<BattleReport> findById(String reportId) {
        return Optional.ofNullable(reportId == null ? null : byId.get(reportId));
    }

    @Override
    public List<BattleReport> reportsOf(String ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        List<BattleReport> out = new ArrayList<>();
        for (BattleReport report : byId.values()) {
            if (ownerId.equals(report.ownerId())) {
                out.add(report);
            }
        }
        // 倒序必须在存储层做：调用方拿到的是「最新在前」的语义，
        // 让每个调用方自己排序迟早会出现列表页与详情页顺序不一致
        out.sort(Comparator.comparingLong(BattleReport::createdAt).reversed()
                .thenComparing(BattleReport::reportId));
        return List.copyOf(out);
    }

    @Override
    public int purgeExpired(long nowMillis) {
        List<String> expired = new ArrayList<>();
        byId.forEach((id, report) -> {
            if (report.expiresAt() <= nowMillis) {
                expired.add(id);
            }
        });
        expired.forEach(id -> {
            byId.remove(id);
            // 分享账要跟着战报一起消失：留着它是纯泄漏，而战报一旦不存在，
            // 那本账能回答的问题（谁能看它）也不再有人问
            sharedTo.remove(id);
        });
        return expired.size();
    }

    @Override
    public void markShared(String reportId, String channelKey) {
        if (reportId == null || channelKey == null) {
            throw new IllegalArgumentException("reportId / channelKey 不得为 null");
        }
        // 战报不在册就不记：Mongo 的 updateFirst 打在空集合上是空操作，两边必须同一语义，
        // 否则单测（内存版）会"记得住一份不存在的战报"
        if (!byId.containsKey(reportId)) {
            return;
        }
        sharedTo.computeIfAbsent(reportId, key -> ConcurrentHashMap.newKeySet()).add(channelKey);
    }

    @Override
    public List<String> sharedChannels(String reportId) {
        if (reportId == null) {
            return List.of();
        }
        Set<String> keys = sharedTo.get(reportId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        // 字典序返回：Mongo 的 $addToSet 不保证插入顺序，两个实现要么都排序、要么等价用例在比运气
        List<String> out = new ArrayList<>(keys);
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    @Override
    public void clear() {
        byId.clear();
        sharedTo.clear();
    }

    public int size() {
        return byId.size();
    }
}
