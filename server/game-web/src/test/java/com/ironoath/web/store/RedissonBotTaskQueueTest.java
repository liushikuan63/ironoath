package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.store.redis.RedissonBotTaskQueue;

/** 真Redis两个独立客户端；只清随机命名空间，不改共享Redis配置/其他key。 */
class RedissonBotTaskQueueTest {
    static RedissonClient client;
    static RedissonBotTaskQueue queue;
    static final String namespace="owned_queue_"+UUID.randomUUID();
    static RedissonClient connect() {
        Config config=new Config();config.useSingleServer()
                .setAddress(System.getProperty("ironoath.test.redis.address","redis://127.0.0.1:6379"))
                .setConnectionPoolSize(4).setConnectionMinimumIdleSize(1).setConnectTimeout(1_000)
                .setTimeout(1_000).setRetryAttempts(1);
        return Redisson.create(config);
    }
    @BeforeAll static void open() { client=connect();queue=new RedissonBotTaskQueue(client,new ObjectMapper(),namespace); }
    @BeforeEach void reset() { queue.clear(); }
    @AfterAll static void close() { if(client!=null){try{if(queue!=null)queue.clear();}finally{client.shutdown();}} }
    private BotScheduler.BotTask action(String id,long due) {
        return BotScheduler.BotTask.actionAt(id,due,new BotDecisionTree.Decision(
                BotDecisionTree.Action.REACT_ATTACK,true,3_000,"回应明确对手，保留性格失误与延迟"));
    }
    @Test void replaceAndFirstEnrollmentKeepExactlyOneWholePendingCursor() {
        queue.schedule(BotScheduler.BotTask.decisionAt("a",100_000));var pending=action("a",103_000);
        queue.schedule(pending);assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.scheduleIfAbsent(BotScheduler.BotTask.decisionAt("a",200_000))).isFalse();
        assertThat(queue.dueBefore(102_999,10)).isEmpty();assertThat(queue.dueBefore(103_000,10)).containsExactly(pending);
        assertThat(queue.dueBefore(103_000,10)).isEmpty();
    }
    @Test void sameMillisecondOrderingAndPagingRetainEveryone() {
        queue.schedule(action("c",100_000));queue.schedule(action("a",100_000));queue.schedule(action("b",100_000));
        assertThat(queue.dueBefore(100_000,0)).isEmpty();assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.dueBefore(100_000,2)).extracting(BotScheduler.BotTask::botId).containsExactly("a","b");
        assertThat(queue.dueBefore(100_000,2)).extracting(BotScheduler.BotTask::botId).containsExactly("c");
    }
    @Test void freshClientRestoresCompleteActionWithoutRegistration() {
        var pending=action("a",100_000);queue.schedule(pending);var other=connect();
        try { assertThat(new RedissonBotTaskQueue(other,new ObjectMapper(),namespace).dueBefore(100_000,1)).containsExactly(pending); }
        finally { other.shutdown(); }
    }
    @Test void twoClientsClaimEachActualTaskOnlyOnce() throws Exception {
        for(int i=0;i<20;i++)queue.schedule(action("owned-"+i,100_000));
        var other=connect();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var peer=new RedissonBotTaskQueue(other,new ObjectMapper(),namespace);
            var first=pool.submit(()->{start.await();return queue.dueBefore(100_000,20);});
            var second=pool.submit(()->{start.await();return peer.dueBefore(100_000,20);});start.countDown();
            var claimed=new ArrayList<>(first.get(10,TimeUnit.SECONDS));claimed.addAll(second.get(10,TimeUnit.SECONDS));
            assertThat(claimed).hasSize(20);assertThat(claimed).extracting(BotScheduler.BotTask::botId).doesNotHaveDuplicates();
            assertThat(queue.size()).isZero();
        } finally { pool.shutdownNow();other.shutdown(); }
    }
    @Test void twoClientsFirstEnrollCannotCreateDuplicateCursor() throws Exception {
        var other=connect();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var peer=new RedissonBotTaskQueue(other,new ObjectMapper(),namespace);
            var a=action("a",100_000);var b=action("a",120_000);
            var first=pool.submit(()->{start.await();return queue.scheduleIfAbsent(a);});
            var second=pool.submit(()->{start.await();return peer.scheduleIfAbsent(b);});start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(queue.size()).isEqualTo(1);assertThat(queue.dueBefore(120_000,2)).singleElement().isIn(a,b);
        } finally { pool.shutdownNow();other.shutdown(); }
    }
    @Test void cancellationAndNamespaceIsolationTouchOnlyOwnedCursor() {
        var separate=new RedissonBotTaskQueue(client,new ObjectMapper(),namespace+"_other");
        try {
            queue.schedule(action("a",100_000));queue.schedule(action("b",100_000));separate.schedule(action("a",100_000));
            queue.cancel("a");assertThat(queue.dueBefore(100_000,20)).extracting(BotScheduler.BotTask::botId).containsExactly("b");
            assertThat(separate.size()).isEqualTo(1);
        } finally { separate.clear(); }
    }
    @Test void unreadablePayloadRemainsAvailableForRepairInsteadOfBeingLostOnRead() {
        queue.schedule(action("a",100_000));String key=RedissonBotTaskQueue.keyPrefix(namespace)+":payload";
        client.getMap(key,StringCodec.INSTANCE).put("a","not-json");
        assertThatThrownBy(()->queue.dueBefore(100_000,1)).isInstanceOf(IllegalStateException.class);
        assertThat(queue.size()).isEqualTo(1);assertThat(client.getMap(key,StringCodec.INSTANCE).get("a")).isEqualTo("not-json");
    }
}
