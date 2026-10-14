package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.core.player.*;
import com.ironoath.web.bot.BotWorldAdapter;

@SpringBootTest
@ActiveProfiles("test")
class BotMatchPowerReadScopeTest {
    @Autowired BotWorldAdapter adapter;
    @SpyBean PlayerRepository players;

    @Test void schedulePowerReadsCurrentMatchPowerWithoutCopyingFullPlayerSave() {
        String id="P-match-scope-"+UUID.randomUUID();
        PlayerSave initial=PlayerSave.createNew(id,"device-"+id,"读域测试",1,1_760_000_000_000L,1,
                Map.of("WOOD",new PlayerResourceState(5000,10000,0,100,1_760_000_000_000L)),
                new PlayerPower(111,222,333),null);
        assertThat(players.insertIfAbsent(initial)).isTrue();
        assertThat(adapter.matchPowerOf(id)).isEqualTo(222);
        PlayerSave changed=players.findByPlayerId(id).orElseThrow();
        changed.setPower(new PlayerPower(444,555,666));players.save(changed);
        clearInvocations(players);
        assertThat(adapter.matchPowerOf(id)).isEqualTo(555);
        verify(players,never()).findByPlayerId(any());
        verify(players,never()).findByPlayerIds(any());
        verify(players,never()).findBriefs(any());
    }
}
