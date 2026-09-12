package com.ironoath.web.store.memory;

import com.ironoath.core.gacha.GachaLogStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：抽卡合规日志的内存实现（B06 §6 / 验收 4）。
 * 依赖：game-core 的端口。
 *
 * <p><b>这是合规凭证，不是普通业务日志</b>。监管来查时要能按玩家与时间取出结构化记录，
 * 且必须包含 isPity（B06 禁止项里点名两次）。文本日志做不到这两点，所以它是端口。
 *
 * <p>内存实现重启即丢，而合规要求保留 90 天 —— 所以生产必须用 MongoDB 版（B16），
 * 且要带 {@code (playerId, drawnAt)} 复合索引与按保留期的清理任务。
 * 装配处用 WARN 日志显式提示这件事，避免有人在生产环境误用本实现。
 */
public final class InMemoryGachaLogStore implements GachaLogStore {

    private final Map<String, List<Entry>> byPlayer = new ConcurrentHashMap<>();

    @Override
    public void appendAll(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        // 一次十连必须同批写入：要么全写要么全不写。
        // 写一半会让「这次十连到底出了什么」变成无法回答的问题，而合规核查问的正是这个
        String playerId = entries.get(0).playerId();
        for (Entry entry : entries) {
            if (!entry.playerId().equals(playerId)) {
                throw new IllegalArgumentException("一批日志必须属于同一个玩家，混入了 "
                        + entry.playerId() + " 与 " + playerId);
            }
        }
        byPlayer.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(entries);
    }

    @Override
    public List<Entry> query(String playerId, long sinceMillis) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        List<Entry> stored = byPlayer.get(playerId);
        if (stored == null) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        synchronized (stored) {
            for (Entry entry : stored) {
                if (entry.drawnAt() >= sinceMillis) {
                    out.add(entry);
                }
            }
        }
        // 按时间升序：合规核查与客服回放都要按发生顺序看
        out.sort((a, b) -> Long.compare(a.drawnAt(), b.drawnAt()));
        return out;
    }

    @Override
    public int purgeBefore(long cutoffMillis) {
        // removeIf 只告诉「有没有删」不告诉「删了几条」，所以用「删前总数 - 删后总数」求差。
        // 合规审计要的就是「删了多少条、删的是哪段时间」，这个数字不能是猜的
        int before = countAll();
        for (List<Entry> stored : byPlayer.values()) {
            synchronized (stored) {
                stored.removeIf(e -> e.drawnAt() < cutoffMillis);
            }
        }
        return before - countAll();
    }

    private int countAll() {
        int total = 0;
        for (List<Entry> stored : byPlayer.values()) {
            synchronized (stored) {
                total += stored.size();
            }
        }
        return total;
    }

    public void clear() {
        byPlayer.clear();
    }

    public int playerCount() {
        return byPlayer.size();
    }
}
