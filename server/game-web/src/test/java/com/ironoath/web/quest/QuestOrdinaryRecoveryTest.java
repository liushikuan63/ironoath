package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doReturn;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.WeekKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Real services/storage effects with test-only abrupt-stop boundaries. No qualification or real-device claim. */
@SpringBootTest @ActiveProfiles("test")
@Import(QuestOrdinaryRecoveryTest.IsolatedContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestOrdinaryRecoveryTest {
    private static final String MAIN = "quest_main_01";
    @TestConfiguration static class IsolatedContext {
        @org.springframework.context.annotation.Bean java.util.concurrent.atomic.AtomicLong questClock() {
            return new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);
        }
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        TimeService questTime(java.util.concurrent.atomic.AtomicLong clock) { return new TimeService(clock::get); }
    }
    static final class Interrupted extends Error { Interrupted() { super("synthetic process interruption"); } }
    @Autowired ConfigRegistry configs;
    @Autowired QuestRulesAssembler assembler;
    @SpyBean QuestProgressStore store;
    @SpyBean RewardService rewards;
    @Autowired TimeService time;
    @SpyBean BattlePassService battlePass;
    @SpyBean com.ironoath.web.battlepass.BattlePassStore pointStore;
    @SpyBean com.ironoath.core.hero.HeroRepository heroes;
    @SpyBean com.ironoath.core.bag.InventoryRepository inventories;
    @SpyBean com.ironoath.web.mail.MailStore mail;
    @Autowired QuestEvents events;
    @Autowired java.util.concurrent.atomic.AtomicLong clock;
    private String originalQuestJson;
    private String originalItemJson;
    @Autowired IdempotencyStore idempotency;
    @SpyBean PlayerRepository players;
    @Autowired SocialStore social;
    @Autowired PlayerLock locks;
    @Autowired RewardNames names;
    @Autowired PlayerInitService init;
    @Autowired QuestAppService quests;

    @BeforeEach void clear() {
        reset(store, rewards, players, heroes, inventories, pointStore, mail, battlePass);
        clock.set(1_700_000_000_000L);
        if (originalQuestJson == null) {
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (root != null && !java.nio.file.Files.exists(root.resolve("contract/config/quest.json"))) root = root.getParent();
            if (root == null) throw new AssertionError("No contract/config ancestor");
            try { originalQuestJson = java.nio.file.Files.readString(root.resolve("contract/config/quest.json")); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        if (originalItemJson == null) {
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (root != null && !java.nio.file.Files.exists(root.resolve("contract/config/item.json"))) root = root.getParent();
            if (root == null) throw new AssertionError("No item config ancestor");
            try { originalItemJson = java.nio.file.Files.readString(root.resolve("contract/config/item.json")); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        configs.reload("item", com.ironoath.config.cfg.ItemCfg.class, originalItemJson);
        configs.reload("quest", com.ironoath.config.cfg.QuestCfg.class, originalQuestJson);
        ((InMemoryPlayerStore) players).clear();
        store.clear();
    }
    private String id() { return UUID.randomUUID().toString(); }
    private QuestAppService rebuilt() {
        return new QuestAppService(configs, assembler, store, time, rewards, battlePass,
                idempotency, players, social, locks, names);
    }
    private String qualified() {
        String uid = init.init(new PlayerInitReq(id(), "dev-" + id(), "任务恢复夹具", time.serverNow(), "")).playerId();
        long now = time.serverNow();
        var progress = QuestProgress.open(uid, assembler.quests().stream().map(QuestRulesAssembler.QuestDef::def).toList(),
                DayKey.of(now), WeekKey.of(now));
        var entry = progress.entry(MAIN);
        progress.onEvent(GameEvent.progress(uid, entry.goalType(), entry.goalTarget(), entry.goalValue(), now));
        store.save(uid, new QuestProgressStore.State(uid, progress.entries(), progress.dayKey(), progress.weekKey()));
        var player = players.findByPlayerId(uid).orElseThrow();
        var grain = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(0, grain.cap(), grain.protectedAmount(), 0, now));
        players.save(player);
        assertThat(quests.list(uid).quests().stream().filter(q -> MAIN.equals(q.questId())).findFirst().orElseThrow().claimable()).isTrue();
        return uid;
    }
    private long grain(String uid) { return players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current(); }
    private void checkRecovered(String uid) {
        var rebuilt = rebuilt();
        rebuilt.list(uid);
        assertThat(grain(uid)).isEqualTo(8_000L);
        assertThat(battlePass.status(uid).points()).isEqualTo(25L);
        assertThat(heroes.findByPlayerId(uid).orElseThrow().owns("hero_sr_01")).isTrue();
        assertThat(fragments(uid)).isZero();
        rebuilt.list(uid);
        assertThat(grain(uid)).isEqualTo(8_000L);
        assertThat(battlePass.status(uid).points()).isEqualTo(25L);
        assertThatThrownBy(() -> rebuilt.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01")))
                .isInstanceOf(RuntimeException.class);
        assertThat(grain(uid)).isEqualTo(8_000L);
    }
    @Test void interruptedAfterClaimedProjectionRestartsAndDeliversFrozenClaim() {
        String uid = qualified();
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            var state = (QuestProgressStore.State) call.getArgument(1);
            Object result = call.callRealMethod();
            if (state.entries().stream().anyMatch(e -> MAIN.equals(e.questId()) && e.claimed()) && armed.compareAndSet(true, false))
                throw new Interrupted();
            return result;
        }).when(store).save(eq(uid), any(QuestProgressStore.State.class));
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    @Test void interruptedAfterAssetsBeforePointsRestartsWithoutLosingPointsOrRegranting() {
        String uid = qualified();
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(rewards).grantQuest(eq(uid), any(), any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    private long fragments(String uid) {
        return inventories.findByPlayerId(uid).map(i -> i.countOf("item_mat_hero_frag_sr")).orElse(0L);
    }
    private QuestClaimReq crashAtAccept(String uid, String questId) {
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            var state = (QuestProgressStore.State) call.getArgument(1);
            if (state.pendingClaims().stream().anyMatch(p -> p.questId().equals(questId)) && armed.compareAndSet(true, false))
                throw new Interrupted();
            return result;
        }).when(store).save(eq(uid), any());
        var request = new QuestClaimReq(id(), questId, MAIN.equals(questId) ? "hero_sr_01" : null);
        assertThatThrownBy(() -> quests.claim(uid, request)).isInstanceOf(Interrupted.class);
        return request;
    }
    private void seedComplete(String uid, String questId) {
        long now = time.serverNow();
        var progress = QuestProgress.open(uid, assembler.quests().stream().map(QuestRulesAssembler.QuestDef::def).toList(),
                DayKey.of(now), WeekKey.of(now));
        var entry = progress.entry(questId);
        progress.onEvent(entry.goalType().accumulates()
                ? GameEvent.progress(uid, entry.goalType(), entry.goalTarget(), entry.goalValue(), now)
                : GameEvent.state(uid, entry.goalType(), entry.goalTarget(), entry.goalValue(), now));
        store.save(uid, new QuestProgressStore.State(uid, progress.entries(), progress.dayKey(), progress.weekKey()));
    }

    @Test void resourceSaveLosesAcknowledgementAndRestartCreditsOnlyOnce() {
        String uid = qualified(); var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            var player = (com.ironoath.core.player.PlayerSave) call.getArgument(0);
            if (uid.equals(player.playerId()) && !player.resourceClaims().isEmpty() && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(players).save(any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    @Test void newHeroSaveLosesAcknowledgementAndNeverTurnsReplayIntoDuplicateFragments() {
        String uid = qualified(); var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            var roster = (com.ironoath.core.hero.HeroRoster) call.getArgument(1);
            if (!roster.rewardClaims().isEmpty() && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(heroes).save(eq(uid), any(), anyLong());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    @Test void duplicateHeroItemSaveLosesAcknowledgementAndFragmentsAreNotGrantedTwice() {
        String uid = qualified();
        heroes.insertIfAbsent(uid, new com.ironoath.core.hero.HeroRoster());
        var roster = heroes.findByPlayerId(uid).orElseThrow(); long version = heroes.versionOf(uid);
        roster.obtain("hero_sr_01"); heroes.save(uid, roster, version);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(inventories).save(eq(uid), any(), anyLong());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        rebuilt().list(uid); rebuilt().list(uid);
        assertThat(grain(uid)).isEqualTo(8000); assertThat(fragments(uid)).isEqualTo(15);
        assertThat(battlePass.status(uid).points()).isEqualTo(25);
    }
    @Test void pointSaveLosesAcknowledgementAndRestartDoesNotAddThePointsAgain() {
        String uid = qualified(); var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(pointStore).update(any(), eq(uid), any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    @Test void failedPendingDeletionReplaysAcceptedAssetsAndPointsSafely() {
        String uid = qualified(); var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            var state = (QuestProgressStore.State) call.getArgument(1);
            if (state.pendingClaims().isEmpty() && state.entries().stream().anyMatch(e -> MAIN.equals(e.questId()) && e.claimed())
                    && armed.compareAndSet(true, false)) throw new Interrupted();
            return call.callRealMethod();
        }).when(store).save(eq(uid), any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        checkRecovered(uid);
    }
    @Test void overflowMailSaveLosesAcknowledgementAndKeepsOneMailWithOriginalExpiry() {
        String uid = qualified();
        var player = players.findByPlayerId(uid).orElseThrow(); var grain = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(grain.cap(), grain.cap(), grain.protectedAmount(), 0, clock.get())); players.save(player);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            com.ironoath.web.mail.MailStore.MailRecord record = call.getArgument(0);
            if (uid.equals(record.playerId()) && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(mail).save(any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        var original = mail.listOf(uid, clock.get()).get(0); clock.addAndGet(86_400_000L);
        rebuilt().list(uid); rebuilt().list(uid);
        assertThat(mail.listOf(uid, clock.get())).singleElement().satisfies(m -> {
            assertThat(m.mailId()).isEqualTo(original.mailId()); assertThat(m.expireAt()).isEqualTo(original.expireAt());
            assertThat(m.rewards()).singleElement().satisfies(r -> assertThat(r.count()).isEqualTo(8000));
        });
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void eventsAndGuideProjectionPreservePendingWithoutWritingAssets() {
        String uid = qualified(); crashAtAccept(uid, MAIN);
        long version = players.findByPlayerId(uid).orElseThrow().version();
        events.progressOnce(uid, com.ironoath.core.quest.GoalType.TRAIN_UNIT, "unit_infantry_t1", 1, clock.get(), "fixture-train-1");
        assertThat(rebuilt().entryOf(uid, MAIN)).isPresent();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).hasSize(1);
        assertThat(grain(uid)).isZero(); assertThat(players.findByPlayerId(uid).orElseThrow().version()).isEqualTo(version);
        checkRecovered(uid);
    }
    @Test void sameRequestAndDifferentHeroCannotReplaceAnAcceptedChoice() {
        String uid = qualified(); var request = crashAtAccept(uid, MAIN);
        assertThatThrownBy(() -> rebuilt().claim(uid, request)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rebuilt().claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_02"))).isInstanceOf(RuntimeException.class);
        checkRecovered(uid);
        assertThat(heroes.findByPlayerId(uid).orElseThrow().owns("hero_sr_02")).isFalse();
    }
    @Test void configRemovalDoesNotEraseAcceptedRewardOrPoints() throws Exception {
        String uid = qualified(); crashAtAccept(uid, MAIN);
        var table = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(originalQuestJson);
        var rows = table.withArray("rows");
        for (int i = rows.size() - 1; i >= 0; i--) {
            var row = (com.fasterxml.jackson.databind.node.ObjectNode) rows.get(i);
            if (MAIN.equals(row.path("id").asText())) rows.remove(i);
            else if (MAIN.equals(row.path("preQuest").asText())) row.putNull("preQuest");
        }
        configs.reload("quest", com.ironoath.config.cfg.QuestCfg.class, table.toString());
        rebuilt().list(uid);
        assertThat(grain(uid)).isEqualTo(8000); assertThat(battlePass.status(uid).points()).isEqualTo(25);
        assertThat(heroes.findByPlayerId(uid).orElseThrow().owns("hero_sr_01")).isTrue();
    }
    @Test void dailyRolloverDeliversOldPromiseAndRetainsNewDaysQualification() {
        String uid = qualified(); String daily = "quest_daily_01";
        seedComplete(uid, daily); long gold = players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();
        crashAtAccept(uid, daily); clock.addAndGet(86_400_000L);
        rebuilt().list(uid);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GOLD").current()).isEqualTo(gold + 30);
        assertThat(rebuilt().list(uid).quests().stream().filter(q -> daily.equals(q.questId())).findFirst().orElseThrow().claimed()).isFalse();
        seedComplete(uid, daily); rebuilt().claim(uid, new QuestClaimReq(id(), daily, null));
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GOLD").current()).isEqualTo(gold + 60);
        assertThat(battlePass.status(uid).points()).isEqualTo(40);
    }
    @Test void oldClaimedProjectionWithoutPlanIsNotGuessedOrBackfilled() {
        String uid = qualified(); var state = store.load(uid).orElseThrow();
        var progress = QuestProgress.restore(uid, state.entries(), state.dayKey(), state.weekKey()); progress.claim(MAIN);
        store.save(uid, new QuestProgressStore.State(uid, progress.entries(), progress.dayKey(), progress.weekKey()));
        rebuilt().list(uid);
        assertThat(grain(uid)).isZero(); assertThat(battlePass.status(uid).points()).isZero();
        assertThat(heroes.findByPlayerId(uid)).isEmpty();
    }

    @Test void genericRecoveryCannotStealQuestOverflow() {
        String uid = fullOverflowDuplicate();
        var armed = new AtomicBoolean(true);
        doAnswer(call -> { Object result = call.callRealMethod(); if (armed.compareAndSet(true, false)) throw new Interrupted(); return result; })
                .when(inventories).save(eq(uid), any(), anyLong());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        // Activity's production loader calls this very generic recovery entry before loading its ledger.
        rewards.recoverItemClaims(uid);
        rebuilt().list(uid); rebuilt().list(uid);
        var delivered = mail.listOf(uid, clock.get());
        long fragments = delivered.stream().flatMap(m -> m.rewards().stream())
                .filter(r -> "item_mat_hero_frag_sr".equals(r.id())).mapToLong(r -> r.count()).sum();
        assertThat(fragments).isEqualTo(15);
        assertThat(delivered).hasSize(1);
    }
    private String fullOverflowDuplicate() {
        String uid = qualified();
        var player = players.findByPlayerId(uid).orElseThrow(); var grain = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(grain.cap(), grain.cap(), grain.protectedAmount(), 0, clock.get())); players.save(player);
        heroes.insertIfAbsent(uid, new com.ironoath.core.hero.HeroRoster());
        var roster = heroes.findByPlayerId(uid).orElseThrow(); long version = heroes.versionOf(uid);
        roster.obtain("hero_sr_01"); heroes.save(uid, roster, version);
        var full = new com.ironoath.core.bag.Inventory(1); full.add("item_speedup_build_1h", 1, 99);
        inventories.insertIfAbsent(uid, full);
        return uid;
    }

    @Test void partialOverflowAcknowledgementKeepsTheSameCombinedMail() {
        String uid = fullOverflowDuplicate(); var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            var player = (com.ironoath.core.player.PlayerSave) call.getArgument(0);
            if (uid.equals(player.playerId()) && player.resourceClaims().values().stream().anyMatch(r -> r.overflowMailId() != null)
                    && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(players).save(any());
        assertThatThrownBy(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, "hero_sr_01"))).isInstanceOf(Interrupted.class);
        var first = mail.listOf(uid, clock.get()).get(0);
        rewards.recoverItemClaims(uid); rebuilt().list(uid); rebuilt().list(uid);
        assertThat(mail.listOf(uid, clock.get())).singleElement().satisfies(m -> {
            assertThat(m.mailId()).isEqualTo(first.mailId());
            assertThat(m.rewards().stream().filter(r -> r.id().equals("GRAIN")).mapToLong(r -> r.count()).sum()).isEqualTo(8000);
            assertThat(m.rewards().stream().filter(r -> r.id().equals("item_mat_hero_frag_sr")).mapToLong(r -> r.count()).sum()).isEqualTo(15);
        });
        assertThat(battlePass.status(uid).points()).isEqualTo(25);
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void weeklyRolloverUsesSeparateClaimKeysAndPreservesOldPromise() {
        String uid = qualified(); String weekly = "quest_weekly_01"; seedComplete(uid, weekly);
        long gold = players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();
        crashAtAccept(uid, weekly); clock.addAndGet(7 * 86_400_000L); rebuilt().list(uid);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GOLD").current()).isEqualTo(gold + 200);
        seedComplete(uid, weekly); rebuilt().claim(uid, new QuestClaimReq(id(), weekly, null));
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("GOLD").current()).isEqualTo(gold + 400);
        assertThat(inventories.findByPlayerId(uid).orElseThrow().countOf("item_mat_hero_frag_n")).isEqualTo(2);
        assertThat(battlePass.status(uid).points()).isEqualTo(120);
    }
    @Test void lateSeasonRecoveryKeepsOldPointsAndSharesTierMailWithActivityRecovery() {
        String uid = qualified(); String oldSeason = battlePass.activitySeasonId();
        var tiers = battlePass.activityTierSnapshot(); long initial = Math.max(0, tiers.get(0).requiredPoints() - 25);
        pointStore.update(oldSeason, uid, p -> p.withPoints(initial)); crashAtAccept(uid, MAIN);
        doReturn("season_fixture_next").when(battlePass).activitySeasonId();
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            com.ironoath.web.mail.MailStore.MailRecord record = call.getArgument(0);
            if (uid.equals(record.playerId()) && "BATTLE_PASS".equals(record.kind()) && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(mail).save(any());
        assertThatThrownBy(() -> rebuilt().list(uid)).isInstanceOf(Interrupted.class);
        var first = mail.listOf(uid, clock.get()).stream().filter(m -> "BATTLE_PASS".equals(m.kind())).findFirst().orElseThrow();
        battlePass.recoverActivityTierRewards(uid, oldSeason, tiers);
        rebuilt().list(uid); rebuilt().list(uid);
        assertThat(pointStore.load(oldSeason, uid).points()).isEqualTo(initial + 25);
        assertThat(pointStore.load("season_fixture_next", uid).points()).isZero();
        long expected = tiers.stream().filter(t -> t.requiredPoints() <= initial + 25).count();
        var mails = mail.listOf(uid, clock.get()).stream().filter(m -> "BATTLE_PASS".equals(m.kind())).toList();
        assertThat(mails).hasSize((int) expected);
        assertThat(mails).anySatisfy(m -> { assertThat(m.mailId()).isEqualTo(first.mailId()); assertThat(m.expireAt()).isEqualTo(first.expireAt()); });
    }
    @Test void competingRequestsFromTwoServicesChooseExactlyOneHeroAndReward() throws Exception {
        String uid = qualified(); var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String hero : java.util.List.of("hero_sr_01", "hero_sr_02")) futures.add(pool.submit(() -> {
                if (!start.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture start timeout");
                try { rebuilt().claim(uid, new QuestClaimReq(id(), MAIN, hero)); return true; }
                catch (com.ironoath.common.BizException alreadyClaimed) { return false; }
            }));
            start.countDown(); int successful = 0;
            for (var result : futures) if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
            assertThat(grain(uid)).isEqualTo(8000); assertThat(battlePass.status(uid).points()).isEqualTo(25);
            var roster = heroes.findByPlayerId(uid).orElseThrow();
            assertThat((roster.owns("hero_sr_01") ? 1 : 0) + (roster.owns("hero_sr_02") ? 1 : 0)).isEqualTo(1);
            assertThat(fragments(uid)).isZero();
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void removedOldSeasonItemNameDoesNotBlockFrozenTierMailAndQuestCompletion() throws Exception {
        String uid = qualified(); String oldSeason = battlePass.activitySeasonId();
        var tiers = battlePass.activityTierSnapshot();
        var tier = tiers.stream().filter(t -> t.freeRewardType() == com.ironoath.config.cfg.BattlePassCfg.FreeRewardType.ITEM)
                .findFirst().orElseThrow();
        long initial = Math.max(0, tier.requiredPoints() - 25);
        pointStore.update(oldSeason, uid, p -> p.withPoints(initial)); crashAtAccept(uid, MAIN);
        doReturn("season_fixture_next").when(battlePass).activitySeasonId();
        var table = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(originalItemJson);
        var rows = table.withArray("rows");
        for (int i = rows.size() - 1; i >= 0; i--) if (tier.freeRewardId().equals(rows.get(i).path("id").asText())) rows.remove(i);
        configs.reload("item", com.ironoath.config.cfg.ItemCfg.class, table.toString());
        rebuilt().list(uid); rebuilt().list(uid);
        assertThat(pointStore.load(oldSeason, uid).points()).isEqualTo(initial + 25);
        assertThat(pointStore.load("season_fixture_next", uid).points()).isZero();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
        var delivered = mail.listOf(uid, clock.get()).stream().flatMap(m -> m.rewards().stream())
                .filter(r -> tier.freeRewardId().equals(r.id())).toList();
        assertThat(delivered).singleElement().satisfies(r -> {
            assertThat(r.count()).isEqualTo(tier.freeRewardCount());
            assertThat(r.name()).isEqualTo("未知道具").doesNotContain(tier.freeRewardId());
        });
    }

    @Test void zeroRewardTaskDoesNotDependOnAssetDelivery() throws Exception {
        var table = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(originalQuestJson);
        for (var node : table.withArray("rows")) if (MAIN.equals(node.path("id").asText())) {
            var row = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            row.put("rewardGrain", 0); row.put("battlePassPoints", 0); row.putNull("rewardHeroChoices");
        }
        configs.reload("quest", com.ironoath.config.cfg.QuestCfg.class, table.toString());
        String uid = qualified();
        doAnswer(call -> { throw new UnsupportedOperationException("synthetic reward backend unavailable"); })
                .when(rewards).grantQuest(eq(uid), any(), any());
        org.assertj.core.api.Assertions.assertThatCode(() -> quests.claim(uid, new QuestClaimReq(id(), MAIN, null)))
                .doesNotThrowAnyException();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
        assertThat(rebuilt().entryOf(uid, MAIN).orElseThrow().claimed()).isTrue();
        assertThat(grain(uid)).isZero(); assertThat(battlePass.status(uid).points()).isZero();
    }

}
