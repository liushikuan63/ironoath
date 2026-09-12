package com.ironoath.web.store;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.web.store.memory.InMemoryArmyStore;

/** 部队仓储必须满足的版本化契约（内存实现）。 */
class ArmyStoreContractTest extends VersionedStoreContractTest<ArmyState> {

    private static final String PLAYER = "P-army";
    private ArmyRepository store = new InMemoryArmyStore();

    @Override protected String storeName() {
        return "InMemoryArmyStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryArmyStore();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(PLAYER, new ArmyState());
    }

    @Override protected StoreHandle<ArmyState> read() {
        ArmyState army = store.findByPlayerId(PLAYER).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到部队"));
        return new StoreHandle<>(army, store.versionOf(PLAYER));
    }

    @Override protected long observe(ArmyState army) {
        return army.totalTroops();
    }

    @Override protected void bump(ArmyState army) {
        army.add("unit_infantry_t1", 10L);
    }

    @Override protected void persist(StoreHandle<ArmyState> handle) {
        store.save(PLAYER, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(PLAYER);
    }
}
