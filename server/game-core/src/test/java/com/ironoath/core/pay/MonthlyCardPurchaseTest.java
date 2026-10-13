package com.ironoath.core.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ironoath.core.player.PlayerPaid;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

class MonthlyCardPurchaseTest {
    static final long DAY=86_400_000L;
    static final long START=Instant.parse("2030-03-02T15:55:00Z").toEpochMilli();
    private MonthlyCardTerms terms(boolean material) {
        return new MonthlyCardTerms(material?"材料月卡":"金币月卡",30,material?0:300,
                material?List.of(new RewardItem(RewardType.ITEM,"item_speedup_build_1h",1),new RewardItem(RewardType.ITEM,"item_res_wood_10k",1))
                        :List.of(new RewardItem(RewardType.RESOURCE,"GOLD",90)),material,0,material);
    }
    private MonthlyCardPurchase purchase(String order,String sku,long at) {
        return new MonthlyCardPurchase(order,sku,terms(sku.equals("material")),at,at+30*DAY,1,0);
    }
    @Test void thirtyDailyPacksAreCappedAcrossBeijingBusinessDayAndExpiry() {
        var p=purchase("one","gold",START);long total=300;
        assertThat(p.claimableDays(START-1)).isZero();assertThat(p.claimableDays(START)).isEqualTo(1);
        var settled=p.withSettledDays(1);
        assertThat(settled.claimableDays(START+60_000)).isZero();
        assertThat(settled.claimableDays(START+10*60_000)).as("Beijing midnight remains the same business day").isZero();
        assertThat(settled.claimableDays(Instant.parse("2030-03-02T19:59:59Z").toEpochMilli())).isZero();
        assertThat(settled.claimableDays(Instant.parse("2030-03-02T20:00:00Z").toEpochMilli())).as("Beijing04:00 refresh").isEqualTo(1);
        for(int n=0;n<30;n++) {long now=START+n*DAY;assertThat(p.claimableDays(now)).isEqualTo(1);total+=90;p=p.withSettledDays(n+1);}
        assertThat(total).isEqualTo(3000);assertThat(p.claimableDays(START+30*DAY-1)).isZero();
        assertThat(p.active(START+30*DAY)).isFalse();assertThat(p.claimableDays(START+30*DAY)).isZero();
    }
    @Test void existingActiveOnlyRemainingDayCapAlsoAppliesToNewCard() {
        var p=purchase("one","gold",START);
        assertThat(p.claimableDays(START+28*DAY)).isEqualTo(3);
        assertThat(p.claimableDays(START+30*DAY)).as("No new right to claim after expiry").isZero();
        assertThatThrownBy(()->p.withSettledDays(-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void independentPurchasesSurviveAllLegacyPaidCopiesAndSameOrderDoesNotRenew() {
        var gold=purchase("gold-order","gold",START);var material=purchase("material-order","material",START);
        var old=PlayerPaid.empty().withCardExtended(START,30*DAY).withCardSettledThrough(START);
        var paid=old.withMonthlyPurchase(gold).withMonthlyPurchase(material).withMonthlyPurchase(gold);
        assertThat(paid.monthlyPurchases()).hasSize(2);assertThat(paid.cardExpireAt()).isEqualTo(old.cardExpireAt());
        assertThat(paid.cardClaimedThroughAt()).isEqualTo(old.cardClaimedThroughAt());
        paid=paid.withFundPurchased(START).withFirstCharged(START).withFundTierClaimed("tier1").withFulfilledOrder("done").withCardExtended(START,DAY).withCardSettledThrough(START+DAY);
        assertThat(paid.monthlyPurchases()).containsEntry("gold-order",gold).containsEntry("material-order",material);
        assertThat(new PlayerPaid(null,null,null,Set.of(),null,Set.of()).monthlyPurchases()).isEmpty();
        assertThat(new PlayerPaid(null,null,null,Set.of(),null,Set.of(),null).monthlyPurchases()).isEmpty();
        var changed=new MonthlyCardPurchase(gold.orderId(),"other-sku",gold.terms(),gold.startsAt(),gold.expiresAt(),1,0);
        final var saved=paid;assertThatThrownBy(()->saved.withMonthlyPurchase(changed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void orderSnapshotPreservesTermsAndOlderSnapshotsRemainNull() {
        var terms=terms(false);var order=PayOrder.create("order","uid",new PayOrder.Line("gold",1,3000,START,null,terms));
        var restored=PayOrder.fromSnapshot(order.snapshot());assertThat(restored.line().cardTerms()).isEqualTo(terms);
        var legacy=PayOrder.create("old","uid",new PayOrder.Line("monthly_card",1,3000,START,null));
        assertThat(PayOrder.fromSnapshot(legacy.snapshot()).line().cardTerms()).isNull();
        assertThatThrownBy(()->terms.dailyRewards().add(new RewardItem(RewardType.RESOURCE,"GOLD",999))).isInstanceOf(UnsupportedOperationException.class);
    }
}
