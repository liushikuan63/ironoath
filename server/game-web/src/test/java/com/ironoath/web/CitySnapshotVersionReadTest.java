package com.ironoath.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;

/** Controlled peer storage write models the CAS boundary after a distributed lock is lost; not a partition test. */
@SpringBootTest
@ActiveProfiles("test")
class CitySnapshotVersionReadTest {
    @Autowired CityAppService service;
    @Autowired PlayerInitService init;
    @SpyBean CityRepository cities;

    private String newPlayer() {
        String run=UUID.randomUUID().toString();
        return init.init(new PlayerInitReq(run,"city-snapshot-"+run,"城建快照",System.currentTimeMillis(),"")).playerId();
    }

    @Test void peerWriteAfterStateReadCannotBeOverwrittenUsingItsNewerVersion() {
        String uid=newPlayer();
        service.list(uid);
        assertThat(cities.findByPlayerId(uid).orElseThrow().extraQueues()).isZero();
        AtomicBoolean armed=new AtomicBoolean(true);
        doAnswer(call->{
            Optional<CityState> old=(Optional<CityState>)call.callRealMethod();
            old.ifPresent(state->peerWrite(uid,state,armed));
            return old;
        }).when(cities).findByPlayerId(uid);
        doAnswer(call->{
            Optional<CityRepository.VersionedCity> old=(Optional<CityRepository.VersionedCity>)call.callRealMethod();
            old.ifPresent(loaded->peerWrite(uid,loaded.state(),armed));
            return old;
        }).when(cities).findVersionedByPlayerId(uid);
        Throwable failure=catchThrowable(()->service.withSettledCity(uid,ctx->ctx.city().extraQueues()));
        assertThat(armed).as("peer确实在读取状态后写入，不能把未触发夹具当通过").isFalse();
        assertThat(cities.findByPlayerId(uid).orElseThrow().extraQueues()).as("peer城建变更必须保留").isEqualTo(1);
        assertThat(failure).as("旧状态只能用其原版本保存，必须拒绝覆盖新版本")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("乐观锁冲突");
    }
    @Test void existingCityUsesOnePairedReadWithoutSeparateStateOrVersion() {
        String uid=newPlayer();service.list(uid);clearInvocations(cities);
        int queues=service.withSettledCity(uid,ctx->ctx.city().extraQueues());assertThat(queues).isZero();
        verify(cities).findVersionedByPlayerId(uid);
        verify(cities,never()).findByPlayerId(uid);verify(cities,never()).versionOf(uid);
        verify(cities,never()).insertIfAbsent(eq(uid),any());
    }
    @Test void successfulInsertStillRereadsTheAuthoritativeStateAndVersion() {
        String uid=newPlayer();assertThat(cities.findVersionedByPlayerId(uid)).isEmpty();clearInvocations(cities);
        AtomicBoolean changed=new AtomicBoolean();
        doAnswer(call->{
            boolean inserted=(boolean)call.callRealMethod();
            if(inserted && changed.compareAndSet(false,true)) {
                CityState submitted=call.getArgument(1);var snapshot=submitted.snapshot();
                cities.save(uid,CityState.fromSnapshot(new CityState.Snapshot(snapshot.buildings(),1)),cities.versionOf(uid));
            }
            return inserted;
        }).when(cities).insertIfAbsent(eq(uid),any());
        int queues=service.withSettledCity(uid,ctx->ctx.city().extraQueues());assertThat(queues).isEqualTo(1);
        assertThat(changed).isTrue();verify(cities,times(2)).findVersionedByPlayerId(uid);
        assertThat(cities.findVersionedByPlayerId(uid).orElseThrow().state().extraQueues()).isEqualTo(1);
    }
    @Test void losingInsertRereadsTheWinnerInsteadOfReturningLocalFreshState() {
        String uid=newPlayer();assertThat(cities.findVersionedByPlayerId(uid)).isEmpty();clearInvocations(cities);
        AtomicBoolean raced=new AtomicBoolean();
        doAnswer(call->{
            Optional<CityRepository.VersionedCity> absent=(Optional<CityRepository.VersionedCity>)call.callRealMethod();
            if(absent.isEmpty() && raced.compareAndSet(false,true)) {
                var winner=new CityState();winner.restoreBuilding(new com.ironoath.core.city.BuildingInstance("peer-main","main_city",1,3,3));
                winner=CityState.fromSnapshot(new CityState.Snapshot(winner.snapshot().buildings(),1));
                assertThat(cities.insertIfAbsent(uid,winner)).isTrue();
            }
            return absent;
        }).when(cities).findVersionedByPlayerId(uid);
        int queues=service.withSettledCity(uid,ctx->ctx.city().extraQueues());assertThat(queues).isEqualTo(1);
        assertThat(raced).isTrue();verify(cities,times(2)).findVersionedByPlayerId(uid);
        verify(cities,times(2)).insertIfAbsent(eq(uid),any());
        assertThat(cities.findVersionedByPlayerId(uid).orElseThrow().state().findByConfigId("main_city").instanceId()).isEqualTo("peer-main");
    }
    private void peerWrite(String uid,CityState old,AtomicBoolean armed) {
        if(!armed.compareAndSet(true,false))return;
        var snapshot=old.snapshot();var peer=CityState.fromSnapshot(new CityState.Snapshot(snapshot.buildings(),1));
        cities.save(uid,peer,cities.versionOf(uid));
    }
}
