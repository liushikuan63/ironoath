package com.ironoath.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.MailClaimAllReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Nonpayment ordinary mail: actual claimed/resource persistence, abrupt cut and fresh service object. */
@SpringBootTest @ActiveProfiles("test")
@Import(MailResourceRecoveryTest.Isolation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailResourceRecoveryTest {
    @TestConfiguration static class Isolation {
        @Bean AtomicLong mailRecoveryClock() { return new AtomicLong(1_700_000_000_000L); }
        @Bean @Primary TimeService mailRecoveryTime(AtomicLong clock) { return new TimeService(clock::get); }
    }
    static final class Interrupted extends Error { Interrupted() { super("synthetic process cut after persistence"); } }
    @SpyBean MailStore store;
    @SpyBean PlayerRepository players;
    @Autowired PlayerInitService init;
    @Autowired MailAppService service;
    @SpyBean ConfigRegistry configs;
    @Autowired TimeService time;
    @Autowired AtomicLong mailRecoveryClock;
    @Autowired RewardService rewards;
    @Autowired IdempotencyStore idempotency;
    @Autowired PlayerLock locks;
    @Autowired RewardNames names;
    @BeforeEach void resetFaults() { reset(store, players, configs); mailRecoveryClock.set(1_700_000_000_000L); }
    private String id() { return UUID.randomUUID().toString(); }
    private MailAppService rebuilt() { return new MailAppService(store, time, configs, rewards, idempotency, locks, names); }
    private String player() {
        String uid = init.init(new PlayerInitReq(id(), "dev-" + id(), "普通邮件恢复夹具", time.serverNow(), "")).playerId();
        var player = players.findByPlayerId(uid).orElseThrow();
        for (String resource : List.of("WOOD", "STONE", "IRON", "GRAIN", "STAMINA")) {
            var state = player.resource(resource);
            player.putResource(resource, new PlayerResourceState(0, state.cap(), state.protectedAmount(), 0, time.serverNow()));
        }
        players.save(player);
        return uid;
    }
    private String mail(String uid, List<MailStore.RewardLine> lines) {
        String mailId = "mail_fixture_" + id(); long now = time.serverNow();
        store.save(new MailStore.MailRecord(mailId, uid, "SYSTEM", "资源附件", "仅供隔离恢复测试", lines,
                "fixture:ordinary-resource", now, Math.addExact(now, configs.longParam("MAIL_RETENTION_DAYS") * 86_400_000L), null, null));
        return mailId;
    }
    private long amount(String uid, String resource) { return players.findByPlayerId(uid).orElseThrow().resource(resource).current(); }

    @Test void afterClaimMarkerProcessCutDoesNotLoseOrdinaryResources() {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (Boolean.TRUE.equals(result) && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(store).acceptClaim(eq(uid), eq(mailId), anyLong(), any(MailClaimPlan.class));
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
    }
    @Test void cutAfterFirstResourceSaveRestoresRemainderWithoutDuplicatingFirstAsset() {
        String uid = player(); mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食"),
                new MailStore.RewardLine("RESOURCE", "IRON", 1000, "铁矿")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod(); PlayerSave player = call.getArgument(0);
            if (uid.equals(player.playerId()) && player.resource("GRAIN").current() == 8000 && player.resource("IRON").current() == 0
                    && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(players).save(any());
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        assertThat(amount(uid, "IRON")).isEqualTo(1000);
        rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        assertThat(amount(uid, "IRON")).isEqualTo(1000);
    }

    @Test void acceptedExpiredPlanSurvivesGlobalPurgeAndReadUpdatesThenRecoversOnList() {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var source = store.findById(uid, mailId).orElseThrow();
        var plan = new MailResourceRecovery(store, configs, rewards).freeze(source, time.serverNow());
        assertThat(store.acceptClaim(uid, mailId, time.serverNow(), plan)).isTrue();
        assertThat(store.markRead(uid, mailId, time.serverNow())).isTrue();
        assertThat(store.findById(uid, mailId).orElseThrow().acceptedPlan()).isEqualTo(plan);
        assertThat(store.releaseClaim(uid, mailId)).isFalse();
        mailRecoveryClock.set(source.expireAt());
        store.purgeExpired(time.serverNow());
        assertThat(store.pendingOf(uid)).extracting(MailStore.MailRecord::mailId).containsExactly(mailId);
        rebuilt().list(uid);
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        assertThat(store.pendingOf(uid)).isEmpty();
        rebuilt().list(uid);
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionWriteFailureOrLostReplyDoesNotCreditAgain(boolean afterWrite) {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            if (armed.compareAndSet(true, false)) {
                if (afterWrite) call.callRealMethod();
                throw new Interrupted();
            }
            return call.callRealMethod();
        }).when(store).completeAccepted(eq(uid), eq(mailId), eq(MailClaimPlan.keyOf(mailId)));
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        rebuilt().list(uid); rebuilt().claimAll(uid, new MailClaimAllReq(id()));
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        assertThat(store.findById(uid, mailId).orElseThrow().acceptedPlan()).isNull();
    }
    @Test void overflowSavedBeforeLostReplyKeepsSingleMailAndFirstDeadline() {
        String uid = player(); var player = players.findByPlayerId(uid).orElseThrow(); var state = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(state.cap(), state.cap(), state.protectedAmount(), 0, time.serverNow()));
        players.save(player);
        String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod(); MailStore.MailRecord saved = call.getArgument(0);
            if (saved.playerId().equals(uid) && saved.sourceRef().equals("mail:" + mailId) && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(store).save(any(MailStore.MailRecord.class));
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var original = store.listOf(uid, time.serverNow()).stream().filter(m -> !m.mailId().equals(mailId)).toList();
        assertThat(original).hasSize(1); var overflow = original.get(0);
        assertThat(overflow.sourceRef()).isEqualTo("mail:" + mailId);
        assertThat(overflow.text()).contains("来源：邮件附件奖励").doesNotContain(mailId);
        mailRecoveryClock.addAndGet(1000);
        rebuilt().list(uid); rebuilt().list(uid);
        var actual = store.listOf(uid, time.serverNow()).stream().filter(m -> !m.mailId().equals(mailId)).toList();
        assertThat(actual).containsExactly(overflow);
        assertThat(actual.get(0).rewards()).containsExactly(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000,
                names.nameOf(new com.ironoath.core.reward.RewardItem(com.ironoath.core.reward.RewardType.RESOURCE, "GRAIN", 8000))));
        assertThat(amount(uid, "GRAIN")).isEqualTo(state.cap());
        assertThat(store.findById(uid, mailId).orElseThrow().acceptedPlan()).isNull();
    }
    @Test void invalidLaterAttachmentDoesNotAcceptOrCreditEarlierResource() {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食"),
                new MailStore.RewardLine("RESOURCE", "INVALID_RESOURCE_FIXTURE", 1000, "未知资源")));
        assertThat(service.claimAll(uid, new MailClaimAllReq(id())).failed()).hasSize(1);
        assertThat(amount(uid, "GRAIN")).isZero();
        var source = store.findById(uid, mailId).orElseThrow();
        assertThat(source.claimedAt()).isNull(); assertThat(source.acceptedPlan()).isNull();
    }
    @Test void expiredOrHistoricalClaimedMailIsNotGuessedAndOtherPlayerIsUnaffected() {
        String uid = player(), other = player();
        String old = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        assertThat(store.claim(uid, old, time.serverNow())).isTrue();
        String expired = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "IRON", 1000, "铁矿")));
        var source = store.findById(uid, expired).orElseThrow();
        mailRecoveryClock.set(source.expireAt());
        assertThat(rebuilt().claimAll(uid, new MailClaimAllReq(id())).claimed()).isZero();
        assertThat(amount(uid, "GRAIN")).isZero(); assertThat(amount(uid, "IRON")).isZero();
        assertThat(amount(other, "GRAIN")).isZero();
        assertThat(store.pendingOf(uid)).isEmpty();
    }

    @Test void sameRequestRetryRecoversAcceptedAssetsBeforeDuplicateRequestGuard() {
        String uid = player(); String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var armed = new AtomicBoolean(true); var request = new MailClaimAllReq(id());
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (Boolean.TRUE.equals(result) && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(store).acceptClaim(eq(uid), eq(mailId), anyLong(), any(MailClaimPlan.class));
        assertThatThrownBy(() -> service.claimAll(uid, request)).isInstanceOf(Interrupted.class);
        assertThatThrownBy(() -> rebuilt().claimAll(uid, request)).isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
        assertThat(store.pendingOf(uid)).isEmpty();
        rebuilt().list(uid); assertThat(amount(uid, "GRAIN")).isEqualTo(8000);
    }
    @Test void partialOverflowReceiptCompletionReplaysWholePackWithoutDuplicateMail() {
        String uid = player(); var player = players.findByPlayerId(uid).orElseThrow();
        for (String resource : List.of("GRAIN", "IRON")) {
            var state = player.resource(resource);
            player.putResource(resource, new PlayerResourceState(state.cap(), state.cap(), state.protectedAmount(), 0, time.serverNow()));
        }
        players.save(player);
        String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食"),
                new MailStore.RewardLine("RESOURCE", "IRON", 1000, "铁矿")));
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod(); PlayerSave saved = call.getArgument(0);
            var first = saved.resourceClaims().get("mail_resource:" + mailId + ":part:0");
            var second = saved.resourceClaims().get("mail_resource:" + mailId + ":part:1");
            if (saved.playerId().equals(uid) && first != null && first.overflowMailId() != null && second != null
                    && second.pendingOverflow() && armed.compareAndSet(true, false)) throw new Interrupted();
            return result;
        }).when(players).save(any());
        assertThatThrownBy(() -> service.claimAll(uid, new MailClaimAllReq(id()))).isInstanceOf(Interrupted.class);
        var overflow = store.listOf(uid, time.serverNow()).stream().filter(m -> !m.mailId().equals(mailId)).toList();
        assertThat(overflow).hasSize(1); assertThat(overflow.get(0).rewards()).hasSize(2);
        rebuilt().list(uid); rebuilt().list(uid);
        assertThat(store.listOf(uid, time.serverNow()).stream().filter(m -> !m.mailId().equals(mailId)).toList()).containsExactlyElementsOf(overflow);
        var save = players.findByPlayerId(uid).orElseThrow();
        assertThat(save.resourceClaims().get("mail_resource:" + mailId + ":part:0").pendingOverflow()).isFalse();
        assertThat(save.resourceClaims().get("mail_resource:" + mailId + ":part:1").pendingOverflow()).isFalse();
    }
    @Test void acceptedPlanUsesFrozenExpiryEvenWhenFirstOverflowOccursAfterConfigRemoval() {
        String uid = player(); var player = players.findByPlayerId(uid).orElseThrow(); var state = player.resource("GRAIN");
        player.putResource("GRAIN", new PlayerResourceState(state.cap(), state.cap(), state.protectedAmount(), 0, time.serverNow())); players.save(player);
        String mailId = mail(uid, List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "粮食")));
        var source = store.findById(uid, mailId).orElseThrow(); var plan = new MailResourceRecovery(store, configs, rewards).freeze(source, time.serverNow());
        assertThat(store.acceptClaim(uid, mailId, time.serverNow(), plan)).isTrue();
        org.mockito.Mockito.doReturn(1L).when(configs).longParam("MAIL_RETENTION_DAYS");
        org.mockito.Mockito.doThrow(new com.ironoath.config.ConfigException("synthetic removed resource display row")).when(configs).getResource("GRAIN");
        mailRecoveryClock.addAndGet(1000);
        rebuilt().list(uid);
        var overflow = store.listOf(uid, time.serverNow()).stream().filter(m -> !m.mailId().equals(mailId)).toList();
        assertThat(overflow).hasSize(1); assertThat(overflow.get(0).createdAt()).isEqualTo(plan.acceptedAt());
        assertThat(overflow.get(0).expireAt()).isEqualTo(plan.overflowExpireAt());
        assertThat(overflow.get(0).rewards()).containsExactly(new MailStore.RewardLine("RESOURCE", "GRAIN", 8000, "资源奖励"));
        assertThat(store.pendingOf(uid)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"WOOD", "STONE", "IRON", "GRAIN", "STAMINA"})
    void everyNonGoldWalletResourceUsesStableClaim(String resource) {
        String uid = player(); String type = "STAMINA".equals(resource) ? "STAMINA" : "RESOURCE";
        String mailId = mail(uid, List.of(new MailStore.RewardLine(type, resource, 13, "资源奖励")));
        assertThat(service.claimAll(uid, new MailClaimAllReq(id())).failed()).isEmpty();
        assertThat(amount(uid, resource)).isEqualTo(13);
        rebuilt().claimAll(uid, new MailClaimAllReq(id())); assertThat(amount(uid, resource)).isEqualTo(13);
        assertThat(store.findById(uid, mailId).orElseThrow().acceptedPlan()).isNull();
    }
    @Test void paymentSourcesGoldAndUnsupportedAttachmentsDoNotEnterThisNewLane() {
        var helper = new MailResourceRecovery(store, configs, rewards); long now = time.serverNow();
        for (String source : List.of("pay:fixture", "paid_gold:fixture", "paid_item:fixture", "payment:fixture")) {
            var mail = new MailStore.MailRecord(id(), "fixture", "OVERFLOW", "夹具", "正文",
                    List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 13, "资源奖励")), source, now, now + 1000, null, null);
            assertThat(helper.supports(mail)).isFalse();
        }
        for (var lines : List.of(List.of(new MailStore.RewardLine("RESOURCE", "GOLD", 1, "金币")),
                List.of(new MailStore.RewardLine("RESOURCE", "GRAIN", 13, "资源奖励"), new MailStore.RewardLine("HERO", "hero_fixture", 1, "武将")))) {
            var mail = new MailStore.MailRecord(id(), "fixture", "SYSTEM", "夹具", "正文", lines, "fixture", now, now + 1000, null, null);
            assertThat(helper.supports(mail)).isFalse();
        }
    }
}
