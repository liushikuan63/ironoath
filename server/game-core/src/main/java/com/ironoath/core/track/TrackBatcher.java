package com.ironoath.core.track;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：埋点攒批策略 —— 攒够 N 条或超过 T 秒就交出一批（B16 §3，验收 3）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>禁止项写了两遍：不要让埋点逐条上报</b>。原因不是流量而是电量与丢包：
 * 一次战斗里可能产生十几个事件（发起、每回合、技能触发、结算），
 * 逐条上报就是十几个 HTTP 请求，微信小游戏在弱网下每个请求都可能超时重试，
 * 于是埋点本身成了弱网体验的主要负担 —— 而这恰恰是最需要埋点数据的场景。
 *
 * <p><b>为什么是泛型</b>：攒批这件事只依赖「一个时刻」，不依赖事件长什么样。
 * 客户端攒的是待发送的 HTTP 批次，服务端攒的是待写入的存储记录，
 * 两者形状不同而策略完全一致。若把事件形状写死在本类里，
 * 服务端就得在「策略对象的 Event」与「存储记录」之间来回翻译，
 * 而翻译层最常见的 bug 是漏字段 —— 漏掉 playerId 的话，事件还在，漏斗却断了。
 *
 * <p><b>本类不发请求也不写库，只决定「什么时候该交、交哪些」</b>：
 * 输出一批待处理的条目，由调用方（客户端网络层 / 服务端存储适配层）真正处理。
 * 这样攒批策略可以在单测里用可控时钟精确验证，不需要起 HTTP 服务或数据库。
 *
 * <p><b>队列长度被 maxBatchSize 从构造上限死</b>：每次入队后只要攒够一批就立刻交出去，
 * 所以队列里最多只有 maxBatchSize - 1 条待发条目，不需要额外的容量上限 ——
 * 一次长时间断网也不会让内存被埋点撑爆。
 * <b>断网期间的累积风险在调用方的重试缓冲区里</b>：本类交出的批次若发不出去，
 * 是调用方决定重投还是丢弃，那个缓冲区的上限必须在调用方设，
 * 在本类里设只会得到一个永远走不到的丢弃分支。
 *
 * <p><b>条目内容不做校验</b>：埋点字典是运营与分析侧的资产，
 * 在核心层校验事件名就等于把字典硬编码进代码，
 * 而验收 3 要求「所有关键按钮与漏斗节点均有事件」—— 那份清单会随版本变化。
 * 校验属于边界（客户端读 UI 输入、服务端读 HTTP 请求体），在边界上做。
 *
 * @param <T> 被攒批的条目类型
 */
public final class TrackBatcher<T> {

    /**
     * @param maxBatchSize   攒够多少条就交出一批。来源 global.TRACK_BATCH_MAX_SIZE
     * @param flushIntervalMillis 最长攒多久。来源 global.TRACK_BATCH_FLUSH_SECONDS
     */
    public record Rules(int maxBatchSize, long flushIntervalMillis) {
        public Rules {
            if (maxBatchSize < 1) {
                throw new IllegalArgumentException("maxBatchSize 必须 >= 1，否则永远攒不满也永远不发，实际="
                        + maxBatchSize);
            }
            if (flushIntervalMillis < 1) {
                throw new IllegalArgumentException("flushIntervalMillis 必须 >= 1，实际=" + flushIntervalMillis
                        + "。为 0 等于每条都立刻发，那就是禁止项说的逐条上报");
            }
        }
    }

    /** 一批待处理的条目。 */
    public record Batch<E>(List<E> items, String reason) {
        public Batch {
            items = List.copyOf(items);
        }

        public int size() {
            return items.size();
        }
    }

    private final Rules rules;
    private final List<T> queue = new ArrayList<>();
    /** 队首条目的入队时刻。攒批的时间窗从第一条算起，而不是从上一条交出后算起 */
    private long oldestAt;
    private int batchCount;

    public TrackBatcher(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 记一个条目。
     *
     * @param item 条目本身，不得为 null
     * @param ts   该条目的时刻（毫秒）。<b>由调用方给而不是本类取当前时间</b>：
     *             本类不读时钟，所以攒批可以在单测里被精确复现
     * @return 若这次入队触发了一批，返回该批；否则返回 null
     */
    public Batch<T> track(T item, long ts) {
        if (item == null) {
            throw new IllegalArgumentException("item 不得为 null");
        }
        if (queue.isEmpty()) {
            oldestAt = ts;
        }
        queue.add(item);
        if (queue.size() >= rules.maxBatchSize()) {
            return flush("攒满 " + rules.maxBatchSize() + " 条");
        }
        return null;
    }

    /**
     * 按时间触发一次检查。由调用方每秒（或每次 tick）调用。
     *
     * <p><b>本类不自己起定时器</b>：B00 禁止 @Scheduled 扫表结算的同一条纪律也适用于此 ——
     * 常驻定时器会让「什么时候该发」变成一件只能靠看日志才知道的事，
     * 而由调用方驱动则可以在单测里精确控制时钟。
     *
     * @return 到期则返回该批，否则 null
     */
    public Batch<T> tick(long now) {
        if (queue.isEmpty()) {
            return null;
        }
        if (now - oldestAt < rules.flushIntervalMillis()) {
            return null;
        }
        return flush("距首条已过 " + ((now - oldestAt) / 1000L) + " 秒");
    }

    /**
     * 立即交出全部（切后台、退出登录、崩溃前、进程关闭）。
     *
     * <p>崩溃前调用尤其重要：验收 9 要求崩溃上报带完整堆栈与 traceId，
     * 而崩溃前最后几个事件往往正是解释崩溃原因的那几个。
     * 它们若还攒在队列里，就随进程一起消失了。
     */
    public Batch<T> flushNow(String reason) {
        if (queue.isEmpty()) {
            return null;
        }
        return flush(reason);
    }

    private Batch<T> flush(String reason) {
        Batch<T> batch = new Batch<>(new ArrayList<>(queue), reason);
        queue.clear();
        oldestAt = 0L;
        batchCount++;
        return batch;
    }

    /**
     * 当前攒了多少条（未交出）。<b>恒小于 maxBatchSize</b>：
     * 攒够即交出去，所以调用方不必再为这个队列设内存上限。
     */
    public int pendingCount() {
        return queue.size();
    }

    /** 已交出多少批。埋点健康度的核心指标：批数远小于条目数说明批量在生效。 */
    public int batchCount() {
        return batchCount;
    }

    /** 队首条目已攒了多久（毫秒）。用于判断 flush 是否卡住。 */
    public long oldestAge(long now) {
        return queue.isEmpty() ? 0L : Math.max(0L, now - oldestAt);
    }

    public Rules rules() {
        return rules;
    }
}
