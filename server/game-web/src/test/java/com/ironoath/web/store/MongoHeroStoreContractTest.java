package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.hero.EquipSlot;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.Lineup;
import com.ironoath.web.store.mongo.MongoHeroStore;

/**
 * 职责：武将存档的 Mongo 实现跑同一份版本化契约，外加一条<b>养成字段逐个</b>的落库往返。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p>补逐字段那条的理由与城建同一条：契约的可观察量只有"武将数量"，
 * 而武将最容易丢的恰恰是等级/经验/星级/觉醒/技能等级/装备位这些字段 ——
 * 少写一个不会让数量变化，只会让玩家某天发现"我明明觉醒过"。
 */
class MongoHeroStoreContractTest extends VersionedStoreContractTest<HeroRoster> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private HeroRepository store;
    private String playerId = "P-hero";

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Override protected String storeName() {
        return "MongoHeroStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoHeroStore(db.template());
        playerId = "P-hero-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(playerId, new HeroRoster());
    }

    @Override protected StoreHandle<HeroRoster> read() {
        HeroRoster roster = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到武将存档"));
        return new StoreHandle<>(roster, store.versionOf(playerId));
    }

    @Override protected long observe(HeroRoster roster) {
        return roster.heroCount();
    }

    @Override protected void bump(HeroRoster roster) {
        roster.obtain("hero_ssr_" + roster.heroCount());
    }

    @Override protected void persist(StoreHandle<HeroRoster> handle) {
        store.save(playerId, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(playerId);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    /** 养成一串字段 + 编队预设，落库后逐个对回来。 */
    @Test
    @DisplayName("落库往返不丢养成：等级/经验/星级/觉醒/两个技能等级/装备位/编队预设都要原样回来")
    void progressionAndLineupsSurviveTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        StoreHandle<HeroRoster> handle = read();
        HeroRoster writing = handle.state();
        writing.obtain("hero_ssr_01");
        writing.obtain("hero_ssr_02");
        Map<EquipSlot, String> equips = new EnumMap<>(EquipSlot.class);
        equips.put(EquipSlot.WEAPON, "equip_sword_01");
        writing.hero("hero_ssr_01").restore(37, 8_400L, 4, 2, 7, 5, equips);
        writing.restoreLineups(List.of(new Lineup(0, "hero_ssr_01", List.of("hero_ssr_02"))));
        persist(handle);

        HeroRoster back = read().state();
        HeroInstance restored = back.hero("hero_ssr_01");
        assertThat(restored).as("武将本体没回来，后面无从谈起").isNotNull();
        assertThat(restored.level()).isEqualTo(37);
        assertThat(restored.exp()).isEqualTo(8_400L);
        assertThat(restored.star()).isEqualTo(4);
        assertThat(restored.awaken()).as("觉醒（丢了就是玩家某天发现自己「明明觉醒过」）").isEqualTo(2);
        assertThat(restored.mainSkillLevel()).isEqualTo(7);
        assertThat(restored.subSkillLevel()).isEqualTo(5);
        assertThat(restored.equips()).as("装备位").containsEntry(EquipSlot.WEAPON, "equip_sword_01");
        assertThat(back.owns("hero_ssr_02")).as("第二个武将的拥有关系").isTrue();
        assertThat(back.heroCount()).isEqualTo(2);

        List<Lineup> lineups = back.snapshot().lineups();
        assertThat(lineups).as("编队预设要跟着回来").hasSize(1);
        assertThat(lineups.get(0).main()).isEqualTo("hero_ssr_01");
        assertThat(lineups.get(0).subs()).containsExactly("hero_ssr_02");
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：内存与 Mongo 的语义等价没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }
}
