package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.RoundSnapshot;
import com.ironoath.battle.SkillEffect;
import com.ironoath.battle.SkillPhase;
import com.ironoath.battle.SkillTrigger;
import com.ironoath.battle.UnitType;
import com.ironoath.battle.Winner;
import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.store.memory.InMemoryBattleReportStore;
import com.ironoath.web.store.mongo.BattleReportDocument;
import com.ironoath.web.store.mongo.MongoBattleReportStore;

/**
 * 职责：战报存储在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的战报档）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>这份测试真正防的是"列表看起来正常、点开是空的"</b>：{@code BattleReportService#list}
 * 只用到摘要字段，回放要的 {@code result.rounds} 只有 {@code open} 才读 ——
 * 于是嵌套结构里少落一层（比如 {@code Map<UnitType,Long>} 的键没转回来、
 * {@code skills} 整列没写）时，列表页完全正常，玩家点进回放才看到 0 个回合。
 * 单端断言抓不到这种形状：内存版不写库当然对，Mongo 版只看"能不能存进去读出来"也不够，
 * 必须**逐字段比**并且**两套实现都跑**。
 *
 * <p>三条容易被各自实现理解错的语义各有一条：幂等不覆盖、倒序 + 同刻按 reportId 定序、
 * 过期边界（{@code expiresAt <= now}）。
 */
class BattleReportStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long TTL = 72L * 3_600_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    /** 集合在整个类里共享、内存版每条用例新建实例 —— 不清理就会互相污染。 */
    @BeforeEach
    void clearReports() {
        if (db != null) {
            newMongoStore().clear();
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Test
    @DisplayName("同 reportId 重复写入是幂等而不是覆盖：两套实现都保留第一份")
    void duplicateReportIdKeepsTheFirstReportOnBothStores() {
        for (BattleReportStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(report("r-dup", "P-first", "A-1", T0, 11L));
            store.save(report("r-dup", "P-second", "A-2", T0 + 5_000L, 22L));

            BattleReport kept = store.findById("r-dup").orElseThrow();
            assertThat(kept.ownerId()).as("%s 覆盖会把一份战报的归属改掉", label).isEqualTo("P-first");
            assertThat(kept.result().seed()).as("%s 内容也必须还是第一份", label).isEqualTo(11L);
            assertThat(store.reportsOf("P-second")).as("%s 第二份不该因此出现在别人列表里", label)
                    .isEmpty();
            assertThat(store.findById("r-dup").map(BattleReport::result).orElseThrow().seed())
                    .as("%s 重复读还是那一份（幂等不是「读的时候碰运气」）", label).isEqualTo(11L);
        }
    }

    @Test
    @DisplayName("列表按生成时刻倒序、同刻按 reportId 定序：两套实现给出同一个顺序")
    void listOrderIsIdenticalAcrossStores() {
        List<String> fromMemory = null;
        List<String> fromMongo = null;
        for (int i = 0; i < bothStores().size(); i++) {
            BattleReportStore store = bothStores().get(i);
            // 三条同一时刻：只有第二排序键能决定它们的先后，而顺序必须稳定
            store.save(report("r-b", "P-order", "A-1", T0, 1L));
            store.save(report("r-a", "P-order", "A-2", T0, 2L));
            store.save(report("r-c", "P-order", "A-3", T0 + 60_000L, 3L));
            store.save(report("r-z", "P-other", "A-4", T0, 4L));
            List<String> ids = new ArrayList<>();
            store.reportsOf("P-order").forEach(r -> ids.add(r.reportId()));
            if (i == 0) {
                fromMemory = ids;
            } else {
                fromMongo = ids;
            }
        }
        assertThat(fromMongo).as("顺序必须逐位一致").isEqualTo(fromMemory);
        assertThat(fromMemory).as("最新的在前，同刻按 reportId 升序")
                .containsExactly("r-c", "r-a", "r-b");
    }

    @Test
    @DisplayName("过期边界两侧同一条：expiresAt == now 就算过期，多一毫秒也不算")
    void expiryBoundaryIsTheSameOnBothStores() {
        for (BattleReportStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            long boundary = T0 + TTL;
            store.save(new BattleReport("r-edge", "P-edge", "A-1", "D-1", "守", "攻",
                    BattleType.PVE, List.of(), List.of(), result(1L), boundary - 1L, boundary));
            store.save(new BattleReport("r-keep", "P-edge", "A-1", "D-1", "守", "攻",
                    BattleType.PVE, List.of(), List.of(), result(2L), boundary, boundary + 1L));

            assertThat(store.purgeExpired(boundary)).as("%s 到期的那条必须被清掉", label).isEqualTo(1);
            assertThat(store.findById("r-edge")).as("%s 清掉的不能还在", label).isEmpty();
            assertThat(store.findById("r-keep")).as("%s 还差一毫秒的不该被连带删掉", label).isPresent();
            assertThat(store.purgeExpired(boundary)).as("%s 重复清理必须报 0 条", label).isZero();
        }
    }

    @Test
    @DisplayName("空 ownerId 与别人的战报：两套实现给出同一个答案")
    void nullOwnerAndForeignLookupsAgree() {
        for (BattleReportStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(report("r-own", "P-own", "A-1", T0, 1L));
            assertThat(store.reportsOf(null)).as("%s null 必须回空表而不是抛", label).isEmpty();
            assertThat(store.findById(null)).as("%s null id 必须回 empty", label).isEmpty();
            assertThat(store.findById("r-missing")).as("%s 不存在回 empty", label).isEmpty();
            // 归属判定在 service 层（open 里比 ownerId），存储层必须照样给得到 ——
            // 存储层"顺手"改成只查自己的话，service 那句"不是你的也回不存在"就变成"回不存在因为查不到"，
            // 两种情况在日志里再也分不开
            assertThat(store.findById("r-own")).as("%s 跨玩家按 id 仍要查得到", label).isPresent();
        }
    }

    /** 摘要字段对了不代表回放对了：这一条逐字段钻到最深一层。 */
    @Test
    @DisplayName("回放载荷逐字段落得回去：回合快照、兵种剩余映射、技能触发、战果与种子")
    void replayPayloadSurvivesTheRoundTrip() {
        requireMongo();
        for (BattleReportStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(fullReport("r-full"));

            BattleReport back = store.findById("r-full").orElseThrow();
            assertThat(describe(back)).as("%s 整份战报逐字段一致（含最深层）", label)
                    .isEqualTo(describe(fullReport("r-full")));

            BattleResult result = back.result();
            assertThat(result.rounds()).as("%s 回合快照一条都不能少（列表只用摘要，缺了只有回放看得出来）",
                    label).hasSize(2);
            RoundSnapshot first = result.rounds().get(0);
            assertThat(first.atkUnits().get(UnitType.CAVALRY))
                    .as("%s 枚举键的 Map 必须转回枚举键，否则回放里每回合都是空的", label).isEqualTo(880L);
            assertThat(first.skills()).as("%s 技能触发记录（谁在何时放了什么）", label).hasSize(1);
            SkillTrigger skill = first.skills().get(0);
            assertThat(skill.phase()).isEqualTo(SkillPhase.ON_HIT);
            assertThat(skill.effect()).isEqualTo(SkillEffect.DAMAGE);
            assertThat(skill.heroId()).isEqualTo("H-1");
            assertThat(skill.appliedFixed()).as("实际生效值与声明值分开存，回放要对得上账").isEqualTo(8123L);
            assertThat(result.loot().get("wood")).as("战利品按资源 id 记").isEqualTo(12_345L);
            assertThat(result.seed()).as("铁律 4：种子丢了就没法复算这一场").isEqualTo(987_654_321L);
            assertThat(back.attackerHeroIds()).as("武将顺序是站位，技能归属按它判")
                    .containsExactly("H-1", "H-2", "H-3");
        }
    }

    @Test
    @DisplayName("战报两条索引必须存在：这是涨得最快的一张表")
    void battleReportHasBothIndexes() {
        requireMongo();
        List<String> shapes = new ArrayList<>();
        db.template().getCollection(BattleReportDocument.COLLECTION).listIndexes()
                .forEach(info -> shapes.add(info.get("key").toString()));
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("ownerId").contains("report.createdAt"));
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("report.expiresAt"));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「战报存得下来」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：战报的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    // ---------- 夹具 ----------

    private List<BattleReportStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryBattleReportStore(), newMongoStore());
    }

    private static MongoBattleReportStore newMongoStore() {
        requireMongo();
        return new MongoBattleReportStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static BattleReport report(String reportId, String ownerId, String defenderId,
                                       long createdAt, long seed) {
        return new BattleReport(reportId, ownerId, "A-attacker", defenderId, "敌国斥候", "铁誓军团",
                BattleType.PVP_SOLO, List.of("H-1"), List.of("H-9"), result(seed),
                createdAt, createdAt + TTL);
    }

    /** 把每一层都塞满非默认值：默认值掩盖漏字段（本项目已经为此写过四条同类记录）。 */
    private static BattleReport fullReport(String reportId) {
        return new BattleReport(reportId, "P-full", "A-attacker", "D-defender", "守方名", "攻方名",
                BattleType.PVP_RALLY, List.of("H-1", "H-2", "H-3"), List.of("H-9"),
                result(987_654_321L), T0, T0 + TTL);
    }

    private static BattleResult result(long seed) {
        Map<UnitType, Long> atk = units(900L, 880L, 700L, 60L);
        Map<UnitType, Long> def = units(700L, 500L, 400L, 30L);
        List<SkillTrigger> skills = List.of(new SkillTrigger(1, SkillPhase.ON_HIT, "H-1", 1,
                "sk_cavalry_charge", SkillEffect.DAMAGE, 15_000L, 8_123L));
        return new BattleResult(Winner.ATTACKER,
                List.of(new RoundSnapshot(1, atk, def, 100L, 200L, 350L, 280L, 41_000L, skills),
                        new RoundSnapshot(2, units(800L, 880L, 640L, 60L), units(500L, 400L, 300L, 10L),
                                200L, 300L, 330L, 260L, 39_000L, List.of())),
                2, atk, def, 100L, 50L, 7L, 200L, 30L, 11L,
                Map.of("wood", 12_345L, "iron", 678L), 50_000L, seed, List.of(41_000L, 39_000L));
    }

    private static Map<UnitType, Long> units(long infantry, long cavalry, long archer, long siege) {
        Map<UnitType, Long> out = new EnumMap<>(UnitType.class);
        UnitType[] all = UnitType.values();
        long[] values = {infantry, cavalry, archer, siege};
        for (int i = 0; i < all.length; i++) {
            out.put(all[i], i < values.length ? values[i] : 0L);
        }
        return out;
    }

    /** 逐字段串（包含最深层），两侧比对只用它。 */
    private static String describe(BattleReport report) {
        BattleResult r = report.result();
        StringBuilder s = new StringBuilder(report.reportId()).append('|').append(report.ownerId())
                .append('|').append(report.attackerId()).append('|').append(report.defenderId())
                .append('|').append(report.attackerName()).append('|').append(report.defenderName())
                .append('|').append(report.battleType()).append('|').append(report.createdAt())
                .append('|').append(report.expiresAt())
                .append("|atkHeroes=").append(report.attackerHeroIds())
                .append("|defHeroes=").append(report.defenderHeroIds())
                .append('|').append(r.winner()).append("|rounds=").append(r.rounds().size())
                .append("|total=").append(r.totalRounds())
                .append("|dead=").append(r.atkDead()).append('/').append(r.defDead())
                .append("|wounded=").append(r.atkWounded()).append('/').append(r.defWounded())
                .append("|overflow=").append(r.atkOverflowDead()).append('/').append(r.defOverflowDead())
                .append("|loot=").append(r.loot().size()).append("/wood=").append(r.loot().get("wood"))
                .append("|lootCap=").append(r.lootCapacity())
                .append("|seed=").append(r.seed())
                .append("|attrition=").append(r.attritionLog());
        for (UnitType type : UnitType.values()) {
            s.append("|surv=").append(type).append(':').append(r.atkSurvivors().get(type))
                    .append('/').append(r.defSurvivors().get(type));
        }
        for (RoundSnapshot round : r.rounds()) {
            s.append("|R").append(round.round()).append(':').append(round.atkLoss())
                    .append('/').append(round.defLoss()).append('/').append(round.atkAttack())
                    .append('/').append(round.defDefense()).append('/').append(round.attritionFixed())
                    .append(":skills=").append(round.skills().size());
            for (SkillTrigger skill : round.skills()) {
                s.append('[').append(skill.round()).append(',').append(skill.phase())
                        .append(',').append(skill.heroId()).append(',').append(skill.slot())
                        .append(',').append(skill.skillId()).append(',').append(skill.effect())
                        .append(',').append(skill.valueFixed()).append(',').append(skill.appliedFixed())
                        .append(']');
            }
            for (UnitType type : UnitType.values()) {
                s.append(',').append(type).append(':').append(round.atkUnits().get(type))
                        .append('/').append(round.defUnits().get(type));
            }
        }
        return s.toString();
    }
}
