package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.QuestCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.quest.QuestProgress.QuestType;
import com.ironoath.core.reward.GoldClaimReceipt;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.social.SocialStore;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Squad;
import com.ironoath.web.store.memory.InMemoryQuestProgressStore;

/** Production service/domain with counted ports: skip only state events with no eligible consumer. */
class QuestStateRefreshConsumerTest {
    private static final String UID = "state-refresh-consumer";
    private static final long NOW = 1_791_979_200_000L;
    private final ConfigRegistry configs = mock(ConfigRegistry.class);
    private final QuestRulesAssembler assembler = mock(QuestRulesAssembler.class);
    private final QuestProgressStore store = spy(new InMemoryQuestProgressStore());
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final SocialStore social = mock(SocialStore.class);
    private final PlayerSave owner = mock(PlayerSave.class);
    private final RewardService rewards = mock(RewardService.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final TimeService time = mock(TimeService.class);
    private final InJvmPlayerLock locks = spy(new InJvmPlayerLock());
    private QuestAppService service;

    @BeforeEach void setup() {
        when(time.serverNow()).thenReturn(NOW);
        when(players.findByPlayerId(UID)).thenReturn(Optional.of(owner));
        when(owner.goldClaims()).thenReturn(Map.of());
        when(owner.resources()).thenReturn(Map.of("WOOD", new PlayerResourceState(42, 1000, 0, 0, NOW),
                "GRAIN", new PlayerResourceState(99, 1000, 0, 0, NOW)));
        when(owner.tech()).thenReturn(new PlayerTech(Map.of("tech_grain", 2), null, null, 0, 0));
        when(social.allianceOf(UID)).thenReturn(Optional.of(mock(Alliance.class)));
        when(social.squadOf(UID)).thenReturn(Optional.of(mock(Squad.class)));
        service = new QuestAppService(configs, assembler, store, time, rewards,
                mock(BattlePassService.class), idempotency, players, social, locks, mock(RewardNames.class));
    }

    private QuestRulesAssembler.QuestDef def(String id, GoalType goal, String target, String pre) {
        return def(id, QuestType.MAIN, goal, target, pre);
    }

    private QuestRulesAssembler.QuestDef def(String id, QuestType type, GoalType goal, String target, String pre) {
        return new QuestRulesAssembler.QuestDef(new QuestProgress.Def(id, type, goal, target, 100, pre),
                "状态任务", List.of(), List.of());
    }

    private QuestProgress.Entry entry(QuestRulesAssembler.QuestDef d, long current, boolean claimed) {
        var x = d.def();
        return new QuestProgress.Entry(x.questId(), x.type(), x.goalType(), x.goalTarget(), x.goalValue(),
                x.preQuestId(), current, claimed);
    }

    private void seed(List<QuestRulesAssembler.QuestDef> defs, List<QuestProgress.Entry> entries) {
        when(assembler.quests()).thenReturn(defs);
        store.save(UID, new QuestProgressStore.State(UID, entries, DayKey.of(NOW), WeekKey.of(NOW)));
        clearInvocations(store, social, players, locks);
    }

    @Test void lockedSocialSourcesAreNotQueriedAndAnIgnoredEventDoesNotCauseSave() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var alliance=def("alliance",GoalType.JOIN_ALLIANCE,null,"parent");
        var squad=def("squad",GoalType.JOIN_SQUAD,null,"parent");
        seed(List.of(parent,alliance,squad),List.of(entry(parent,0,false),entry(alliance,0,false),entry(squad,0,false)));
        assertThat(service.claimableCount(UID)).isZero();
        verify(social, never()).allianceOf(UID);
        verify(social, never()).squadOf(UID);
        verify(store, never()).save(anyString(),any());
        verify(players).findByPlayerId(UID); // Gold recovery still needs the owner read.
        var order=inOrder(locks);
        order.verify(locks).runLocked(eq(UID),eq(3000L),any());
        order.verify(locks).runLocked(eq(QuestProgressStore.lockKey(UID)),eq(3000L),any());
    }

    @Test void lockedOwnerStateDoesNotCauseNoOpSave() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var resource=def("resource",GoalType.REACH_RESOURCE,"WOOD","parent");
        var tech=def("tech",GoalType.RESEARCH_TECH,"tech_grain","parent");
        seed(List.of(parent,resource,tech),List.of(entry(parent,0,false),entry(resource,0,false),entry(tech,0,false)));
        var progress=service.loaded(UID,NOW);
        assertThat(progress.entry("resource").current()).isZero();
        assertThat(progress.entry("tech").current()).isZero();
        verify(store, never()).save(anyString(),any());
    }

    @Test void unlockedAndClaimedStateStillRefreshesDownward() {
        var alliance=def("alliance",GoalType.JOIN_ALLIANCE,null,null);
        seed(List.of(alliance),List.of(entry(alliance,1,true)));
        when(social.allianceOf(UID)).thenReturn(Optional.empty());
        var progress=service.loaded(UID,NOW);
        assertThat(progress.entry("alliance").current()).isZero();
        assertThat(progress.entry("alliance").claimed()).isTrue();
        verify(social).allianceOf(UID);
        verify(store).save(eq(UID),any());
    }

    @Test void aLockedSourceStillFeedsAnUnlockedWildcardConsumer() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var wildcard=def("wildcard",GoalType.REACH_RESOURCE,null,null);
        var source=def("source",GoalType.REACH_RESOURCE,"WOOD","parent");
        seed(List.of(parent,wildcard,source),List.of(entry(parent,0,false),entry(wildcard,0,false),entry(source,0,false)));
        var progress=service.loaded(UID,NOW);
        assertThat(progress.entry("wildcard").current()).isEqualTo(42);
        assertThat(progress.entry("source").current()).isZero();
        verify(store).save(eq(UID),any());
    }

    @Test void aDifferentUnlockedTargetCannotMakeAnIgnoredSourceDirty() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var wood=def("wood",GoalType.REACH_RESOURCE,"WOOD",null);
        var grain=def("grain",GoalType.REACH_RESOURCE,"GRAIN","parent");
        seed(List.of(parent,wood,grain),List.of(entry(parent,0,false),entry(wood,42,false),entry(grain,0,false)));
        var progress=service.loaded(UID,NOW);
        assertThat(progress.entry("wood").current()).isEqualTo(42);
        assertThat(progress.entry("grain").current()).isZero();
        verify(store,never()).save(anyString(),any());
    }

    @Test void claimingTheParentEnablesTheSourceOnTheNextRead() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var child=def("child",GoalType.JOIN_ALLIANCE,null,"parent");
        seed(List.of(parent,child),List.of(entry(parent,100,false),entry(child,0,false)));
        when(idempotency.tryAcquire(anyString(),anyLong(),anyLong())).thenReturn(true);
        when(configs.get(QuestCfg.class,"parent")).thenReturn(mock(QuestCfg.class));
        service.claim(UID,new QuestClaimReq("parent-request","parent",null));
        clearInvocations(social);
        assertThat(service.loaded(UID,NOW).entry("child").current()).isEqualTo(1);
        verify(social).allianceOf(UID);
    }

    @Test void dailyRolloverRelocksTheChildBeforeChoosingItsSource() {
        var parent=def("parent",QuestType.DAILY,GoalType.UPGRADE_BUILDING,null,null);
        var child=def("child",GoalType.JOIN_ALLIANCE,null,"parent");
        seed(List.of(parent,child),List.of(entry(parent,100,true),entry(child,0,false)));
        long tomorrow=NOW+86_400_000L;
        var progress=service.loaded(UID,tomorrow);
        assertThat(progress.entry("parent").claimed()).isFalse();
        assertThat(progress.unlocked("child")).isFalse();
        assertThat(progress.dayKey()).isEqualTo(DayKey.of(tomorrow));
        verify(social,never()).allianceOf(UID);
        verify(store).save(eq(UID),any()); // Rollover must still persist.
    }

    @Test void anActiveSourceFailureStillPropagatesWithoutSaving() {
        var child=def("child",GoalType.JOIN_ALLIANCE,null,null);
        seed(List.of(child),List.of(entry(child,0,false)));
        when(social.allianceOf(UID)).thenThrow(new IllegalStateException("active-source-failed"));
        assertThatThrownBy(()->service.loaded(UID,NOW)).isInstanceOf(IllegalStateException.class).hasMessage("active-source-failed");
        verify(store,never()).save(anyString(),any());
    }

    @Test void goldOverflowRecoveryIsNotSkippedWhenAllStateSourcesAreLocked() {
        var parent=def("parent",GoalType.UPGRADE_BUILDING,null,null);
        var child=def("child",GoalType.JOIN_ALLIANCE,null,"parent");
        seed(List.of(parent,child),List.of(entry(parent,0,false),entry(child,0,false)));
        when(owner.goldClaims()).thenReturn(Map.of("other",new GoldClaimReceipt("other","free_gold","other",10,5,NOW,Set.of(),null)));
        service.loaded(UID,NOW);
        verify(rewards).recoverGoldClaims(UID);
        verify(social,never()).allianceOf(UID);
    }
}
