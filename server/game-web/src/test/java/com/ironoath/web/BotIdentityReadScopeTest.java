package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.*;
import com.ironoath.web.store.memory.InMemoryBotProfileStore;

class BotIdentityReadScopeTest {
    @Test
    void membershipDoesNotEnumerateAndSortEveryCompleteProfile() {
        var store=spy(new InMemoryBotProfileStore());
        var profile=new BotProfile("bot-a","bot_jielue",new BotProfile.AiProfile(8000,4000,2000,6000),
                new BotProfile.Persona(42,7,99,List.of(0,12,23),3,27,1000),9000);
        store.put(profile);var registry=new BotRegistry(mock(BotRulesAssembler.class),store);
        assertThat(registry.humansIn(Arrays.asList("human-b",null,"bot-a","human-a","human-b")))
                .containsExactly("human-b","human-a","human-b");
        verify(store,never()).all();
    }
}
