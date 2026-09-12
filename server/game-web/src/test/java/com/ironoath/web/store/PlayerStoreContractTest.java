package com.ironoath.web.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/** 玩家仓储的版本化契约（内存实现）。与 Mongo 实现跑的是同一组断言。 */
class PlayerStoreContractTest extends VersionedStoreContractTest<PlayerSave> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long NOW = 1_760_000_000_000L;

    private PlayerRepository store = new InMemoryPlayerStore();
    private String playerId = "P-player";

    /** 每次插入都要一个新 deviceId：玩家表的唯一约束在 deviceId 上，复用会让并发用例全部冲突在第二顺位。 */
    static PlayerSave newSave(String playerId, String deviceId) {
        Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
        resources.put("WOOD", new PlayerResourceState(5_000L, 10_000L, 0L, 100L, NOW));
        resources.put("GOLD", new PlayerResourceState(200L, 1_000_000L, 0L, 0L, NOW));
        return PlayerSave.createNew(playerId, deviceId, "契约测试", 1, NOW, 1, resources,
                PlayerPower.zero(), null);
    }

    @Override protected String storeName() {
        return "InMemoryPlayerStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryPlayerStore();
        playerId = "P-player-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(newSave(playerId, "dev-" + playerId));
    }

    @Override protected StoreHandle<PlayerSave> read() {
        PlayerSave save = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到存档"));
        // 玩家仓储的版本随存档本身走（没有独立的 versionOf），所以读的那一刻就是它
        return new StoreHandle<>(save, save.version());
    }

    @Override protected long observe(PlayerSave state) {
        return state.cityLevel();
    }

    @Override protected void bump(PlayerSave state) {
        state.setCityLevel(state.cityLevel() + 1);
    }

    @Override protected void persist(StoreHandle<PlayerSave> handle) {
        store.save(handle.state());
    }

    @Override protected long storedVersion() {
        return read().readVersion();
    }
}
