package com.ironoath.web.power;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：匹配池 —— 订阅 {@link PowerChangedEvent}，为「按战力挑对手」提供 O(1) 读取（B08 §1）。
 * 依赖：无（只是一个进程内的 map）。
 *
 * <p><b>它是缓存，不是权威</b>。权威值永远是 {@code PlayerSave.power()}：
 * <ul>
 *   <li>命中时用池里的值，省掉一次存档读取；</li>
 *   <li>未命中时调用方必须回落到存档（{@link #matchPowerOr} 的 fallback 参数就是这个用途）。</li>
 * </ul>
 * 尤其是<b>发起攻击时的圈层判定绝不允许只信这个池</b>：那是一次会扣兵、会结算战损的写操作，
 * 必须在玩家锁内重算。池的价值只在于把「1000 个候选逐个读存档」变成「1 次批量读 + N 次 map 查」。
 *
 * <p><b>进程内，不是分布式</b>：多实例部署时各实例的池互不可见，
 * 于是 A 实例上的玩家练兵，B 实例的池不会更新。这在单实例下不存在，
 * 且因为「未命中/命中都不影响正确性、只影响一次搜索的新鲜度」，
 * 多实例下的表现是搜索结果偶尔用上一个战力值 —— 可接受，但不是免费的。
 * TODO(B16)：换成 Redis 上的有序集合（ZSET by matchPower），
 * 那时「按战力区间捞候选」可以直接由存储完成，连半径过滤都能下推。
 */
@Component
public class MatchPool {

    private static final Logger LOG = LoggerFactory.getLogger(MatchPool.class);

    private final Map<String, Entry> byPlayer = new ConcurrentHashMap<>();

    /**
     * 池里的一条记录。
     *
     * @param matchPower   匹配战力（圈层口径）
     * @param displayPower 展示战力（排行榜口径）
     * @param atMillis     这次变更的时刻，用于判断新鲜度与排查
     */
    public record Entry(long matchPower, long displayPower, long atMillis) {
    }

    /** 战力变更时更新池。同步执行，只做一次 put（见 {@link PowerChangedEvent} 的类注释）。 */
    @EventListener
    public void onPowerChanged(PowerChangedEvent event) {
        if (event == null) {
            return;
        }
        byPlayer.put(event.playerId(),
                new Entry(event.matchPower(), event.displayPower(), event.atMillis()));
    }

    public Optional<Entry> find(String playerId) {
        return Optional.ofNullable(playerId == null ? null : byPlayer.get(playerId));
    }

    /**
     * 取匹配战力；池里没有就用调用方给的 fallback。
     *
     * <p>把 fallback 做成显式参数而不是返回 {@code Optional}，是为了让调用方无法「忘记回落」——
     * 一个返回 Optional 的接口在搜索这种批量场景里，最容易被写成 {@code orElse(0L)}，
     * 而 0 战力的候选会被圈层判定直接拒绝，表现是「附近明明有人却搜不到」。
     */
    public long matchPowerOr(String playerId, long fallback) {
        Entry entry = playerId == null ? null : byPlayer.get(playerId);
        return entry == null ? fallback : entry.matchPower();
    }

    public int size() {
        return byPlayer.size();
    }

    /**
     * 清空。仅供赛季重置（B14）与测试使用。
     *
     * <p>不做逐条过期清理：条目数等于玩家数，本身有界；
     * 而「按时间淘汰」会引入一个定时器，与 B00 的惰性结算纪律相冲突。
     */
    public void clear() {
        int removed = byPlayer.size();
        byPlayer.clear();
        if (removed > 0) {
            LOG.info("匹配池已清空 条目数={}", removed);
        }
    }
}
