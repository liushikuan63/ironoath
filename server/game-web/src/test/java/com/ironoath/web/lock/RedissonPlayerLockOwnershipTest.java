package com.ironoath.web.lock;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;
import com.ironoath.core.lock.PlayerLock;

/** Real Redis, separate client identities; cleanup is restricted to this test's generated player keys. */
@Timeout(15)
class RedissonPlayerLockOwnershipTest {
    private static RedissonClient client,peer;
    private final String namespace="owned-lock-release-"+UUID.randomUUID();
    private final List<String> owned=new ArrayList<>();
    private static RedissonClient connect() {
        var config=new Config();config.useSingleServer()
                .setAddress(System.getProperty("ironoath.test.redis.address","redis://127.0.0.1:6379"))
                .setConnectionPoolSize(4).setConnectionMinimumIdleSize(1).setConnectTimeout(1000).setTimeout(1000).setRetryAttempts(1);
        return Redisson.create(config);
    }
    @BeforeAll static void open(){client=connect();peer=connect();}
    @AfterAll static void close(){if(peer!=null)peer.shutdown();if(client!=null)client.shutdown();}
    @AfterEach void cleanupOwnedKeysOnly() {
        for(String id:owned) {
            assertThat(id).startsWith(namespace+"-");
            client.getLock(key(id)).forceUnlock();
        }
    }
    private String player(){String id=namespace+"-"+owned.size();owned.add(id);return id;}
    private static String key(String id){return "ironoath:lock:player:"+id;}
    private static boolean acquire(RLock lock,long timeout) {
        try{return lock.tryLock(timeout,TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    @Test void twoClientsAndThreadsStillSerializeTheSamePlayer() throws Exception {
        String id=player();var provider=new RedissonPlayerLock(client);var other=new RedissonPlayerLock(peer);
        var pool=Executors.newSingleThreadExecutor();var entered=new AtomicBoolean();
        try {
            provider.runLocked(id,1000,()->{
                var attempt=pool.submit(()->other.runLocked(id,100,()->{entered.set(true);return "unexpected";}));
                assertThatThrownBy(()->attempt.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(PlayerLock.LockTimeoutException.class);
                assertThat(entered).isFalse();return "held";
            });
            assertThat(other.runLocked(id,1000,()->"after release")).isEqualTo("after release");
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void innerReentrantReleaseDoesNotReleaseTheOuterOwner() {
        String id=player();var provider=new RedissonPlayerLock(client);var raw=client.getLock(key(id));
        provider.runLocked(id,1000,()->{
            provider.runLocked(id,1000,()->{assertThat(raw.getHoldCount()).isEqualTo(2);return "inner";});
            assertThat(raw.getHoldCount()).isEqualTo(1);assertThat(acquire(peer.getLock(key(id)),100)).isFalse();return "outer";
        });
        assertThat(raw.getHoldCount()).isZero();
    }
    @Test void lostOriginalOwnerCannotReleaseTheNewPeerOwner() {
        String id=player();var provider=new RedissonPlayerLock(client);var other=peer.getLock(key(id));
        try {
            assertThat(provider.runLocked(id,1000,()->{
                assertThat(client.getLock(key(id)).forceUnlock()).isTrue(); // this generated key only: simulate owner loss
                assertThat(acquire(other,1000)).isTrue();return "original action result";
            })).isEqualTo("original action result");
            assertThat(other.isHeldByCurrentThread()).isTrue();assertThat(other.getHoldCount()).isEqualTo(1);
            assertThat(other.remainTimeToLive()).isPositive();
        } finally {if(other.isHeldByCurrentThread())other.unlock();}
    }
    @Test void actionFailureStillReleasesForAnotherClient() {
        assertFailureThenPeerCanAcquire(new IllegalArgumentException("action failure"));
    }
    @Test void actionIllegalMonitorStateExceptionStillPropagatesAndReleases() {
        assertFailureThenPeerCanAcquire(new IllegalMonitorStateException("action's own IMSE"));
    }
    private void assertFailureThenPeerCanAcquire(RuntimeException original) {
        String id=player();var provider=new RedissonPlayerLock(client);
        assertThatThrownBy(()->provider.runLocked(id,1000,()->{throw original;})).isSameAs(original);
        var other=peer.getLock(key(id));boolean acquired=acquire(other,1000);
        try {assertThat(acquired).isTrue();}finally{if(acquired)other.unlock();}
    }
    @Test void differentPlayersRemainIndependent() {
        String first=player(),second=player();var provider=new RedissonPlayerLock(client);var other=new RedissonPlayerLock(peer);
        assertThat(provider.runLocked(first,1000,()->other.runLocked(second,1000,()->"independent"))).isEqualTo("independent");
    }
}
