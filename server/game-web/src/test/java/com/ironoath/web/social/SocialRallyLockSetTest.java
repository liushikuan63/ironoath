package com.ironoath.web.social;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.social.Rally;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.nation.NationOperationLock;
import com.ironoath.web.service.SocialAppService;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 固定顺序覆盖多方上下文，旧单方生产口也必须复用；均为自有内存夹具。 */
class SocialRallyLockSetTest {
    @Mock SocialStore store;
    @Mock ArmyRepository armies;
    @Spy PlayerLock locks=new InJvmPlayerLock();
    @Spy SocialRulesAssembler rules=new SocialRulesAssembler(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));
    @InjectMocks SocialAppService service;
    private AutoCloseable mocks;
    private final Map<String,Rally> rallies=new ConcurrentHashMap<>();
    private final List<String> keys=Collections.synchronizedList(new ArrayList<>());
    @BeforeEach void open(){
        mocks=MockitoAnnotations.openMocks(this);
        when(store.rallyOf(anyString())).thenAnswer(inv->Optional.ofNullable(rallies.get(inv.getArgument(0))));
        when(store.pendingRallySettlementsOf(anyString())).thenReturn(List.of());
        doAnswer(inv->{keys.add(inv.getArgument(0));return inv.callRealMethod();})
                .when(locks).runLocked(anyString(),anyLong(),any());
        rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-b"));
        rallies.put("rally-z",rally("rally-z",Rally.Scope.NATION,"N-a"));
    }
    @AfterEach void close() throws Exception {mocks.close();}
    private Rally rally(String id,Rally.Scope scope,String group){
        var r=Rally.restore(id,scope,group,"owned-leader",2,1,100,110,
                Map.of("owned-leader",new Rally.Participant("owned-leader",Map.of("unit_infantry_t1",1L),List.of())),
                Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);r.depart(110);return r;
    }

    @Test void nationSetIsSortedDeduplicatedAndEmptyDoesNotAcquireKeys(){
        var lock=new NationOperationLock(locks);
        assertThat(lock.runLockedAll(List.of("N-b","N-a","N-b"),()->7)).isEqualTo(7);
        assertThat(keys).containsExactly("@nation:N-a","@nation:N-b");keys.clear();
        assertThat(lock.runLockedAll(List.of(),()->9)).isEqualTo(9);assertThat(keys).isEmpty();
        assertThatThrownBy(()->lock.runLockedAll(Arrays.asList("N-a",null),()->9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(keys).isEmpty();
    }

    @Test void allNationKeysPrecedeAllSortedRallyKeysAndDuplicatesDoNotRepeat(){
        assertThat(service.runRalliesDepartureLocked(List.of("rally-z","rally-a","rally-z"),()->42)).isEqualTo(42);
        assertThat(keys).containsExactly("@nation:N-a","@nation:N-b","@rally-departure:rally-a","@rally-departure:rally-z");
        verify(store,times(2)).pendingRallySettlementsOf("N-a");verify(store,times(2)).pendingRallySettlementsOf("N-b");
    }

    @Test void existingSingletonPathStillRecoversAndSupportsSameContextReentrancy(){
        assertThat(service.runRallyDepartureLocked("rally-a",()->service.runRallyDepartureLocked("rally-a",()->7))).isEqualTo(7);
        assertThat(keys).allMatch(k->k.equals("@nation:N-b") || k.equals("@rally-departure:rally-a"));
        verify(store,atLeast(2)).pendingRallySettlementsOf("N-b");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Rally.Scope.class,names={"SQUAD","ALLIANCE"})
    void nonNationalRallyKeepsOnlyItsRallyLockAndOneRecovery(Rally.Scope scope){
        rallies.put("owned-non-national",rally("owned-non-national",scope,"G-owned"));
        service.runRallyDepartureLocked("owned-non-national",()->true);
        assertThat(keys).containsExactly("@rally-departure:owned-non-national");
        verify(store,times(1)).pendingRallySettlementsOf("G-owned");
    }

    @Test void nestedSubsetCanReuseOriginalSupersetButExpansionRejectsBeforeNewKeys(){
        service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),()->
            service.runRallyDepartureLocked("rally-a",()->service.runRallyDepartureLocked("rally-z",()->true)));
        keys.clear();var calls=new AtomicInteger();
        service.runRallyDepartureLocked("rally-a",()->{
            assertThatThrownBy(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),calls::incrementAndGet))
                    .hasMessageContaining("扩张");return true;
        });
        assertThat(calls).hasValue(0);assertThat(keys).doesNotContain("@nation:N-a","@rally-departure:rally-z");
    }

    @Test void changedContextIsRedispatchedOnlyAfterOldNationLockReleases(){
        var changed=new AtomicBoolean();
        doAnswer(inv->{String key=inv.getArgument(0);keys.add(key);
            if(key.equals("@nation:N-b") && changed.compareAndSet(false,true))
                rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-new"));
            return inv.callRealMethod();}).when(locks).runLocked(anyString(),anyLong(),any());
        var result=service.runRallyDepartureLocked("rally-a",()->"done");
        assertThat(result).isEqualTo("done");
        assertThat(keys).containsExactly("@nation:N-b","@nation:N-new","@rally-departure:rally-a");
        verify(store,never()).pendingRallySettlementsOf("N-b");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void contextChangeOrDisappearanceAtRallyAcquisitionNeverRunsUnderOldKeys(boolean removed) throws Exception {
        var once=new AtomicBoolean();var calls=new AtomicInteger();
        doAnswer(inv->{String key=inv.getArgument(0);keys.add(key);
            if(key.equals("@rally-departure:rally-a") && once.compareAndSet(false,true)) {
                if(removed)rallies.remove("rally-a");
                else rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-new"));
            }
            return inv.callRealMethod();}).when(locks).runLocked(anyString(),anyLong(),any());
        if(removed) {
            assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",calls::incrementAndGet)).hasMessageContaining("缺失");
            assertThat(calls).hasValue(0);rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-b"));
        } else {
            assertThat(service.runRallyDepartureLocked("rally-a",calls::incrementAndGet)).isEqualTo(1);
            assertThat(keys).containsExactly("@nation:N-b","@rally-departure:rally-a","@nation:N-new","@rally-departure:rally-a");
        }
        verify(store,times(1)).pendingRallySettlementsOf("N-b"); // 仅旧国家阶段，没有旧集结阶段恢复。
        var pool=Executors.newSingleThreadExecutor();
        try {assertThat(pool.submit(()->new NationOperationLock(locks).runLocked("N-b",()->true)).get(5,TimeUnit.SECONDS)).isTrue();}
        finally{pool.shutdownNow();}
    }

    @Test void nestedSameIdWithChangedParentCountryRejectsBeforeAnyNewCountryKey(){
        var calls=new AtomicInteger();
        service.runRallyDepartureLocked("rally-a",()->{
            rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-new"));
            assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",calls::incrementAndGet)).hasMessageContaining("上下文已变化");
            return true;
        });
        assertThat(calls).hasValue(0);assertThat(keys).doesNotContain("@nation:N-new");
        verify(store,never()).pendingRallySettlementsOf("N-new");
        assertThat(service.runRallyDepartureLocked("rally-a",calls::incrementAndGet)).isEqualTo(1);
    }

    @Test void inRallyRecoveryFailurePropagatesOriginalAndReleasesCompleteSet() throws Exception {
        var reads=new AtomicInteger();var calls=new AtomicInteger();var failure=new IllegalStateException("owned in-rally recovery failed");
        when(store.pendingRallySettlementsOf("N-b")).thenAnswer(inv->{if(reads.incrementAndGet()==2)throw failure;return List.of();});
        assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",calls::incrementAndGet)).isSameAs(failure);
        assertThat(calls).hasValue(0);assertThat(keys).containsExactly("@nation:N-b","@rally-departure:rally-a");
        doReturn(List.of()).when(store).pendingRallySettlementsOf("N-b");
        var pool=Executors.newSingleThreadExecutor();
        try {assertThat(pool.submit(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),calls::incrementAndGet))
                .get(5,TimeUnit.SECONDS)).isEqualTo(1);}
        finally{pool.shutdownNow();}
    }

    @Test void missingContextAndRecoveryFailureNeverRunActionAndDoNotPoisonLaterCalls(){
        var action=new AtomicInteger();
        assertThatThrownBy(()->service.runRalliesDepartureLocked(List.of("missing"),action::incrementAndGet))
                .isInstanceOf(RuntimeException.class);assertThat(keys).isEmpty();
        var failure=new IllegalStateException("owned recovery unavailable");
        when(store.pendingRallySettlementsOf("N-b")).thenThrow(failure);
        assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",action::incrementAndGet)).isSameAs(failure);
        assertThat(action).hasValue(0);
        doReturn(List.of()).when(store).pendingRallySettlementsOf("N-b");
        assertThat(service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),action::incrementAndGet)).isEqualTo(1);
    }

    @Test void actionFailureReleasesAllKeysAndClearsNestedScope() throws Exception {
        var failure=new IllegalStateException("owned action failed");
        assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",()->{throw failure;})).isSameAs(failure);
        assertThat(service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),()->1)).isEqualTo(1);
        var pool=Executors.newSingleThreadExecutor();
        try {assertThat(pool.submit(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),()->2))
                .get(5,TimeUnit.SECONDS)).isEqualTo(2);}
        finally{pool.shutdownNow();}
    }

    @Test void laterLockAcquisitionFailureReleasesEarlierLocksForAnotherThread() throws Exception {
        var failure=new IllegalStateException("owned later lock unavailable");var called=new AtomicInteger();
        doThrow(failure).when(locks).runLocked(eq("@rally-departure:rally-z"),anyLong(),any());
        assertThatThrownBy(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),called::incrementAndGet)).isSameAs(failure);
        assertThat(called).hasValue(0);
        doCallRealMethod().when(locks).runLocked(eq("@rally-departure:rally-z"),anyLong(),any());
        var pool=Executors.newSingleThreadExecutor();
        try {assertThat(pool.submit(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),called::incrementAndGet))
                .get(5,TimeUnit.SECONDS)).isEqualTo(1);}
        finally{pool.shutdownNow();}
    }

    @Test void anotherNationFacadeCannotExpandAnAlreadyHeldCountrySet(){
        var outer=new NationOperationLock(locks);var calls=new AtomicInteger();
        assertThatThrownBy(()->outer.runLocked("N-b",()->
                service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),calls::incrementAndGet)))
                .hasMessageContaining("扩张");
        assertThat(calls).hasValue(0);assertThat(keys).containsExactly("@nation:N-b");
    }

    @Test void nationFacadeReentryRetainsTheFullOriginalSet(){
        var one=new NationOperationLock(locks);var two=new NationOperationLock(locks);
        assertThat(one.runLockedAll(List.of("N-a","N-b"),()->
                two.runLocked("N-b",()->two.runLocked("N-a",()->7)))).isEqualTo(7);
    }

    @Test void independentLockProvidersDoNotShareAFalseNationScope(){
        var one=new NationOperationLock(locks);var independent=new NationOperationLock(new InJvmPlayerLock());
        assertThat(one.runLocked("N-b",()->independent.runLocked("N-a",()->7))).isEqualTo(7);
    }

    @Test void repeatedContextChangesStopAfterTwoAttemptsWithoutActionOrRecovery(){
        var changed=new AtomicInteger();var action=new AtomicInteger();
        doAnswer(inv->{String key=inv.getArgument(0);keys.add(key);
            if(key.startsWith("@nation:"))rallies.put("rally-a",rally("rally-a",Rally.Scope.NATION,"N-change-"+changed.incrementAndGet()));
            return inv.callRealMethod();}).when(locks).runLocked(anyString(),anyLong(),any());
        assertThatThrownBy(()->service.runRallyDepartureLocked("rally-a",action::incrementAndGet)).hasMessageContaining("持续变化");
        assertThat(action).hasValue(0);assertThat(changed).hasValue(2);
        verify(store,never()).pendingRallySettlementsOf(anyString());
    }

    @Test void emptyOrInvalidRallySetsCannotRunAnUnprotectedAction(){
        var action=new AtomicInteger();
        assertThatThrownBy(()->service.runRalliesDepartureLocked(List.of(),action::incrementAndGet)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.runRalliesDepartureLocked(List.of(" "),action::incrementAndGet)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.runRalliesDepartureLocked(Arrays.asList("rally-a",null),action::incrementAndGet)).isInstanceOf(IllegalArgumentException.class);
        assertThat(action).hasValue(0);assertThat(keys).isEmpty();
    }

    @Test void reciprocalPartyOrderSerializesRealReentrantLocksWithoutConcurrentActions() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var firstEntered=new CountDownLatch(1);
        var secondStarted=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);
        var active=new AtomicInteger();var maximum=new AtomicInteger();var finished=new AtomicInteger();
        Supplier<Integer> action=()->{int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);
            try{return finished.incrementAndGet();}finally{active.decrementAndGet();}};
        try {
            var one=pool.submit(()->service.runRalliesDepartureLocked(List.of("rally-a","rally-z"),()->{
                active.incrementAndGet();maximum.accumulateAndGet(active.get(),Math::max);firstEntered.countDown();
                try {if(!releaseFirst.await(5,TimeUnit.SECONDS))throw new IllegalStateException("owned test release missing");
                    return finished.incrementAndGet();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                finally{active.decrementAndGet();}
            }));
            assertThat(firstEntered.await(5,TimeUnit.SECONDS)).isTrue();
            var two=pool.submit(()->{secondStarted.countDown();return service.runRalliesDepartureLocked(List.of("rally-z","rally-a"),action);});
            assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->two.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(1,2);
            assertThat(maximum).hasValue(1);
        } finally {releaseFirst.countDown();pool.shutdownNow();}
    }
}
