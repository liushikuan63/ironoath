package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.ironoath.web.mail.MailClaimPlan;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.store.memory.InMemoryMailStore;
import com.ironoath.web.store.mongo.MailDocument;
import com.ironoath.web.store.mongo.MongoMailStore;

/** Same accepted promise contract on memory and real, uniquely owned Mongo database. */
class MailAcceptedPlanStoreTest {
    static final long T = 1_700_000_000_000L, END = T + 86_400_000L;
    static TestMongo db;
    @BeforeAll static void open() { db = TestMongo.tryOpen(); assertThat(db).as("synthetic Mongo contract requires local Mongo").isNotNull(); }
    @AfterAll static void close() { if (db != null) db.close(); }
    @BeforeEach void cleanOwnedMails() { new MongoMailStore(db.template()).clear(); }
    private List<MailStore> stores() { return List.of(new InMemoryMailStore(), new MongoMailStore(db.template())); }
    private MailStore.MailRecord mail(String id) {
        return new MailStore.MailRecord(id, "owner", "SYSTEM", "隔离邮件", "正文",
                List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")), "fixture", T, END, null, null);
    }
    private MailClaimPlan plan(MailStore.MailRecord mail, long at) {
        return new MailClaimPlan(MailClaimPlan.keyOf(mail.mailId()), mail.mailId(), mail.rewards(), at, at + 2 * 86_400_000L);
    }
    @Test void acceptChecksOwnerExactAttachmentsAndExpiryWithoutHalfState() {
        for (var store : stores()) {
            var mail = mail("accept"); store.save(mail);
            assertThat(store.acceptClaim("other", mail.mailId(), T, plan(mail, T))).isFalse();
            var wrong = new MailClaimPlan(MailClaimPlan.keyOf(mail.mailId()), mail.mailId(),
                    List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 9999, "粮食")), T, END);
            assertThat(store.acceptClaim("owner", mail.mailId(), T, wrong)).isFalse();
            assertThat(store.acceptClaim("owner", mail.mailId(), END, plan(mail, END))).isFalse();
            var untouched = store.findById("owner", mail.mailId()).orElseThrow();
            assertThat(untouched.claimedAt()).isNull(); assertThat(untouched.acceptedPlan()).isNull();
            assertThat(store.acceptClaim("owner", mail.mailId(), T, plan(mail, T))).isTrue();
            assertThat(store.acceptClaim("owner", mail.mailId(), T, plan(mail, T))).isFalse();
        }
    }
    @Test void pendingSurvivesReadReleasePurgeAndNewMongoStoreThenCompletesByOriginalKey() {
        for (var store : stores()) {
            var mail = mail("pending"); store.save(mail); var plan = plan(mail, T);
            assertThat(store.acceptClaim("owner", mail.mailId(), T, plan)).isTrue();
            assertThat(store.markRead("owner", mail.mailId(), T + 1)).isTrue();
            assertThat(store.releaseClaim("owner", mail.mailId())).isFalse();
            assertThat(store.completeAccepted("other", mail.mailId(), plan.businessKey())).isFalse();
            assertThat(store.completeAccepted("owner", mail.mailId(), "wrong-key")).isFalse();
            assertThat(store.purgeExpired(END)).isZero();
            var reopened = store instanceof MongoMailStore ? new MongoMailStore(db.template()) : store;
            assertThat(reopened.pendingOf("other")).isEmpty();
            assertThat(reopened.pendingOf("owner")).singleElement().satisfies(m -> {
                assertThat(m.acceptedPlan()).isEqualTo(plan); assertThat(m.claimedAt()).isEqualTo(T); assertThat(m.readAt()).isEqualTo(T + 1);
            });
            assertThat(reopened.completeAccepted("owner", mail.mailId(), plan.businessKey())).isTrue();
            assertThat(reopened.completeAccepted("owner", mail.mailId(), plan.businessKey())).isTrue();
            assertThat(reopened.pendingOf("owner")).isEmpty();
            assertThat(reopened.findById("owner", mail.mailId()).orElseThrow().claimedAt()).isEqualTo(T);
            assertThat(reopened.purgeExpired(END)).isEqualTo(1);
        }
    }
    @Test void acceptVersusExpiryCleanupHasOnlyDeletedOrDurablyPendingOutcomes() throws Exception {
        for (var store : stores()) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 12; i++) {
                    var mail = mail("race-" + i); store.save(mail); var start = new CountDownLatch(1);
                    var accepted = workers.submit(() -> { start.await(); return store.acceptClaim("owner", mail.mailId(), T, plan(mail, T)); });
                    var purge = workers.submit(() -> { start.await(); return store.purgeExpired(END); });
                    start.countDown(); boolean won = accepted.get(10, TimeUnit.SECONDS); purge.get(10, TimeUnit.SECONDS);
                    if (won) assertThat(store.findById("owner", mail.mailId()).orElseThrow().acceptedPlan()).isEqualTo(plan(mail, T));
                    else assertThat(store.findById("owner", mail.mailId())).isEmpty();
                }
            } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        }
    }
    @Test void oldBsonWithoutAcceptedPlanReadsNullAndDoesNotInventPendingPromise() {
        var store = new MongoMailStore(db.template()); var mail = mail("legacy"); store.save(mail);
        db.template().updateFirst(Query.query(Criteria.where("_id").is(mail.mailId())),
                new Update().unset("mail.acceptedPlan").set(MailDocument.FIELD_CLAIMED_AT, T), MailDocument.COLLECTION);
        var reopened = new MongoMailStore(db.template());
        assertThat(reopened.findById("owner", mail.mailId()).orElseThrow().acceptedPlan()).isNull();
        assertThat(reopened.pendingOf("owner")).isEmpty();
        assertThat(reopened.acceptClaim("owner", mail.mailId(), T, plan(mail, T))).isFalse();
    }

    @Test void competingAcceptsHaveExactlyOneWinnerAndOneImmutablePlan() throws Exception {
        for (var store : stores()) {
            var mail = mail("competing"); store.save(mail); var plan = plan(mail, T);
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                var start = new CountDownLatch(1); var results = new java.util.ArrayList<Future<Boolean>>();
                for (int i = 0; i < 8; i++) results.add(workers.submit(() -> { start.await(); return store.acceptClaim("owner", mail.mailId(), T, plan); }));
                start.countDown(); int winners = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
                assertThat(winners).isEqualTo(1); assertThat(store.pendingOf("owner")).singleElement().extracting(MailStore.MailRecord::acceptedPlan).isEqualTo(plan);
            } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        }
    }
}
