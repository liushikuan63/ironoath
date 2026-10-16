package com.ironoath.web.bot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;

/** Real initialized Bot and production observation; track actual lexical lock boundaries. */
@SpringBootTest @ActiveProfiles("test")
class BotObservationPlayerScopeTest {
    @Autowired BotWorldAdapter adapter;
    @Autowired BotRegistry bots;
    @Autowired BotRuntimeService runtime;
    @Autowired BotRulesAssembler rules;
    @Autowired PlayerInitService init;
    @SpyBean PlayerRepository players;
    @SpyBean PlayerLock playerLock;
    @SpyBean TimeService time;
    String uid;long now;

    @BeforeEach void fixture(){
        now=System.currentTimeMillis();doReturn(now).when(time).serverNow();
        runtime.reset();bots.clear();adapter.resetCounters();
        String own=UUID.randomUUID().toString();
        uid=init.init(new PlayerInitReq(own,"scope-"+own,"观察范围验收",now,"")).playerId();
        bots.register(rules.archetypes().get(0).spawnProfile(uid,1,Rng.of(431)));
        clearInvocations(players,playerLock);
    }

    @Test void oneOuterPlayerScopeCoversTheFirstOwnerReadAndAllExistingObservationReads(){
        Deque<String> held=new ArrayDeque<>();List<Boolean> ownerReadWithinScope=new ArrayList<>();
        AtomicInteger outerActorEntries=new AtomicInteger();
        doAnswer(invocation->{
            String key=invocation.getArgument(0);
            if(held.isEmpty()&&uid.equals(key))outerActorEntries.incrementAndGet();
            held.push(key);try{return invocation.callRealMethod();}finally{held.pop();}
        }).when(playerLock).runLocked(anyString(),anyLong(),any());
        doAnswer(invocation->{ownerReadWithinScope.add(held.contains(uid));return invocation.callRealMethod();})
                .when(players).findByPlayerId(uid);

        assertThat(adapter.observe(uid,now)).isNotNull();
        assertThat(ownerReadWithinScope).as("must actually observe this initialized Bot's stored player").isNotEmpty();
        assertThat(ownerReadWithinScope).as("first owner read and subsequent settlement/quest reads share the outer player scope")
                .allMatch(Boolean::booleanValue);
        assertThat(outerActorEntries).as("existing nested service calls stay inside one lexical observation scope").hasValue(1);
        assertThat(held).isEmpty();
    }

    @Test void unavailableOuterPlayerLockPreventsEvenTheFirstOwnerRead(){
        var unavailable=new PlayerLock.LockTimeoutException(uid,3000);
        doThrow(unavailable).when(playerLock).runLocked(eq(uid),anyLong(),any());
        assertThatThrownBy(()->adapter.observe(uid,now)).isSameAs(unavailable);
        verify(players,never()).findByPlayerId(uid);
    }
}
