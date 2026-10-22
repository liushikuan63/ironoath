package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOccupationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 目标为空与资料缺失必须给出不同结论；夹具不伪装成实际玩家攻占。 */
class StrategicTargetDefenderTest {
    private final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private final WorldTargets.Target target=WorldTargets.from(configs).at(Coord.of(256,216));
    private final TargetOccupationStore owners=new InMemoryTargetOccupationStore();
    private final MarchRepository marches=new InMemoryMarchStore(32);
    private final SocialStore social=mock(SocialStore.class);
    private final StrategicTargetDefender service=new StrategicTargetDefender(configs,owners,marches,social);
    private TargetOccupationStore.Snapshot owner(TargetOccupationStore.Garrison garrison,long version,String op) {
        return new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                "N-owned","验收国",100,op,version,garrison);
    }
    private March stationed(String targetId,String nation) {
        var m=new March("owned-defender-march","owned-defender-leader",Coord.of(10,10),target.coord(),
                100,110,Map.of("unit_infantry_t4",2L),List.of(),1000,1,
                March.TargetType.STRATEGIC_TARGET,targetId,March.Action.ATTACK);
        m.attachToRally("owned-defender-rally");m.arrive(110);
        m.holdStrategicVictory(Map.of(m.playerId(),m.units()));
        var r=Rally.restore(m.rallyId(),Rally.Scope.NATION,nation,m.playerId(),2,1,100,110,
                Map.of(m.playerId(),new Rally.Participant(m.playerId(),m.units(),List.of())),
                Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
        r.depart(110);when(social.rallyOf(m.rallyId())).thenReturn(Optional.of(r));
        assertThat(marches.insertIfAbsent(m)).isTrue();return m;
    }

    @Test void onlyNeverOccupiedTargetUsesConfiguredInitialArmy() {
        var neutral=service.read(target);
        assertThat(neutral.initialNpc()).isTrue();assertThat(neutral.occupation()).isNull();
        assertThat(neutral.troops().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(60185L);
        assertThat(neutral.troops()).containsOnlyKeys("unit_infantry_t4","unit_cavalry_t4","unit_archer_t4","unit_siege_t4");
        verifyNoInteractions(social);
    }

    @Test void knownEmptyRetainsNationAndNeverResurrectsNpc() {
        var state=owner(TargetOccupationStore.Garrison.empty(),1,"withdraw-done");
        assertThat(owners.compareAndSet(state,0)).isTrue();
        var empty=service.read(target);
        assertThat(empty.initialNpc()).isFalse();assertThat(empty.troops()).isEmpty();
        assertThat(empty.occupation()).isEqualTo(state);assertThat(empty.march()).isNull();
        verifyNoInteractions(social);
    }

    @Test void actualTwoTroopsAreUsedInsteadOfFullConfigurationAndSnapshotIsFrozen() {
        var m=stationed("gate_north","N-owned");
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"capture"),0);
        var actual=service.read(target);
        assertThat(actual.initialNpc()).isFalse();assertThat(actual.troops()).containsExactlyEntriesOf(Map.of("unit_infantry_t4",2L));
        assertThat(actual.marchVersion()).isEqualTo(marches.versionOf(m.id()));
        assertThatThrownBy(()->actual.troops().put("unit_infantry_t4",100L)).isInstanceOf(UnsupportedOperationException.class);
        var later=marches.findById(m.id()).orElseThrow();later.beginGarrisonDefense();later.applyLosses(Map.of("unit_infantry_t4",1L));
        assertThat(actual.march().units()).containsEntry("unit_infantry_t4",2L);
    }

    @Test void legacyOwnerWithoutGarrisonFactsAndMissingBoundMarchFailClosed() {
        owners.compareAndSet(owner(null,1,"legacy-metadata"),0);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("驻军资料");
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed("missing"),2,"broken-binding"),1);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("行军");
    }

    @Test void wrongTargetAndWrongNationCannotMasqueradeAsDefender() {
        var m=stationed("gate_south","other-nation");
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"bad-anchor"),0);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("绑定");
        var separate=new InMemoryMarchStore(32);var valid=March.fromSnapshot(m.snapshot());
        // 同坐标但目标ID相同也仍须验证原国家集结，不能把敌方部队接给当前国家。
        var n=new March("another","owned-defender-leader",Coord.of(10,10),target.coord(),100,110,
                valid.units(),List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        n.attachToRally(m.rallyId());n.arrive(110);n.holdStrategicVictory(Map.of(n.playerId(),n.units()));separate.insertIfAbsent(n);
        var other=new InMemoryTargetOccupationStore();other.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(n.id()),1,"wrong-nation"),0);
        assertThatThrownBy(()->new StrategicTargetDefender(configs,other,separate,social).read(target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("国家");
    }

    @Test void fightingOrReturningMarchCannotBeReadAsStationedDefender() {
        var m=stationed("gate_north","N-owned");
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"capture"),0);
        long v=marches.versionOf(m.id());m.beginGarrisonDefense();marches.save(m,v);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("交战或返程");
        m.beginReturn(120,10);marches.save(m,marches.versionOf(m.id()));
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("交战或返程");
    }

    @Test void ownershipOrMarchVersionChangingDuringReadCannotFormMixedBattleInput() {
        var m=stationed("gate_north","N-owned");var before=owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"capture");
        owners.compareAndSet(before,0);
        var unstable=spy(owners);
        when(unstable.findByTargetIds(List.of("gate_north"))).thenReturn(Map.of("gate_north",before),Map.of("gate_north",owner(TargetOccupationStore.Garrison.empty(),2,"withdraw")));
        assertThatThrownBy(()->new StrategicTargetDefender(configs,unstable,marches,social).read(target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已变化");
        var changing=spy(marches);when(changing.versionOf(m.id())).thenReturn(1L,2L);
        assertThatThrownBy(()->new StrategicTargetDefender(configs,owners,changing,social).read(target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已变化");
    }

    @Test void mongoRebuiltStoresReadActualGarrisonAndEmptyWithoutNpcFallback() {
        try(var db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var source=stationed("gate_north","N-owned");
            var mongoMarches=new com.ironoath.web.store.mongo.MongoMarchStore(db.template(),32);
            var mongoOwners=new com.ironoath.web.store.mongo.MongoTargetOccupationStore(db.template());
            assertThat(mongoMarches.insertIfAbsent(source)).isTrue();
            assertThat(mongoOwners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(source.id()),1,"capture"),0)).isTrue();
            var restarted=new StrategicTargetDefender(configs,
                    new com.ironoath.web.store.mongo.MongoTargetOccupationStore(db.template()),
                    new com.ironoath.web.store.mongo.MongoMarchStore(db.template(),32),social);
            assertThat(restarted.read(target).troops()).containsExactlyEntriesOf(Map.of("unit_infantry_t4",2L));
            assertThat(mongoOwners.compareAndSet(owner(TargetOccupationStore.Garrison.empty(),2,"withdraw-done"),1)).isTrue();
            assertThat(restarted.read(target).troops()).isEmpty();
            assertThat(restarted.read(target).initialNpc()).isFalse();
        }
    }

    @ParameterizedTest @EnumSource(value=Rally.Status.class,names={"PREPARING","CANCELLED"})
    void uncommittedOrCancelledRallyCannotProvideGarrisonIdentity(Rally.Status status) {
        var m=stationed("gate_north","N-owned");
        var original=spy(social.rallyOf(m.rallyId()).orElseThrow());when(original.status()).thenReturn(status);
        when(social.rallyOf(m.rallyId())).thenReturn(Optional.of(original));
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"bad-rally-state"),0);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("集结状态");
    }

    @Test void marchCommanderMustBeOriginalRallyInitiator() {
        var m=stationed("gate_north","N-owned");
        var original=spy(social.rallyOf(m.rallyId()).orElseThrow());when(original.initiatorId()).thenReturn("other-commander");
        when(social.rallyOf(m.rallyId())).thenReturn(Optional.of(original));
        owners.compareAndSet(owner(TargetOccupationStore.Garrison.stationed(m.id()),1,"bad-commander"),0);
        assertThatThrownBy(()->service.read(target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("发起人");
    }
}
