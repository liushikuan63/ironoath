package com.ironoath.web.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardGrantor;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryMailStore;

/** 新材料日包必须把两件道具与业务收据一起落盘，外层请求重试不能增发。 */
class DurableItemGrantTest {
    private static final long NOW = 1_791_850_000_000L;
    private static final String PLAYER = "P-material-card";
    private static final List<RewardItem> PACK = List.of(
            new RewardItem(RewardType.ITEM, "item_speedup_build_1h", 1),
            new RewardItem(RewardType.ITEM, "item_res_wood_10k", 1));
    private final ConfigRegistry configs = configs();
    private final InMemoryMailStore mails = new InMemoryMailStore();
    private final RewardPorts.Mailbox mailbox = new StoreMailbox(mails, new RewardNames(configs, mock(HeroFragmentExtras.class)), clock(), configs);
    private final RewardContext context = RewardContext.toMail("paid_items", "material-card-period", "card-day:order-one:0");

    @Test void replayUsesOriginalPackEvenWhenConfigAmountsChange() {
        var store = new InMemoryInventoryStore();
        store.insertIfAbsent(PLAYER, Inventory.empty(60));
        var grants = grants(store, mailbox);
        grants.grant(PLAYER, PACK, context);
        var replay = grants.grant(PLAYER, PACK.stream().map(r -> r.withCount(9)).toList(), context);
        assertThat(store.findByPlayerId(PLAYER).orElseThrow().countOf(PACK.get(0).id())).isEqualTo(1);
        assertThat(store.findByPlayerId(PLAYER).orElseThrow().countOf(PACK.get(1).id())).isEqualTo(1);
        assertThat(replay.granted()).isEmpty();
        assertThat(mails.listOf(PLAYER, NOW)).isEmpty();
    }

    @Test void commitThenLostReplyRetriesWholePackWithoutDuplicatingEitherItem() {
        var backing = new InMemoryInventoryStore();
        backing.insertIfAbsent(PLAYER, Inventory.empty(60));
        InventoryRepository store = spy(backing);
        var fail = new AtomicBoolean(true);
        doAnswer(call -> {
            Object version = call.callRealMethod();
            if (fail.getAndSet(false)) throw new IllegalStateException("fixture: committed inventory reply lost");
            return version;
        }).when(store).save(anyString(), any(Inventory.class), anyLong());
        var grants = grants(store, mailbox);
        assertThatThrownBy(() -> grants.grant(PLAYER, PACK, context)).isInstanceOf(IllegalStateException.class);
        grants.grant(PLAYER, PACK, context);
        assertThat(store.findByPlayerId(PLAYER).orElseThrow().countOf(PACK.get(0).id())).isEqualTo(1);
        assertThat(store.findByPlayerId(PLAYER).orElseThrow().countOf(PACK.get(1).id())).isEqualTo(1);
    }

    @Test void overflowMailLostReplyAndEarlyClaimCannotCreateAnotherOrResetClaimedMail() {
        var store = new InMemoryInventoryStore();
        var full = Inventory.empty(1);
        full.add("item_res_stone_10k", 1, 99);
        store.insertIfAbsent(PLAYER, full);
        var fail = new AtomicBoolean(true);
        RewardPorts.Mailbox flaky = (player, items, ctx) -> {
            String id = mailbox.sendOverflow(player, items, ctx);
            if (fail.getAndSet(false)) throw new IllegalStateException("fixture: mail committed reply lost");
            return id;
        };
        var grants = grants(store, flaky);
        assertThatThrownBy(() -> grants.grant(PLAYER, PACK, context)).isInstanceOf(IllegalStateException.class);
        var mail = mails.listOf(PLAYER, NOW).get(0);
        assertThat(mails.claim(PLAYER, mail.mailId(), NOW)).isTrue();
        grants.grant(PLAYER, PACK, context);
        assertThat(mails.listOf(PLAYER, NOW)).hasSize(1);
        assertThat(mails.findById(PLAYER, mail.mailId()).orElseThrow().claimedAt()).isEqualTo(NOW);
        assertThat(mail.text()).contains("月卡材料奖励").doesNotContain("paid_items", "material-card-period");
    }

    private RewardGrantor grants(InventoryRepository store, RewardPorts.Mailbox mail) {
        return new RewardGrantor(mock(RewardPorts.Wallet.class), new PlayerBag(store, configs), mail,
                mock(RewardPorts.Compensation.class), mock(RewardPorts.Extras.class), () -> NOW);
    }

    private static com.ironoath.common.time.TimeService clock() {
        var clock = mock(com.ironoath.common.time.TimeService.class);
        when(clock.serverNow()).thenReturn(NOW);
        return clock;
    }

    private static ConfigRegistry configs() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) return ConfigRegistry.loadFromDirectory(candidate);
            dir = dir.getParent();
        }
        throw new IllegalStateException("contract/config not found");
    }
}
