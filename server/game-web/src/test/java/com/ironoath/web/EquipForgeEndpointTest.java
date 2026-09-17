package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.EquipForgeBlockReason;
import com.ironoath.web.dto.generated.EquipForgeReq;
import com.ironoath.web.dto.generated.EquipForgeResp;
import com.ironoath.web.dto.generated.EquipInstanceView;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.equip.EquipAppService;
import com.ironoath.web.service.HeroAppService;

/**
 * 职责：钉住 B20 验收 4 的四件事 —— 消耗正确、等级 +1、`might` 定点增加、到上限拒绝；
 * 外加清单端点确实能把 uid 交出来（客户端唯一能拿到 uid 的地方）。
 * 依赖：Spring 上下文（真表 + 内存存储）+ {@code EquipAppService}。
 *
 * <p><b>价格在这里写成表推导出来的常数，而不是在测试里重跑一遍算式</b>：
 * 12 点属性的铁剑一级 = 12 × 70 = 840 铁，二级 = 840 × 1.22 = 1024.8 → 1025。
 * 测试里如果写 {@code attrs * base * ratio^n}，改表就会连期望值一起改，那条断言就此失去意义
 * （#154 那条"错位一档 ⇒ 一级与二级花一样的钱"的教训正是这么暴露的）。
 *
 * <p><b>"到上限"那一条用一件已经满级的装备做前提</b>：N 档 +10 全程要 24 072 铁，
 * 而铁的基础容量上限只有 10 000，靠强化本身走不到顶。所以账本按"满级的一件"直接摆进夹具，
 * 断言的是"这一笔被拒且<b>一个铁都没扣</b>" —— 校验排在扣款之前，这条顺序比"抛对的码"更值得钉。
 */
@SpringBootTest
@ActiveProfiles("test")
class EquipForgeEndpointTest {

    /** 铁剑三维总和 12 ⇒ 一级 12 × 70。 */
    private static final long SWORD_L1 = 840L;
    /** 840 × 1.22 = 1024.8 ⇒ 四舍五入到 1025（定点落地一次）。 */
    private static final long SWORD_L2 = 1025L;
    private static final String SWORD = "eq_iron_sword";
    private static final String BLADE = "eq_pojun_blade";
    private static final String HERO = "hero_ssr_01";

    @Autowired private EquipAppService equipAppService;
    @Autowired private HeroAppService heroAppService;
    @Autowired private PlayerRepository players;
    @Autowired private InventoryRepository inventories;
    @Autowired private HeroRepository heroes;
    @Autowired private RewardPorts.Bag bagPort;
    @Autowired private com.ironoath.web.service.PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;

    @Test
    @DisplayName("清单端点把 uid 交出来：两件铁剑就是两条实例，字段齐且能直接拿去强化")
    void listHandsOutTheUidsTheForgeCallNeeds() {
        String playerId = newPlayerWithIron(9_000L);
        assertThat(bagPort.add(playerId, SWORD, 2L)).isEqualTo(2L);

        var view = equipAppService.list(playerId);
        assertThat(view.instances()).as("两件 = 两条实例").hasSize(2);
        EquipInstanceView first = view.instances().get(0);
        assertThat(first.uid()).as("uid 就是强化请求要的位").isNotBlank();
        assertThat(first.equipId()).isEqualTo(SWORD);
        assertThat(first.name()).as("中文名从表里来（改表立刻生效）").isEqualTo("铁剑");
        assertThat(first.forgeLevel()).isZero();
        assertThat(first.forgeMax()).isEqualTo(10);
        assertThat(first.nextCostIron()).as("+0 → +1 的一级价").isEqualTo(SWORD_L1);
        assertThat(first.canForge()).isTrue();
        assertThat(first.blockReason()).isEqualTo(EquipForgeBlockReason.NONE);
        assertThat(first.wornByHeroId()).as("没穿在任何人身上").isNull();
        assertThat(first.mightFixed()).as("12 点 = 120000 定点").isEqualTo(120_000L);
    }

    @Test
    @DisplayName("强化一次：扣 840 铁、等级 +1、武力从 120000 变 126000 定点")
    void forgingOneLevelPaysAndRaisesExactly() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, SWORD, 1L);
        String uid = onlyUid(playerId);
        long before = iron(playerId);

        EquipForgeResp resp = equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), uid));

        assertThat(resp.costIron()).as("消耗 = 表推导的 840，不是「感觉合理」").isEqualTo(SWORD_L1);
        assertThat(iron(playerId)).as("余额精确减 840").isEqualTo(before - SWORD_L1);
        assertThat(resp.instance().forgeLevel()).isEqualTo(1);
        assertThat(resp.instance().mightFixed()).isEqualTo(126_000L);
        assertThat(resp.instance().nextCostIron()).as("下一级按 1.22 递增").isEqualTo(SWORD_L2);
    }

    @Test
    @DisplayName("逐级递增：连强两级花 840 + 1025，第二级不便宜")
    void costRisesWithLevel() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, SWORD, 1L);
        String uid = onlyUid(playerId);

        equipAppService.forge(playerId, new EquipForgeReq("req-" + UUID.randomUUID(), uid));
        long mid = iron(playerId);
        EquipForgeResp second = equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), uid));

        assertThat(second.costIron()).isEqualTo(SWORD_L2);
        assertThat(mid - iron(playerId)).isEqualTo(SWORD_L2);
        assertThat(second.instance().forgeLevel()).isEqualTo(2);
        // 12 × (1 + 5% × 2) = 13.2 ⇒ 定点 132000（不是 13，也不是一路舍到 13.19）
        assertThat(second.instance().mightFixed()).isEqualTo(132_000L);
    }

    @Test
    @DisplayName("穿着的那一件涨战力，包里的那一件 powerDelta 精确为 0")
    void onlyTheWornPieceMovesPower() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, BLADE, 1L);
        String uid = onlyUid(playerId);
        heroAppService.equip(playerId, new com.ironoath.web.dto.generated.HeroEquipReq(
                "req-" + UUID.randomUUID(), HERO,
                com.ironoath.web.dto.generated.EquipSlot.WEAPON, uid));

        EquipForgeResp worn = equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), uid));
        assertThat(worn.powerDelta())
                .as("穿着的那一件强化必须涨战力（每 10 点属性折 1% 加成，见 HERO_ATTR_PER_PERCENT）")
                .isPositive();
        assertThat(equipAppService.list(playerId).instances().get(0).wornByHeroId())
                .isEqualTo(HERO);

        bagPort.add(playerId, SWORD, 1L);
        String inBag = equipAppService.list(playerId).instances().stream()
                .filter(instance -> instance.wornByHeroId() == null)
                .map(EquipInstanceView::uid).findFirst().orElseThrow();
        assertThat(equipAppService.forge(playerId, new EquipForgeReq("req-" + UUID.randomUUID(), inBag))
                .powerDelta()).as("在包里的不涨任何战力").isZero();
    }

    @Test
    @DisplayName("已到上限：回 EQUIP_FORGE_MAX(4005)，并且一个铁都不扣")
    void maxedPieceIsRefusedAndCostsNothing() {
        String playerId = newPlayerWithIron(9_000L);
        // 先走一次生产发放口把背包建出来（PlayerInitService 不建背包，第一次 add 才建），
        // 否则下面 bagOf 抛的是"没有背包记录"，与本题无关
        bagPort.add(playerId, SWORD, 1L);
        // N 档 +10 全程 24 072 铁 > 铁的基础容量 10 000，靠强化走不到顶，所以满级前提直接摆出来
        Inventory bag = bagOf(playerId);
        bag.restore(bag.snapshot(), List.of(new Inventory.EquipInstance("e1", SWORD, 10, false)),
                bag.capacityMax(), 2, id -> false);
        inventories.save(playerId, bag, inventories.versionOf(playerId));
        long before = iron(playerId);

        assertThatThrownBy(() -> equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), "e1")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.EQUIP_FORGE_MAX);
        assertThat(iron(playerId)).as("被拒的一笔不许花钱（校验全部排在扣款之前）").isEqualTo(before);
        assertThat(equipAppService.list(playerId).instances().get(0).canForge()).isFalse();
        assertThat(equipAppService.list(playerId).instances().get(0).nextCostIron())
                .as("满级时报 0，而 0 不是免费：blockReason 才是要显示的那句话")
                .isZero();
        assertThat(equipAppService.list(playerId).instances().get(0).blockReason())
                .isEqualTo(EquipForgeBlockReason.MAX_LEVEL);
    }

    @Test
    @DisplayName("铁不够：回 RESOURCE_NOT_ENOUGH(4000)，等级不动")
    void insufficientIronLeavesTheLevelAlone() {
        String playerId = newPlayerWithIron(100L);
        bagPort.add(playerId, SWORD, 1L);
        String uid = onlyUid(playerId);

        assertThat(equipAppService.list(playerId).instances().get(0).blockReason())
                .as("面板先说一句假话就已经是缺陷：清单必须已经判出铁不够")
                .isEqualTo(EquipForgeBlockReason.IRON_LOW);
        assertThatThrownBy(() -> equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), uid)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_ENOUGH);
        assertThat(onlyView(playerId).forgeLevel()).isZero();
        assertThat(iron(playerId)).isEqualTo(100L);
    }

    @Test
    @DisplayName("幂等：同一个 requestId 重放只扣一次铁（弱网重试是常态）")
    void sameRequestIdChargesOnce() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, SWORD, 1L);
        String uid = onlyUid(playerId);
        String requestId = "req-" + UUID.randomUUID();
        long before = iron(playerId);

        equipAppService.forge(playerId, new EquipForgeReq(requestId, uid));
        assertThatThrownBy(() -> equipAppService.forge(playerId, new EquipForgeReq(requestId, uid)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(iron(playerId)).as("重放不许再扣一次").isEqualTo(before - SWORD_L1);
        assertThat(onlyView(playerId).forgeLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 requestId 直接拒：强化是花钱动作，不许有无去重的那条路")
    void missingRequestIdIsRefused() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, SWORD, 1L);
        String uid = onlyUid(playerId);

        assertThatThrownBy(() -> equipAppService.forge(playerId, new EquipForgeReq(null, uid)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING);
        assertThat(onlyView(playerId).forgeLevel()).isZero();
    }

    @Test
    @DisplayName("传配置行 id 强化：PARAM_INVALID 且说清去哪拿 uid（这一族不替玩家挑「哪一件」）")
    void aRowIdIsNotAnUid() {
        String playerId = newPlayerWithIron(9_000L);
        bagPort.add(playerId, SWORD, 1L);

        assertThatThrownBy(() -> equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), SWORD)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("GET /equip/instances")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("不存在的 uid：ITEM_NOT_FOUND，不凭空造出一件 +1 的空气装备")
    void unknownUidIsNotFound() {
        String playerId = newPlayerWithIron(9_000L);
        long before = iron(playerId);

        assertThatThrownBy(() -> equipAppService.forge(playerId,
                new EquipForgeReq("req-" + UUID.randomUUID(), "e404")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_FOUND);
        assertThat(iron(playerId)).isEqualTo(before);
        assertThat(equipAppService.list(playerId).instances()).isEmpty();
    }

    // ---------- 夹具 ----------

    private String newPlayerWithIron(long amount) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "装备测试",
                1_700_000_000_000L, "")).playerId();
        if (!heroes.findByPlayerId(playerId).isPresent()) {
            // 新号的武将档是按需建的（PlayerInitService 不建它），先 insert 再改：
            // 直接 versionOf 抛的是「武将存档不存在」，那与本题无关
            heroes.insertIfAbsent(playerId, new HeroRoster());
        }
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseThrow();
        // 这名武将是"穿着的那件涨不涨战力"那条断言的<b>前提条件</b>，不是被测对象，
        // 所以直接摆一个 25 级的（够穿 SR 装的 20 级门槛），不走碎片合成那条与本题无关的链
        roster.restoreHero(new com.ironoath.core.hero.HeroInstance(HERO, 25, 0L, 1, 0, 1, 1));
        heroes.save(playerId, roster, heroes.versionOf(playerId));
        putIron(playerId, amount);
        return playerId;
    }

    /**
     * 夹具给铁。<b>直接摆 {@link PlayerResourceState} 而不是走发奖器</b>：本类要测的是"强化扣多少"，
     * 资源的发放与结算各自早有归属测试（B04 / #145），这里只把它当已成立的前提。
     *
     * <p><b>给的量必须贴着容量上限</b>：{@code ResourceRateService.settleTo} 校验
     * 「资源量不得超过容量上限」，超过 10 000 会让<b>每一次结算都抛</b>（症状是所有用例死在
     * 与装备无关的地方）。9 000 够本类全部路径：最贵的一条是连强两级铁剑 840+1025，
     * 加一件 SR 装的一级 3150，也才 5015。
     */
    private void putIron(String playerId, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = save.resource("IRON");
        long capped = Math.min(amount, s.cap());
        save.putResource("IRON", new PlayerResourceState(capped, s.cap(),
                s.protectedAmount(), s.perHour(), s.lastSettle()));
        players.save(save);
    }

    private long iron(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("IRON").current();
    }

    private Inventory bagOf(String playerId) {
        return inventories.findByPlayerId(playerId).orElseThrow();
    }

    private String onlyUid(String playerId) {
        return equipAppService.list(playerId).instances().stream()
                .map(EquipInstanceView::uid).findFirst()
                .orElseThrow(() -> new AssertionError("夹具里没有装备实例"));
    }

    private EquipInstanceView onlyView(String playerId) {
        return equipAppService.list(playerId).instances().get(0);
    }
}
