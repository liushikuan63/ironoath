package com.ironoath.web.store;

import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.web.store.memory.InMemoryCityStore;

/** 城建仓储必须满足的版本化契约（内存实现）。 */
class CityStoreContractTest extends VersionedStoreContractTest<CityState> {

    private static final String PLAYER = "P-city";
    private CityRepository store = new InMemoryCityStore();

    @Override protected String storeName() {
        return "InMemoryCityStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryCityStore();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(PLAYER, new CityState());
    }

    @Override protected StoreHandle<CityState> read() {
        CityState state = store.findByPlayerId(PLAYER).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到城建存档"));
        return new StoreHandle<>(state, store.versionOf(PLAYER));
    }

    @Override protected long observe(CityState state) {
        return state.buildings().size();
    }

    @Override protected void bump(CityState state) {
        state.restoreBuilding(new BuildingInstance(
                "b" + state.buildings().size() + "-" + System.nanoTime(), "farm", 1, 3, 3));
    }

    @Override protected void persist(StoreHandle<CityState> handle) {
        store.save(PLAYER, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(PLAYER);
    }
}
