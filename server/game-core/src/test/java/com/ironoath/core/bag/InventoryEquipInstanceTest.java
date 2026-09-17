package com.ironoath.core.bag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：钉住装备实例账本（B20 §五⑤）在聚合根这一层的四条规则：按件铸造、按件占格、
 * 扣减原子且顺序可预期、老档迁移是读取的确定性函数。
 * 依赖：无（game-core 纯 Java）。
 *
 * <p><b>为什么这一族断言值得单独成一个类</b>：实例化之后「一件装备」第一次成为有身份的东西，
 * 而它的身份全部由这个聚合根保管 —— 上面那一圈（穿戴、强化、发奖）都只是搬运。
 * 最贵的一类错误是<b>静默少一件</b>：数量与实例两处各存一半、读的时候只看到一半。
 * 所以这里每条断言都在数「几件」，而不是"有没有"。
 *
 * <p><b>迁移为什么必须是纯函数</b>：内存存储读回来的是活对象，Mongo 每次读都新建一份。
 * 如果迁移用随机数铸 uid，同一份 Mongo 档读两次就得到两套 uid，而武将槽位里存的正是 uid ——
 * 症状是"重启之后装备才消失"，那是最难查的一类。{@link #legacyMigrationIsDeterministic}
 * 就是钉这一条的。
 */
class InventoryEquipInstanceTest {

    private static final Predicate<String> EQUIP_IS_INSTANCED = id -> id.startsWith("eq_");
    private static final String SWORD = "eq_iron_sword";
    private static final String BLADE = "eq_pojun_blade";

    private static Inventory bag() {
        return Inventory.empty(10);
    }

    // ---------- 铸造与持有 ----------

    @Test
    @DisplayName("两件同名装备是两个实例、两个 uid，占两格（旧的「itemId → 数量」不再成立）")
    void twoCopiesAreTwoDistinctInstances() {
        Inventory bag = bag();
        List<String> uids = bag.mintEquips(SWORD, 2);

        assertThat(uids).as("uid 各不相同").doesNotHaveDuplicates().hasSize(2);
        assertThat(bag.countOf(SWORD)).as("持有量 = 件数").isEqualTo(2L);
        assertThat(bag.capacityUsed()).as("按件占格：2 件占 2 格").isEqualTo(2);
        assertThat(bag.snapshot()).as("装备绝不进 counts 那张表").isEmpty();
        assertThat(bag.equipInstance(uids.get(0))).isNotNull();
        assertThat(bag.equipInstances(SWORD)).hasSize(2);
    }

    @Test
    @DisplayName("uid 的形状与配置行 id 天然可分（迁移判定靠形状，不靠查表）")
    void instanceUidsAreDistinguishableFromConfigRowIds() {
        assertThat(Inventory.isInstanceUid("e7")).isTrue();
        assertThat(Inventory.isInstanceUid("u:eq_iron_sword:2"))
                .as("老档迁移铸出来的号也是 uid").isTrue();
        assertThat(Inventory.isInstanceUid(SWORD)).as("行 id 不是 uid").isFalse();
        assertThat(Inventory.isInstanceUid("eq_e7")).as("以 eq_ 开头的行 id 不能被误判成 uid").isFalse();
        assertThat(Inventory.isInstanceUid("e")).as("光一个前缀不算号").isFalse();
        assertThat(Inventory.isInstanceUid("equipment")).isFalse();
        assertThat(Inventory.isInstanceUid(null)).isFalse();
    }

    // ---------- 穿戴 ----------

    @Test
    @DisplayName("穿上不删实例、只翻标志；标志只影响格子数，不影响它存在")
    void wearingFlipsTheFlagInsteadOfDeleting() {
        Inventory bag = bag();
        String uid = bag.mintEquips(SWORD, 1).get(0);
        int before = bag.capacityUsed();

        assertThat(bag.markWorn(uid, true).worn()).isTrue();
        assertThat(bag.capacityUsed()).as("穿着的不占格").isEqualTo(before - 1);
        assertThat(bag.countOf(SWORD)).as("东西还是他的，只是穿在身上").isEqualTo(1L);
        assertThat(bag.equipInstance(uid)).as("实例没有离开账本").isNotNull();

        bag.markWorn(uid, false);
        assertThat(bag.capacityUsed()).as("卸下重新占回那一格").isEqualTo(before);
    }

    @Test
    @DisplayName("改一个不存在的 uid 必须响亮失败，而不是静默造出一件")
    void wearingSomethingNotOwnedFails() {
        Inventory bag = bag();
        assertThatThrownBy(() -> bag.markWorn("e404", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e404");
        assertThat(bag.equipCount()).isZero();
    }

    @Test
    @DisplayName("第一件未穿的解析：已穿的那件不会被再次交出去")
    void firstUnwornSkipsWornOnes() {
        Inventory bag = bag();
        String first = bag.mintEquips(SWORD, 2).get(0);
        assertThat(bag.firstUnwornEquip(SWORD)).isEqualTo(first);

        bag.markWorn(first, true);
        assertThat(bag.firstUnwornEquip(SWORD)).isNotEqualTo(first);
        bag.markWorn(bag.firstUnwornEquip(SWORD), true);
        assertThat(bag.firstUnwornEquip(SWORD)).as("两件都穿了就没有未穿的").isNull();
    }

    // ---------- 扣减 ----------

    @Test
    @DisplayName("扣装备按件且原子：不足整笔不扣；够扣时先未穿、再等级低的")
    void removalIsAtomicAndOrderIsPredictable() {
        Inventory bag = bag();
        String wornHigh = bag.mintEquips(SWORD, 1).get(0);
        String unwornLow = bag.mintEquips(SWORD, 1).get(0);
        bag.restore(Map.of(), List.of(
                new Inventory.EquipInstance(wornHigh, SWORD, 5, true),
                new Inventory.EquipInstance(unwornLow, SWORD, 0, false)),
                10, 3, EQUIP_IS_INSTANCED);

        assertThat(bag.removeEquips(SWORD, 3)).as("只有 2 件，整笔不扣").isEmpty();
        assertThat(bag.equipCount()).as("被拒的扣减一件都不能少").isEqualTo(2);

        List<Inventory.EquipInstance> taken = bag.removeEquips(SWORD, 1);
        assertThat(taken).extracting(Inventory.EquipInstance::uid)
                .as("先牺牲未穿的、等级低的那件，穿着的 +5 不动")
                .containsExactly(unwornLow);
        assertThat(bag.equipInstance(wornHigh)).isNotNull();
    }

    // ---------- 迁移与持久化 ----------

    @Test
    @DisplayName("老档的 eq_xxx: 2 在读取时铸成两件实例，而不是留在数量表里看不见")
    void legacyCountsBecomeInstancesOnRead() {
        Inventory bag = bag();
        bag.restore(Map.of(SWORD, 2L, "item_res_wood_10k", 3L), null, 10, 1, EQUIP_IS_INSTANCED);

        assertThat(bag.countOf(SWORD)).as("§五⑤：绝不能读成没装备").isEqualTo(2L);
        assertThat(bag.equipInstances(SWORD)).extracting(Inventory.EquipInstance::uid)
                .containsExactly("u:eq_iron_sword:1", "u:eq_iron_sword:2");
        assertThat(bag.countOf("item_res_wood_10k"))
                .as("非装备的数量条目不许被顺手迁走").isEqualTo(3L);
        assertThat(bag.capacityUsed()).as("2 件装备 + 1 种道具 = 3 格").isEqualTo(3);
    }

    @Test
    @DisplayName("迁移是同一个输入的同一个函数：读两次得到同一套 uid（武将槽位里存的就是 uid）")
    void legacyMigrationIsDeterministic() {
        Inventory first = bag();
        Inventory second = bag();
        Map<String, Long> legacy = Map.of(SWORD, 2L);
        first.restore(legacy, null, 10, 1, EQUIP_IS_INSTANCED);
        second.restore(legacy, null, 10, 1, EQUIP_IS_INSTANCED);

        assertThat(first.equipSnapshot()).isEqualTo(second.equipSnapshot());
    }

    @Test
    @DisplayName("数量条目与实例同时存在时不重复铸造（防「白送一件」），也不丢数量条目")
    void staleCountDoesNotDoubleMint() {
        Inventory bag = bag();
        bag.restore(Map.of(SWORD, 2L),
                List.of(new Inventory.EquipInstance("e1", SWORD, 4, false)),
                10, 2, EQUIP_IS_INSTANCED);

        assertThat(bag.equipInstances(SWORD)).as("账本已有这种装备，就不再从数量条目铸").hasSize(1);
        assertThat(bag.countOf(SWORD)).as("读到的件数以账本为准").isEqualTo(1L);
    }

    @Test
    @DisplayName("实例账本、强化等级、穿着标志、铸造序号都跟着 copy 走（两套存储共用这一份定义）")
    void everythingSurvivesTheRoundTrip() {
        Inventory bag = bag();
        String uid = bag.mintEquips(BLADE, 1).get(0);
        bag.restore(bag.snapshot(), List.of(new Inventory.EquipInstance(uid, BLADE, 3, true)),
                10, bag.nextEquipUid() + 4, EQUIP_IS_INSTANCED);

        Inventory copy = bag.copy();
        assertThat(copy.equipSnapshot()).isEqualTo(bag.equipSnapshot());
        assertThat(copy.nextEquipUid()).as("序号不回拨，否则下一次铸造会盖掉已有的那件")
                .isEqualTo(bag.nextEquipUid());
        assertThat(copy.capacityUsed()).as("穿着的那件在副本里也不占格")
                .isEqualTo(bag.capacityUsed());
        assertThat(copy.equipInstance(uid).forgeLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("铸造序号向前推进且撞号时不回拨；等级为负或 uid 为空的实例在构造期就被拒")
    void guardsStaySharp() {
        Inventory bag = bag();
        bag.mintEquips(SWORD, 3);
        assertThat(bag.nextEquipUid()).isEqualTo(4);
        assertThatThrownBy(() -> bag.mintEquips(SWORD, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Inventory.EquipInstance("e1", SWORD, -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为负");
        assertThatThrownBy(() -> new Inventory.EquipInstance(" ", SWORD, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        // 序号被回拨的脏档：已有 e1..e3 而序号写着 1 → 铸造必须从 4 起，不能盖掉已有实例
        Inventory dirty = bag();
        dirty.restore(Map.of(), List.of(
                new Inventory.EquipInstance("e1", SWORD, 0, false),
                new Inventory.EquipInstance("e2", SWORD, 0, false),
                new Inventory.EquipInstance("e3", SWORD, 0, false)), 10, 1, EQUIP_IS_INSTANCED);
        assertThat(dirty.mintEquips(SWORD, 1).get(0)).isEqualTo("e4");
        assertThat(dirty.equipInstances(SWORD)).hasSize(4);
    }
}
