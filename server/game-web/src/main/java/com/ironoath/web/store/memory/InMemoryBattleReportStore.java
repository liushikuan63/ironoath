package com.ironoath.web.store.memory;

import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        expired.forEach(byId::remove);
        return expired.size();
    }

    @Override
    public void clear() {
        byId.clear();
    }

    public int size() {
        return byId.size();
    }
}
