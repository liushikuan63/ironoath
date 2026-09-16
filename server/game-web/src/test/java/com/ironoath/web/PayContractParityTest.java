package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.PayRewardItem;
import com.ironoath.web.pay.PaidProducts;

/**
 * 职责：钉住付费域里那几处<b>故意重新声明</b>的枚举与三段类型转换（B19 §二）。
 * 依赖：两侧的类型本身。
 *
 * <p><b>为什么这份文件存在</b>：{@code pay.schema.json} 的 PayRewardItem.type 写着
 * 「取值与 bag 协议的 RewardType 一致（由 parity 测试钉住）」，而 {@code OrderStatus} 那句写的是
 * 「由 PayContractParityTest 断言」—— 在本批次之前<b>这个类并不存在</b>。
 * 文档指着一个不存在的量具，比没有文档更糟：读的人会以为这件事有人守着。
 * （同族的 {@code NationPayEnumParityTest} 管的是订单状态收窄，两者不重叠。）
 *
 * <p><b>三处转换是这条链上最容易松的地方</b>：配置表枚举（RESOURCE / ITEM 两个值）、
 * 领域枚举（六个值）、协议里那个裸字符串，是同一条"这是什么奖励"的三个形态。
 * 表里将来放开一个取值而 {@link PaidProducts#fromConfigured} 忘了同步，
 * 症状不是编译不过，而是玩家领到别的东西。
 */
class PayContractParityTest {

    @Test
    @DisplayName("协议里 RewardType 的取值与领域枚举逐字一致（pay.schema 与 bag.schema 各声明了一份）")
    void protocolRewardTypeMatchesTheDomainEnum() {
        List<String> domain = java.util.Arrays.stream(RewardType.values()).map(Enum::name).toList();
        List<String> protocol = java.util.Arrays.stream(
                com.ironoath.web.dto.generated.RewardType.values()).map(Enum::name).toList();

        assertThat(protocol).as("bag.schema 里那份重新声明的枚举必须与领域枚举逐项同序").isEqualTo(domain);
    }

    @Test
    @DisplayName("PayRewardItem.type 只能是领域枚举的名字：裸字符串换来的是必须有人收窄一次")
    void rewardItemTypeIsNarrowableToTheDomainEnum() {
        for (RewardType type : RewardType.values()) {
            PayRewardItem item = new PayRewardItem(type.name(), "any_id", 1L);
            assertThat(RewardType.valueOf(item.type()))
                    .as("%s 必须能原样读回来，否则查单端点回放会抛 IllegalArgumentException", type)
                    .isEqualTo(type);
        }
        assertThatThrownBy(() -> RewardType.valueOf("VIP_DAYS"))
                .as("表外取值必须响：静默兜底成某个默认类型等于把奖励发错东西")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("配置表枚举 ⇄ 领域枚举双向逐值对齐，表外的那几类必须拒绝从 product_reward 发")
    void rewardTypeBridgesAreExact() {
        for (ProductRewardCfg.RewardType configured : ProductRewardCfg.RewardType.values()) {
            RewardType domain = PaidProducts.fromConfigured(configured);
            assertThat(PaidProducts.toConfigured(domain))
                    .as("%s 绕一圈必须回到原值", configured)
                    .isEqualTo(configured);
        }
        for (RewardType outside : List.of(RewardType.HERO, RewardType.HERO_FRAGMENT,
                RewardType.STAMINA, RewardType.PRIVILEGE)) {
            assertThatThrownBy(() -> PaidProducts.toConfigured(outside))
                    .as("%s 不在 product_reward 的取值范围内，从这条路发它会领到别的东西", outside)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("订单行 ⇄ 领域奖励 ⇄ 协议奖励：三态转一圈之后每一项都原样")
    void rewardRoundTripPreservesEveryField() {
        List<RewardItem> original = List.of(
                new RewardItem(RewardType.RESOURCE, "GOLD", 120L),
                new RewardItem(RewardType.ITEM, "item_speedup_build_1h", 2L),
                new RewardItem(RewardType.HERO, "hero_sr_02", 1L),
                new RewardItem(RewardType.PRIVILEGE, "monthly_card", 30L));

        List<PayOrder.RewardRow> rows = PaidProducts.toOrderRows(original);
        assertThat(PaidProducts.fromProtocolRows(rows))
                .as("订单里存的那一份读回来必须与发出去的那一份逐项相等").isEqualTo(original);
        assertThat(PaidProducts.toProtocol(original))
                .as("下发给客户端的那一份是同一批内容，type 用枚举名字")
                .extracting(PayRewardItem::type, PayRewardItem::id, PayRewardItem::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RESOURCE", "GOLD", 120L),
                        org.assertj.core.groups.Tuple.tuple("ITEM", "item_speedup_build_1h", 2L),
                        org.assertj.core.groups.Tuple.tuple("HERO", "hero_sr_02", 1L),
                        org.assertj.core.groups.Tuple.tuple("PRIVILEGE", "monthly_card", 30L));
        assertThat(rows).extracting(PayOrder.RewardRow::type)
                .containsExactly("RESOURCE", "ITEM", "HERO", "PRIVILEGE");
    }

    @Test
    @DisplayName("订单里的奖励行不许带零量或空 id：那一行是玩家对账的依据")
    void rewardRowGuardsItself() {
        assertThatThrownBy(() -> new PayOrder.RewardRow("RESOURCE", "GOLD", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayOrder.RewardRow("RESOURCE", " ", 5L))
                .as("空 id 读回来是一个没有指向的奖励，玩家与客服都无从核对")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recordRewards 只准写一次，且未确认收款的订单不准记发货结果")
    void recordedRewardsAreWriteOnce() {
        PayOrder pending = PayOrder.create("order_parity_pending", "P-parity",
                new PayOrder.Line("monthly_card", 1, 3000L, 1L, null));
        assertThatThrownBy(() -> pending.recordRewards(
                List.of(new PayOrder.RewardRow("PRIVILEGE", "monthly_card", 30L))))
                .as("还没收到钱就记下「发过货」，等于账面上凭空多出一笔已履约")
                .isInstanceOf(IllegalStateException.class);

        pending.confirmCallback("txn-1", true, 2L);
        pending.recordRewards(List.of(new PayOrder.RewardRow("PRIVILEGE", "monthly_card", 30L)));
        assertThat(pending.rewards()).hasSize(1);
        assertThatThrownBy(() -> pending.recordRewards(
                List.of(new PayOrder.RewardRow("RESOURCE", "GOLD", 120L))))
                .as("第二套清单必须响：两套清单同时存在时没人能判断哪一套真发出去了")
                .isInstanceOf(IllegalStateException.class);

        PayOrder other = PayOrder.create("order_parity_empty", "P-parity",
                new PayOrder.Line("monthly_card", 1, 3000L, 1L, null));
        other.confirmCallback("txn-2", true, 2L);
        assertThatThrownBy(() -> other.recordRewards(List.of()))
                .as("空清单不是「发过货」，记下来会让查单端点把这一单当成已结清")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("heroChoice 只许是武将 id 或 null：空串会被读成两种不同的意思")
    void lineRejectsBlankHeroChoice() {
        assertThatThrownBy(() -> new PayOrder.Line("first_charge", 1, 600L, 1L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PayOrder.Line("first_charge", 1, 600L, 1L, "hero_sr_01").heroChoice())
                .isEqualTo("hero_sr_01");
    }
}
