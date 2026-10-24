package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.service.WorldTargets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrategicTargetCircleGuardTest {
    @Test void npcGarrisonCircleUsesActualConfiguredTroopPowerAndSharedB08Rules() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var marches=new InMemoryMarchStore(16);var social=mock(SocialStore.class);
        var defenders=new StrategicTargetDefender(configs,new InMemoryTargetOccupationStore(),marches,social,
                new InMemoryNeutralTargetGarrisonStore());
        var powers=new PowerService(configs,new HeroStatsService(configs),new EquipLedgers(mock(com.ironoath.core.bag.InventoryRepository.class),configs));
        var attackGuard=attackGuard(configs,powers);
        var circle=new StrategicTargetCircleGuard(configs,defenders,mock(PlayerRepository.class),powers,
                mock(PowerRefreshService.class),attackGuard);
        var target=WorldTargets.from(configs).targets().stream().filter(t->t.definition().id().equals("gate_north"))
                .findFirst().orElseThrow();
        var defender=defenders.read(target);
        var npcArmy=new ArmyState();defender.troops().forEach(npcArmy::add);
        long actualNpcMatchPower=powers.troopsPower(npcArmy);
        assertThat(actualNpcMatchPower).isPositive();
        assertThatThrownBy(()->circle.guardArrival(1,defender,1)).isInstanceOf(com.ironoath.common.BizException.class);
        assertThatCode(()->circle.guardArrival(actualNpcMatchPower,defender,1)).doesNotThrowAnyException();
    }

    @Test void playerGarrisonCircleUsesStoredB08TargetMatchPowerAndActualRallySize() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var playerId="garrison-owner";var troops=Map.of("unit_infantry_t4",10L);
        var march=new March("garrison-circle-march",playerId,Coord.of(10,10),Coord.of(256,216),100,200,
                troops,List.of(),100,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        march.attachToRally("garrison-circle-rally");march.arrive(200);
        march.holdStrategicVictory(Map.of(playerId,troops));
        var owner=new TargetOccupationStore.Snapshot("gate_north",com.ironoath.core.world.WorldGenerator.EntityType.GATE,
                256,216,"owner-nation","Owner Nation",200,"owned-capture",1,
                TargetOccupationStore.Garrison.stationed(march.id()));
        var occupationStore=new InMemoryTargetOccupationStore();assertThat(occupationStore.compareAndSet(owner,0)).isTrue();
        var marches=new InMemoryMarchStore(16);assertThat(marches.insertIfAbsent(march)).isTrue();
        var defenders=new StrategicTargetDefender(configs,occupationStore,marches,mock(SocialStore.class),
                new InMemoryNeutralTargetGarrisonStore());
        var save=mock(com.ironoath.core.player.PlayerSave.class);var targetPower=60_000L;
        when(save.power()).thenReturn(new PlayerPower(targetPower,targetPower,targetPower));
        var players=mock(PlayerRepository.class);when(players.findByPlayerId(playerId)).thenReturn(Optional.of(save));
        var powers=new PowerService(configs,new HeroStatsService(configs),new EquipLedgers(mock(com.ironoath.core.bag.InventoryRepository.class),configs));
        var circle=new StrategicTargetCircleGuard(configs,defenders,players,powers,mock(PowerRefreshService.class),attackGuard(configs,powers));
        var captured=new StrategicTargetDefender.Defender(owner,marches.versionOf(march.id()),march.snapshot(),
                march.units(),false,null);

        assertThatThrownBy(()->circle.guardArrival(1,captured,4)).isInstanceOf(com.ironoath.common.BizException.class);
        assertThatCode(()->circle.guardArrival(targetPower,captured,4)).doesNotThrowAnyException();
    }

    private static AttackGuardService attackGuard(ConfigRegistry configs,PowerService powers) {
        return new AttackGuardService(mock(PlayerRepository.class),mock(com.ironoath.core.world.WorldRepository.class),powers,
                mock(PowerRefreshService.class),mock(com.ironoath.web.season.SeasonAppService.class),mock(SocialStore.class),
                mock(com.ironoath.web.nation.NationStore.class),mock(com.ironoath.web.bot.BotAttackLimiter.class));
    }
}
