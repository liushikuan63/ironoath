package com.ironoath.web.store;

import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.web.store.memory.InMemoryHeroStore;

/** 武将仓储必须满足的版本化契约（内存实现）。 */
class HeroStoreContractTest extends VersionedStoreContractTest<HeroRoster> {

    private static final String PLAYER = "P-hero";
    private HeroRepository store = new InMemoryHeroStore();

    @Override protected String storeName() {
        return "InMemoryHeroStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryHeroStore();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(PLAYER, new HeroRoster());
    }

    @Override protected StoreHandle<HeroRoster> read() {
        HeroRoster roster = store.findByPlayerId(PLAYER).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到武将表"));
        return new StoreHandle<>(roster, store.versionOf(PLAYER));
    }

    @Override protected long observe(HeroRoster roster) {
        return roster.heroCount();
    }

    @Override protected void bump(HeroRoster roster) {
        roster.obtain("hero_ssr_01_" + roster.heroCount());
    }

    @Override protected void persist(StoreHandle<HeroRoster> handle) {
        store.save(PLAYER, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(PLAYER);
    }
}
