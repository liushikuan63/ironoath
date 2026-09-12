package com.ironoath.web.ws;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 职责：把社交事件异步推给在线玩家（B10 验收 5：在线成员 3 秒内收到推送）。
 * 依赖：{@link PushGateway} 端口（由 {@code GameWebSocketHandler} 实现）。
 *
 * <p><b>为什么不用 {@code @Async}</b>：本项目没有任何地方声明 {@code @EnableAsync}，
 * 而 Spring 在缺少它时会让 {@code @Async} <b>静默退化成同步调用</b> ——
 * 不报错、不告警，注解看起来生效了，实际上广播仍在业务线程里跑。
 * 那正好违反 B10 禁止项「广播不得同步阻塞业务线程」，而且是最难发现的那种违反：
 * 代码审查看到的是 {@code @Async}，运行时才知道它是同步的。
 * 所以这里显式持有一个线程池，让「异步」这件事在代码里是可验证的事实而不是一个注解。
 *
 * <p><b>队列有界且满了就丢，绝不阻塞调用方</b>：调用方是战斗结算与社交写操作，
 * 它们都持有玩家锁。一次推送卡住 3 秒，锁就持有 3 秒，
 * 期间该玩家的所有请求全部排队 —— 而推送卡住的常见原因恰恰是网络不好，
 * 也就是所有人都在同时卡。有界队列 + 丢弃 + 计数，是把这种连锁反应限制在「少收到一条推送」，
 * 而「少收到一条推送」有离线补偿兜底（B10 验收 12），玩家下次上线能看到。
 *
 * <p><b>丢弃必须计数并打日志</b>：静默丢弃的话，「盟友被打了却没收到通知」会变成
 * 一条谁也复现不了的投诉 —— 玩家说没收到，日志里一切正常。
 */
@Component
public class SocialPushPublisher implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SocialPushPublisher.class);

    /** 推送线程数。推送是 IO 密集但量小（一次战斗最多通知一个联盟的在线成员），2 条足够。 */
    private static final int THREADS = 2;
    /**
     * 待推送任务上限。取 1000：一次国战里同时被打的联盟成员可能上百，
     * 每人一条推送就是一个任务；再大就只是在推迟「丢弃」这件事，
     * 而积压的推送送到时早就过了支援窗口，送出去也是误导。
     */
    private static final int QUEUE_CAPACITY = 1000;
    /** 单个推送的超时。WebSocket 写本身很快，慢的是对端不收；超时后放弃这一条。 */
    private static final long SHUTDOWN_WAIT_MILLIS = 2000L;

    private final PushGateway gateway;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();

    public SocialPushPublisher(PushGateway gateway) {
        this.gateway = gateway;
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "ironoath-social-push");
                    thread.setDaemon(true);
                    return thread;
                },
                // 满了就在提交线程里直接丢弃。CallerRunsPolicy 是最坏的选择 ——
                // 它会把推送搬回业务线程执行，正好是这里要避免的事
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 把一条事件推给一批玩家。<b>不阻塞、不抛异常</b>：调用方通常在玩家锁内。
     *
     * @param type    事件类型，客户端据此分派（形如 MEMBER_ATTACKED）
     * @param peers   收件人。离线的人会被跳过 —— 他们有离线补偿那条路（B10 验收 12）
     * @param payload 事件内容，会被序列化成 JSON
     */
    public void publish(String type, Collection<String> peers, Object payload) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> deliver(type, peers, payload));
        } catch (RejectedExecutionException e) {
            long total = dropped.incrementAndGet();
            LOG.warn("推送队列已满（{}），丢弃 {} 条 {} 推送给 {} 人。累计已丢 {} 条："
                            + "这些人要靠离线补偿在下次上线时看到，需要人去看是不是推送量异常",
                    QUEUE_CAPACITY, 1, type, peers.size(), total);
        }
    }

    /**
     * 全服广播：收件人是网关此刻的在线快照（B08 §4 的公敌档每 6 小时广播）。
     *
     * <p>与 {@link #publish} 同一条异步通道，因此同样<b>绝不阻塞调用线程</b> —— 调用方是视野下发，
     * 那是全服最频繁的一条读路径。离线的人不在这里：他们的兜底是「公敌的坐标本身已经不受迷雾保护」，
     * 上线打开地图就看得见，不需要一条 6 小时前的推送。
     *
     * @return 本次的候选（在线）人数。0 是正常值，不是失败 —— 夜里没人在线
     */
    public int broadcastToOnline(String type, Object payload) {
        Collection<String> online = gateway.onlinePlayerIds();
        publish(type, online, payload);
        return online.size();
    }

    private void deliver(String type, Collection<String> peers, Object payload) {
        int sent = 0;
        for (String peer : peers) {
            try {
                if (!gateway.isOnline(peer)) {
                    continue;
                }
                if (gateway.pushToPlayer(peer, type, payload)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // 一个人的会话坏了不该让同一批其他人收不到
                LOG.warn("推送给 {} 失败（type={}），跳过这一个", peer, type, e);
            }
        }
        published.addAndGet(sent);
        if (sent > 0) {
            LOG.info("已推送 {} 给 {} 人（候选 {} 人，其余离线）", type, sent, peers.size());
        }
    }

    /** 已成功推送的条数。健康度指标。 */
    public long publishedCount() {
        return published.get();
    }

    /** 因队列满而丢弃的条数。非零说明推送量超出了预算，需要人去看。 */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                int lost = executor.shutdownNow().size();
                if (lost > 0) {
                    LOG.warn("进程关闭时仍有 {} 条推送未发出，已丢弃（收件人靠离线补偿）", lost);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
