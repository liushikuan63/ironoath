package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.web.reward.PlayerWallet;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 同一金币领取事实在内存与真实隔离Mongo库验证，不以跳过代表通过。 */
class GoldClaimStoreEquivalenceTest {
    private static TestMongo db;
    private static final long NOW = 1_760_000_000_000L;
    private static final RewardContext CTX = new RewardContext("free_gold", "signin", "signin:20261013",
            true, Set.of("signin-day:20261013", "signin-week:20261012:third"));

    @BeforeAll static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db).as("需要127.0.0.1:27017的隔离Mongo测试库，缺少不能算通过").isNotNull();
    }
    @AfterAll static void close() { if (db != null) db.close(); }
    private PlayerRepository store(String kind) {
        return "mongo".equals(kind) ? new MongoPlayerStore(db.template()) : new InMemoryPlayerStore();
    }
    private String player(PlayerRepository store) {
        String id = "P-gold-" + UUID.randomUUID();
        assertThat(store.insertIfAbsent(PlayerStoreContractTest.newSave(id, "dev-" + id))).isTrue();
        return id;
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void newWalletAndUnrelatedSaveKeepReceiptAndNeverRegrant(String kind) {
        var store = store(kind); String id = player(store);
        var first = new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW);
        assertThat(first.creditedNow()).isEqualTo(20);
        var after = store.findByPlayerId(id).orElseThrow();
        after.setCityLevel(2); store.save(after);
        var retry = new PlayerWallet(store).grantGoldOnce(id, 30, CTX, NOW + 1);
        assertThat(retry.creditedNow()).isZero();
        assertThat(retry.receipt().requested()).isEqualTo(20);
        assertThat(retry.receipt().facts()).isEqualTo(CTX.claimFacts());
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(220);
        assertThat(store.findByPlayerId(id).orElseThrow().goldClaims()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void failureAfterCommittedSaveIsRecoveredWithoutDuplicateGold(String kind) {
        var store = store(kind); String id = player(store);
        var wallet = new PlayerWallet(failingSave(store, true));
        assertThatThrownBy(() -> wallet.grantGoldOnce(id, 20, CTX, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("模拟提交回执丢失");
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(220);
        var retry = new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW + 1);
        assertThat(retry.creditedNow()).isZero();
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(220);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void failureBeforeSaveLeavesNeitherMoneyNorAcceptedFact(String kind) {
        var store = store(kind); String id = player(store);
        assertThatThrownBy(() -> new PlayerWallet(failingSave(store, false)).grantGoldOnce(id, 20, CTX, NOW))
                .isInstanceOf(IllegalStateException.class);
        var untouched = store.findByPlayerId(id).orElseThrow();
        assertThat(untouched.resource("GOLD").current()).isEqualTo(200);
        assertThat(untouched.goldClaims()).isEmpty();
        assertThat(new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW + 1).creditedNow()).isEqualTo(20);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void cappedCreditHasDurableOverflowAndMailReceipt(String kind) {
        var store = store(kind); String id = player(store);
        var capped = store.findByPlayerId(id).orElseThrow();
        capped.putResource("GOLD", new PlayerResourceState(999990, 1000000, 0, 0, NOW)); store.save(capped);
        var grant = new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW);
        assertThat(grant.creditedNow()).isEqualTo(10);
        assertThat(grant.receipt().overflow()).isEqualTo(10);
        assertThat(new PlayerWallet(store).pendingGoldClaims(id)).hasSize(1);
        new PlayerWallet(store).completeGoldOverflow(id, CTX.traceId(), "mail-fixed");
        assertThat(new PlayerWallet(store).pendingGoldClaims(id)).isEmpty();
        assertThat(new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW + 1).receipt().overflowMailId())
                .isEqualTo("mail-fixed");
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(1000000);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void concurrentFirstAcceptanceHasOneWinnerAndRetryIsSafe(String kind) throws Exception {
        var store = store(kind); String id = player(store);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var concurrent = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),
                new Class<?>[] {PlayerRepository.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(store, args);
                        if ("findByPlayerId".equals(method.getName()))
                            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> claim = () -> {
                try { new PlayerWallet(concurrent).grantGoldOnce(id, 20, CTX, NOW); return "accepted"; }
                catch (IllegalStateException e) {
                    if (!e.getMessage().contains("乐观锁")) throw e;
                    return "conflict";
                }
            };
            var a = pool.submit(claim); var b = pool.submit(claim);
            assertThat(java.util.List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("accepted", "conflict");
            assertThat(new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW + 1).creditedNow()).isZero();
            assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(220);
            assertThat(store.findByPlayerId(id).orElseThrow().goldClaims()).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    @org.junit.jupiter.api.Test
    void oldMongoDocumentWithoutNewFieldKeepsBalanceAndCanAcceptFirstClaim() {
        var store = store("mongo"); String id = player(store);
        db.template().updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().unset("goldClaims"), "player");
        assertThat(store.findByPlayerId(id).orElseThrow().goldClaims()).isEmpty();
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(200);
        new PlayerWallet(store).grantGoldOnce(id, 20, CTX, NOW);
        assertThat(store.findByPlayerId(id).orElseThrow().resource("GOLD").current()).isEqualTo(220);
        assertThat(store.findByPlayerId(id).orElseThrow().goldClaims()).hasSize(1);
    }

    private static PlayerRepository failingSave(PlayerRepository store, boolean afterCommit) {
        AtomicBoolean armed = new AtomicBoolean(true);
        return (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),
                new Class<?>[] {PlayerRepository.class}, (proxy, method, args) -> {
                    boolean fail = "save".equals(method.getName()) && armed.getAndSet(false);
                    if (fail && !afterCommit) throw new IllegalStateException("模拟提交前故障");
                    try {
                        Object result = method.invoke(store, args);
                        if (fail) throw new IllegalStateException("模拟提交回执丢失");
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }
}
