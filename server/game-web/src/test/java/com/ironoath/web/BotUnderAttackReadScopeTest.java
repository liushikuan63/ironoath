package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerPvp;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotBehaviorStore;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.web.bot.BotSpawnService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.ExileAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.TargetSearchService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：Bot 每个 tick 采集一次「世界状态」时，<b>为"我是不是正在被打"读了几次行为档</b>。
 *
 * <p><b>缺陷形状</b>：{@code BotWorldAdapter} 每 tick 无条件读一次 {@code BotBehaviorStore}
 * 只为取 {@code reactedUpTo}（回应水位），而这一位只在「最近一发还在 1 小时通告窗口内」时
 * 才参与判断。绝大多数 Bot 当时并没有受击记录，于是这次整档读是纯白读 ——
 * 5000 Bot × 每轮的乘数，而结果完全正确，任何结果断言都抓不到它，只有读范围能。
 *
 * <p><b>两条边界都要钉</b>：窗口外不读（省掉的是白读），窗口内必须读且只读一次
 * （否则"省"会变成"省掉了本该做的判断"，那是另一种错）。
 */
class BotUnderAttackReadScopeTest {

    private static final long NOW = 1_800_000_000_000L;
    /** 1 小时通告窗口：窗口内用 {@code NOW - 1000}，窗口外用 2 小时前。 */
    private static final long FRESH_HIT = NOW - 1_000L;
    private static final long STALE_HIT = NOW - 2L * 60L * 60L * 1000L;
    private static final String BOT = "bot-under-attack-scope";

    private final BotRegistry bots = mock(BotRegistry.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final CityAppService cities = mock(CityAppService.class);
    private final ArmyAppService army = mock(ArmyAppService.class);
    private final HeroAppService heroes = mock(HeroAppService.class);
    private final ArmyRepository armies = mock(ArmyRepository.class);
    private final SocialStore social = mock(SocialStore.class);
    private final MarchRepository marches = mock(MarchRepository.class);
    private final BotSpawnService spawner = mock(BotSpawnService.class);
    private final RewardPorts.Wallet wallet = mock(RewardPorts.Wallet.class);
    private final ConfigRegistry configs = mock(ConfigRegistry.class);
    private final MarchAppService marches2 = mock(MarchAppService.class);
    private final ExileAppService exiles = mock(ExileAppService.class);
    private final WorldRepository world = mock(WorldRepository.class);
    private final SocialAppService social2 = mock(SocialAppService.class);
    private final TargetSearchService targetSearch = mock(TargetSearchService.class);
    private final QuestAppService quests = mock(QuestAppService.class);
    private final BotRulesAssembler assembler = mock(BotRulesAssembler.class);
    private final WorldAppService worldService = mock(WorldAppService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BotBehaviorStore behavior = mock(BotBehaviorStore.class);
    private final PlayerLock playerLock = mock(PlayerLock.class);
    private final PlayerSave save = mock(PlayerSave.class);
    private final PlayerPvp pvp = mock(PlayerPvp.class);

    private BotWorldAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BotWorldAdapter(bots, players, cities, army, heroes, armies, social, marches, spawner,
                wallet, configs, marches2, exiles, world, social2, targetSearch, quests, assembler,
                worldService, events, behavior, playerLock);

        // 玩家锁：shared scope 直接执行，本用例量的是"读了几次"，不是锁语义。
        when(playerLock.runWithSharedScope(anyString(), anyLong(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        when(players.findByPlayerId(BOT)).thenReturn(Optional.of(save));
        when(save.pvp()).thenReturn(pvp);
        when(save.power()).thenReturn(new PlayerPower(100L, 100L, 100L));
        when(cities.botCityTickFacts(anyString(), anyLong(), any()))
                .thenReturn(new CityAppService.CityTickFacts(false, null));
        when(army.observationSnapshot(BOT)).thenReturn(mock(ArmyAppService.ArmyObservationSnapshot.class));
        when(heroes.troopCapSnapshot(BOT)).thenReturn(mock(HeroAppService.TroopCapSnapshot.class));
        when(army.firstTrainable(anyString(), anyLong(), any(), any())).thenReturn(Optional.empty());
        when(social.allianceOf(BOT)).thenReturn(Optional.empty());
        when(social.squadOf(BOT)).thenReturn(Optional.empty());
        when(quests.claimableCount(BOT)).thenReturn(0);
        when(behavior.find(BOT)).thenReturn(BotBehaviorStore.State.EMPTY);
    }

    private BotDecisionTree.WorldState observeWith(Map<String, Long> attackerHits) {
        when(pvp.attackerHits()).thenReturn(attackerHits);
        return adapter.observe(BOT, NOW);
    }

    @Test
    void noAttackRecordDoesNotReadBehaviorStore() {
        BotDecisionTree.WorldState state = observeWith(Map.of());

        assertThat(state.underAttack()).as("没有受击记录就不该判成正在被打").isFalse();
        verify(behavior, never()).find(anyString());
    }

    @Test
    void staleHitOutsideNoticeWindowDoesNotReadBehaviorStore() {
        BotDecisionTree.WorldState state = observeWith(Map.of("attacker-1", STALE_HIT));

        assertThat(state.underAttack()).as("过了 1 小时通告窗口就不再是「正在被打」").isFalse();
        verify(behavior, never()).find(anyString());
    }

    @Test
    void freshUnansweredHitReadsBehaviorStoreExactlyOnce() {
        BotDecisionTree.WorldState state = observeWith(Map.of("attacker-1", FRESH_HIT));

        assertThat(state.underAttack()).as("窗口内受击且没回应过 ⇒ 正在被打").isTrue();
        verify(behavior, times(1)).find(BOT);
    }

    @Test
    void freshAnsweredHitReadsOnceAndClearsUnderAttack() {
        when(behavior.find(BOT)).thenReturn(new BotBehaviorStore.State(FRESH_HIT, null, null));

        BotDecisionTree.WorldState state = observeWith(Map.of("attacker-1", FRESH_HIT));

        assertThat(state.underAttack()).as("已经回应过这一发就不再重复反应").isFalse();
        verify(behavior, times(1)).find(BOT);
    }
}
