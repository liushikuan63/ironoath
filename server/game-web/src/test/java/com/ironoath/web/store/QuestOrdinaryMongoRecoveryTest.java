package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.WeekKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.reward.RewardGrantor;
import com.ironoath.web.battlepass.BattlePassRules;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.quest.QuestRulesAssembler;
import com.ironoath.web.quest.QuestProgressStore;
import com.ironoath.web.reward.HeroFragmentExtras;
import com.ironoath.web.reward.PaidPrivilegeGrants;
import com.ironoath.web.reward.PlayerBag;
import com.ironoath.web.reward.PlayerWallet;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.reward.StoreMailbox;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.mongo.HeroDocument;
import com.ironoath.web.store.mongo.MongoBattlePassStore;
import com.ironoath.web.store.mongo.MongoHeroStore;
import com.ironoath.web.store.mongo.MongoInventoryStore;
import com.ironoath.web.store.mongo.MongoMailStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.MongoQuestProgressStore;
import com.ironoath.web.store.mongo.QuestProgressDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Each reconstruction creates fresh Mongo stores and real asset ports; only the unused paid-privilege port is mocked. */
@SpringBootTest @ActiveProfiles("test")
@Import(QuestOrdinaryMongoRecoveryTest.Isolation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestOrdinaryMongoRecoveryTest {
    @TestConfiguration static class Isolation {}
    private static TestMongo db;
    @BeforeAll static void connect() { db = TestMongo.tryOpen(); assertThat(db).as("owned random Mongo fixture required").isNotNull(); }
    @AfterAll static void close() { if (db != null) db.close(); }
    static final class Interrupted extends Error { Interrupted() { super("synthetic process interruption after persistence"); } }
    private static final String MAIN = "quest_main_01";
    @Autowired ConfigRegistry configs;
    @Autowired QuestRulesAssembler assembler;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository seedPlayers;
    @Autowired TimeService systemTime;
    @Autowired BattlePassRules rules;
    @Autowired RewardNames names;
    @Autowired PlayerLock locks;
    @Autowired SocialStore social;
    @Autowired IdempotencyStore idempotency;

    record Backend(MongoPlayerStore players, MongoHeroStore heroes, MongoInventoryStore inventory,
            MongoQuestProgressStore quests, MongoBattlePassStore points, MongoMailStore mail,
            QuestAppService service, BattlePassService pass) {}
    private Backend fresh(AtomicLong now) {
        var players = spy(new MongoPlayerStore(db.template()));
        var heroes = spy(new MongoHeroStore(db.template()));
        var inventory = spy(new MongoInventoryStore(db.template(), configs));
        var quests = spy(new MongoQuestProgressStore(db.template()));
        var points = new MongoBattlePassStore(db.template());
        var mail = new MongoMailStore(db.template());
        var time = new TimeService(now::get);
        var bag = new PlayerBag(inventory, configs);
        var mailbox = new StoreMailbox(mail, names, time, configs);
        var extras = new HeroFragmentExtras(bag, configs, heroes, mock(PaidPrivilegeGrants.class));
        var reward = new RewardGrantor(new PlayerWallet(players), bag, mailbox,
                (uid, items, ctx, cause) -> { throw new AssertionError("ordinary replay must not enter generic compensation", cause); }, extras, now::get);
        var pass = new BattlePassService(points, rules, reward, names, locks, time, configs, mailbox);
        return new Backend(players, heroes, inventory, quests, points, mail,
                new QuestAppService(configs, assembler, quests, time, reward, pass, idempotency, players, social, locks, names), pass);
    }
    private String seeded(Backend b, AtomicLong now) {
        String uid = init.init(new PlayerInitReq(id(), "dev-" + id(), "随机库任务恢复夹具", systemTime.serverNow(), "")).playerId();
        var player = seedPlayers.findByPlayerId(uid).orElseThrow();
        var grain = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(0, grain.cap(), grain.protectedAmount(), 0, now.get()));
        assertThat(b.players.insertIfAbsent(player)).isTrue();
        var progress = QuestProgress.open(uid, assembler.quests().stream().map(QuestRulesAssembler.QuestDef::def).toList(), DayKey.of(now.get()), WeekKey.of(now.get()));
        var main = progress.entry(MAIN);
        progress.onEvent(GameEvent.progress(uid, main.goalType(), main.goalTarget(), main.goalValue(), now.get()));
        b.quests.save(uid, new QuestProgressStore.State(uid, progress.entries(), progress.dayKey(), progress.weekKey()));
        return uid;
    }
    private String id() { return UUID.randomUUID().toString(); }
    private QuestClaimReq request() { return new QuestClaimReq(id(), MAIN, "hero_sr_01"); }
    private void assertRecovered(String uid, AtomicLong now) { assertRecovered(uid, now, 0); }
    private void assertRecovered(String uid, AtomicLong now, long expectedFragments) {
        var again = fresh(now); again.service.list(uid);
        var third = fresh(now); third.service.list(uid);
        assertThat(third.players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);
        assertThat(third.players.findByPlayerId(uid).orElseThrow().resourceClaims()).hasSize(1);
        var roster = third.heroes.findByPlayerId(uid).orElseThrow();
        assertThat(roster.owns("hero_sr_01")).isTrue(); assertThat(roster.rewardClaims()).hasSize(1);
        assertThat(third.inventory.findByPlayerId(uid).map(i -> i.countOf("item_mat_hero_frag_sr")).orElse(0L)).isEqualTo(expectedFragments);
        assertThat(third.pass.status(uid).points()).isEqualTo(25);
        assertThat(third.quests.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void acceptedPlanSurvivesMongoStoresAndServiceReconstruction() {
        var now = new AtomicLong(systemTime.serverNow()); var before = fresh(now); String uid = seeded(before, now);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            QuestProgressStore.State state = call.getArgument(1);
            if (!state.pendingClaims().isEmpty() && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(before.quests).save(eq(uid), any());
        assertThatThrownBy(() -> before.service.claim(uid, request())).isInstanceOf(Interrupted.class);
        assertThat(new MongoQuestProgressStore(db.template()).load(uid).orElseThrow().pendingClaims()).hasSize(1);
        assertRecovered(uid, now);
    }
    @Test void persistedResourceReceiptPreventsCreditAfterFreshStoreReconstruction() {
        var now = new AtomicLong(systemTime.serverNow()); var before = fresh(now); String uid = seeded(before, now);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            var player = (com.ironoath.core.player.PlayerSave) call.getArgument(0);
            if (!player.resourceClaims().isEmpty() && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(before.players).save(any());
        assertThatThrownBy(() -> before.service.claim(uid, request())).isInstanceOf(Interrupted.class);
        assertRecovered(uid, now);
    }
    @Test void persistedHeroReceiptPreventsReplayBecomingDuplicateConversion() {
        var now = new AtomicLong(systemTime.serverNow()); var before = fresh(now); String uid = seeded(before, now);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(before.heroes).save(eq(uid), any(), anyLong());
        assertThatThrownBy(() -> before.service.claim(uid, request())).isInstanceOf(Interrupted.class);
        assertRecovered(uid, now);
    }
    @Test void oldMongoDocumentsWithoutNewFieldsRemainReadableWithoutBackfill() {
        var now = new AtomicLong(systemTime.serverNow()); var before = fresh(now); String uid = seeded(before, now);
        before.service.claim(uid, request());
        db.template().getCollection("player").updateOne(new Document("_id", uid), new Document("$unset", new Document("resourceClaims", "")));
        db.template().getCollection(HeroDocument.COLLECTION).updateOne(new Document("_id", uid), new Document("$unset", new Document("rewardClaims", "")));
        db.template().getCollection(QuestProgressDocument.COLLECTION).updateOne(new Document("_id", uid), new Document("$unset", new Document("pendingClaims", "")));
        var legacy = fresh(now); legacy.service.list(uid);
        assertThat(legacy.players.findByPlayerId(uid).orElseThrow().resourceClaims()).isEmpty();
        assertThat(legacy.players.findByPlayerId(uid).orElseThrow().resource("GRAIN").current()).isEqualTo(8000);
        assertThat(legacy.heroes.findByPlayerId(uid).orElseThrow().rewardClaims()).isEmpty();
        assertThat(legacy.quests.load(uid).orElseThrow().pendingClaims()).isEmpty();
        assertThat(legacy.pass.status(uid).points()).isEqualTo(25);
    }
    @Test void persistedQuestItemReceiptUsesCurrentCodecAndPreventsDuplicateFragments() {
        var now = new AtomicLong(systemTime.serverNow()); var before = fresh(now); String uid = seeded(before, now);
        before.heroes.insertIfAbsent(uid, new com.ironoath.core.hero.HeroRoster());
        var roster = before.heroes.findByPlayerId(uid).orElseThrow(); long version = before.heroes.versionOf(uid);
        roster.obtain("hero_sr_01"); before.heroes.save(uid, roster, version);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(before.inventory).save(eq(uid), any(), anyLong());
        assertThatThrownBy(() -> before.service.claim(uid, request())).isInstanceOf(Interrupted.class);
        assertRecovered(uid, now, 15);
        assertThat(new MongoInventoryStore(db.template(), configs).findByPlayerId(uid).orElseThrow().itemClaims().values())
                .singleElement().satisfies(r -> assertThat(r.source()).isEqualTo("quest_item"));
    }

}
