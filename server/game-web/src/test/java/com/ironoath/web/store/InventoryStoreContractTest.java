package com.ironoath.web.store;

import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.web.store.memory.InMemoryInventoryStore;

/** 背包仓储必须满足的版本化契约（内存实现）。 */
class InventoryStoreContractTest extends VersionedStoreContractTest<Inventory> {

    private static final String PLAYER = "P-bag";
    private InventoryRepository store = new InMemoryInventoryStore();

    @Override protected String storeName() {
        return "InMemoryInventoryStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryInventoryStore();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(PLAYER, Inventory.empty(60));
    }

    @Override protected StoreHandle<Inventory> read() {
        Inventory inventory = store.findByPlayerId(PLAYER).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到背包"));
        return new StoreHandle<>(inventory, store.versionOf(PLAYER));
    }

    @Override protected long observe(Inventory inventory) {
        return inventory.countOf("item_speedup_build_1h");
    }

    @Override protected void bump(Inventory inventory) {
        inventory.add("item_speedup_build_1h", 1L, 99L);
    }

    @Override protected void persist(StoreHandle<Inventory> handle) {
        store.save(PLAYER, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(PLAYER);
    }
}
