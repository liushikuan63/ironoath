package com.ironoath.core.bag;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ironoath.core.reward.RewardContext;
class InventoryItemClaimTest {
    private RewardContext ctx(String key) {return new RewardContext("paid_item","orderA",key,true,Set.of("monthly-day:1"));}
    private Map<String,Long> pair() {var m=new LinkedHashMap<String,Long>();m.put("speed",1L);m.put("wood",1L);return m;}
    @Test void oneAtomicReceiptSurvivesCopyAndConsumptionWithoutRepeatingItems() {
        var bag=Inventory.empty(2);var receipt=bag.claimItemsOnce(pair(),Map.of("speed",99L,"wood",99L),ctx("daily1"),1000);
        assertThat(bag.snapshot()).containsEntry("speed",1L).containsEntry("wood",1L);
        assertThat(receipt.credited()).isEqualTo(pair());assertThat(receipt.overflow()).isEmpty();
        bag=bag.copy();bag.remove("speed",1);bag.remove("wood",1);
        assertThat(bag.claimItemsOnce(pair(),Map.of("speed",99L,"wood",99L),ctx("daily1"),2000)).isEqualTo(receipt);
        assertThat(bag.snapshot()).isEmpty();assertThat(bag.itemClaims()).hasSize(1);
        assertThatThrownBy(()->receipt.requested().put("wood",999L)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void overflowReceiptOwnsExactMissingItemsAndMailProjection() {
        var bag=Inventory.empty(1);var receipt=bag.claimItemsOnce(pair(),Map.of("speed",99L,"wood",99L),ctx("daily1"),1000);
        assertThat(receipt.credited()).containsEntry("speed",1L);assertThat(receipt.overflow()).containsEntry("wood",1L);
        assertThat(receipt.pendingOverflow()).isTrue();bag.completeItemOverflow("daily1","mail1");
        assertThat(bag.copy().itemClaims().get("daily1").overflowMailId()).isEqualTo("mail1");
        assertThat(bag.itemClaims().get("daily1").pendingOverflow()).isFalse();
    }
    @Test void allInputsAreValidatedBeforeAnyItemMutationAndKeyCannotChangeMeaning() {
        var bag=Inventory.empty(2);
        assertThatThrownBy(()->bag.claimItemsOnce(pair(),Map.of("speed",99L),ctx("daily1"),1000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(bag.snapshot()).isEmpty();assertThat(bag.itemClaims()).isEmpty();
        bag.claimItemsOnce(pair(),Map.of("speed",99L,"wood",99L),ctx("daily1"),1000);
        assertThatThrownBy(()->bag.claimItemsOnce(Map.of("speed",9L),Map.of("speed",99L),ctx("daily1"),2000)).isInstanceOf(IllegalStateException.class);
        assertThat(bag.countOf("speed")).isEqualTo(1);assertThat(bag.countOf("wood")).isEqualTo(1);
    }
    @Test void oldMissingReceiptsRestoreAsEmptyAndStackLimitOverflowIsRetained() {
        var bag=Inventory.empty(1);bag.add("speed",98,99);
        var receipt=bag.claimItemsOnce(Map.of("speed",3L),Map.of("speed",99L),ctx("daily1"),1000);
        assertThat(receipt.credited()).containsEntry("speed",1L);assertThat(receipt.overflow()).containsEntry("speed",2L);
        bag.restoreItemClaims(null);assertThat(bag.itemClaims()).isEmpty();assertThat(bag.countOf("speed")).isEqualTo(99);
    }
    @Test void restoringAnotherSnapshotOnSameInventoryCannotRetainPreviousItemReceipts() {
        var bag=Inventory.empty(2);
        bag.claimItemsOnce(pair(),Map.of("speed",99L,"wood",99L),ctx("daily1"),1000);
        assertThat(bag.itemClaims()).containsKey("daily1");
        bag.restore(Map.of("replacement",2L),List.of(),2,1,itemId->false);
        assertThat(bag.snapshot()).containsExactlyEntriesOf(Map.of("replacement",2L));
        assertThat(bag.itemClaims()).as("another snapshot without receipt fields cannot inherit the prior claim ledger").isEmpty();
        assertThat(bag.copy().itemClaims()).isEmpty();
    }
}
