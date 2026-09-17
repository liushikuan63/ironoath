package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.battle.TechBonus;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.web.battle.AllianceTechBonuses;
import com.ironoath.web.battle.BattleTechBonuses;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceTechReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：个人科技进<b>战斗乘区 B</b> 与<b>医院容量</b>这两条接线的定点值验证（B20 验收 3 的第三、四格）。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>三件事各自要拦住的不同错法</b>：
 * <ul>
 *   <li><b>精确数</b>：3%/级 实现成 ×1.03 还是 ×1.3，战斗里都"打得动"，只有定点断言能分开；</li>
 *   <li><b>相加而不是相乘</b>（§五④）：联盟 +1.5% 与个人 +6% 并存时，
 *       乘出来的总率是 7.74%（774 万分比），加出来才是 7.5%（750）。
 *       两者都不会报错，但"两条线都点满"的玩家会悄悄多出 0.24% —— 而这类漂移一旦成型就再也拆不回乘区；</li>
 *   <li><b>装配点全传</b>：{@code toSide} 有八处（野怪、攻城双方、采集战双方、关卡双方…），
 *       漏一处的症状是"那场战斗没吃到个人科技"，而战报与日志一切正常。
 *       所以最后一条用例直接扫源码，而不是只测其中一处。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class TechBattleBonusTest {

    /** 锋芒 / 坚壁：攻防各 +3%/级（tech.json 的 0.03）。 */
    private static final long ATK_PER_LEVEL_FIXED = 300L;
    private static final long DEF_PER_LEVEL_FIXED = 300L;
    /** 伤营：医院容量 +5%/级（0.05）。 */
    private static final long HOSP_PER_LEVEL_FIXED = 500L;
    /** 联盟 atech_atk 单级 1.5%（定点 150），用来验"相加"。 */
    private static final long ALLIANCE_ATK_L1_FIXED = 150L;

    private static final String ATK_TECH = "tech_mil_atk";
    private static final String DEF_TECH = "tech_mil_def";
    private static final String HOSP_TECH = "tech_mil_hosp";

    @Autowired private BattleTechBonuses battleTechBonuses;
    @Autowired private AllianceTechBonuses allianceTechBonuses;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private ArmyAppService armyAppService;
    @Autowired private SocialAppService social;
    @Autowired private SocialStore socialStore;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        socialStore.clear();
    }

    // ---------- 夹具 ----------

    private static String reqId() {
        return "req-" + UUID.randomUUID();
    }

    private String newPlayer() {
        String playerId = playerInitService.init(new PlayerInitReq(
                reqId(), "dev-" + UUID.randomUUID(), "战斗接线", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(10);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(100_000L, gold.cap(),
                    gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    private void putTech(String playerId, Map<String, Integer> levels) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setTech(new PlayerTech(levels, null, null, 0L, 0L));
        players.save(save);
    }

    /** 医院摆到指定等级（走真实存储，这样 {@code hospitalCapacity} 读的就是夹具造出来的那份城）。 */
    private void giveHospital(String playerId, int level) {
        cityAppServiceList(playerId);
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        BuildingInstance hospital = city.findByConfigId("hospital");
        if (hospital == null) {
            city.restoreBuilding(new BuildingInstance("b-hospital-fixture", "hospital", level, 4, 4));
        } else {
            hospital.restore(level, hospital.gridX(), hospital.gridY(),
                    com.ironoath.core.city.BuildingStatus.IDLE, null,
                    0L, 0L, 0L, hospital.helpCount(), hospital.lastMovedAt(), hospital.lastFinishedAt());
        }
        cities.save(playerId, city, version);
    }

    private void cityAppServiceList(String playerId) {
        // 城建存档首次访问时才创建；这里只为了触发那一次创建，不断任何东西
        armyAppService.list(playerId);
    }

    // ---------- 乘区 B ----------

    @Test
    @DisplayName("只有个人科技时：攻 2 级 = 600、防 5 级 = 1500（万分比），别的属性不混进来")
    void personalTechLandsInZoneBWithExactValues() {
        String playerId = newPlayer();
        putTech(playerId, Map.of(ATK_TECH, 2, DEF_TECH, 5, HOSP_TECH, 4));

        assertThat(battleTechBonuses.forPlayer(playerId))
                .as("3%/级 × 2 = 600，3%/级 × 5 = 1500。伤营那一行不属于乘区，不能被算成攻防")
                .isEqualTo(new TechBonus(ATK_PER_LEVEL_FIXED * 2L, DEF_PER_LEVEL_FIXED * 5L));
        assertThat(battleTechBonuses.forPlayer(playerId).defenseFixed())
                .as("医院容量的 5%/级 绝不能变成防御加成")
                .isNotEqualTo(HOSP_PER_LEVEL_FIXED * 4L + DEF_PER_LEVEL_FIXED * 5L);
    }

    @Test
    @DisplayName("联盟与个人的同名加成是<b>相加</b>：150 + 600 = 750，不是相乘出来的 774")
    void allianceAndPersonalSumInsteadOfMultiply() {
        String leader = newPlayer();
        social.allianceCreate(leader, new AllianceCreateReq(reqId(), "相加盟", "SUM"));
        social.allianceDonate(leader, new AllianceDonateReq(reqId(), 0));
        social.allianceDonate(leader, new AllianceDonateReq(reqId(), 2));
        com.ironoath.core.social.Alliance alliance = socialStore.allianceOf(leader).orElseThrow();
        long expectedVersion = alliance.version();
        alliance.addFund(6000L);
        socialStore.saveAlliance(alliance, expectedVersion);
        social.allianceTech(leader, new AllianceTechReq(reqId(), "atech_atk", 1));

        assertThat(allianceTechBonuses.forPlayer(leader).attackFixed())
                .as("先确认联盟那一份真的是 150，否则下面那条相加断言会空转")
                .isEqualTo(ALLIANCE_ATK_L1_FIXED);

        putTech(leader, Map.of(ATK_TECH, 2));
        assertThat(battleTechBonuses.forPlayer(leader).attackFixed())
                .as("§五④：同类加成相加成总加成率。150 + 600 = 750；"
                        + "各自成为一个乘区再相乘会得到 774（1.015 × 1.06 - 1）")
                .isEqualTo(ALLIANCE_ATK_L1_FIXED + ATK_PER_LEVEL_FIXED * 2L)
                .isNotEqualTo(774L);
    }

    @Test
    @DisplayName("读不到存档也不抛：退回联盟那一份（Bot 未建档、战报重建都在这条路上）")
    void missingSaveFallsBackToAllianceSide() {
        assertThat(battleTechBonuses.forPlayer("P-never-existed"))
                .as("没有存档 = 没有个人科技，但也不该让一场战斗装配不起来")
                .isEqualTo(TechBonus.none());
        assertThat(battleTechBonuses.forPlayer(null)).isEqualTo(TechBonus.none());
    }

    // ---------- 医院容量 ----------

    @Test
    @DisplayName("医院容量按伤营等级放大：4 级 = +20%，取整口径是 HALF_UP(base × 1.2)")
    void hospitalCapacityScalesWithTheTechLevel() {
        String playerId = newPlayer();
        giveHospital(playerId, 6);
        long plain = armyAppService.hospitalCapacity(playerId, cities.findByPlayerId(playerId).orElseThrow());
        assertThat(plain).as("夹具必须真造出了医院，否则这条断言会空转").isPositive();

        putTech(playerId, Map.of(HOSP_TECH, 4));
        long expected = new BigDecimal(plain).multiply(new BigDecimal("1.2"))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        assertThat(armyAppService.hospitalCapacity(playerId, cities.findByPlayerId(playerId).orElseThrow()))
                .as("伤营 4 级 = +20%（5%/级），作用一次；HALF_UP 而不是 ceil（§五④ 的 ceil 只管缩短时长）")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("没建医院时容量仍是 0：加成不能凭空造出容量")
    void hospitalBonusCannotInventCapacity() {
        String playerId = newPlayer();
        putTech(playerId, Map.of(HOSP_TECH, 30));
        CityState city = new CityState();
        assertThat(armyAppService.hospitalCapacity(playerId, city))
                .as("基数为 0 就是 0 —— 这一句防的是「科技给一格不存在的医院加成」")
                .isZero();
    }

    // ---------- 装配点全传 ----------

    @Test
    @DisplayName("每一处 toSide 都必须带科技参数：漏一处的症状是「那场战斗没吃到个人科技」且不报错")
    void everyBattleAssemblyPassesTechBonuses() throws IOException {
        List<String> offenders = new ArrayList<>();
        int sides = 0;
        for (String rel : List.of(
                "server/game-web/src/main/java/com/ironoath/web/battle/MonsterBattleService.java",
                "server/game-web/src/main/java/com/ironoath/web/battle/PlayerCityBattleService.java",
                "server/game-web/src/main/java/com/ironoath/web/service/StageAppService.java")) {
            String src = Files.readString(repoRoot().resolve(rel)).replaceAll("\\s+", " ");
            var matcher = java.util.regex.Pattern.compile("toSide\\((.*?)\\);").matcher(src);
            while (matcher.find()) {
                sides++;
                String args = matcher.group(1);
                if (!args.contains("forPlayer(") && !args.contains("TechBonus.none()")) {
                    offenders.add(rel + " -> toSide(" + args.substring(0, Math.min(90, args.length())) + "…)");
                }
            }
        }
        assertThat(sides)
                .as("扫到的 toSide 装配点数（下面那三个文件里的全部军队装配）")
                .isGreaterThanOrEqualTo(6);
        assertThat(offenders)
                .as("每一处都必须显式给一份 TechBonus：玩家侧走 forPlayer（联盟 + 个人），"
                                + "野怪/关卡敌方走 TechBonus.none()。新增装配点时忘了传就是"
                        + "静默少一格加成，战斗本身不会报错")
                .isEmpty();
    }

    /** 测试的工作目录是模块目录，向上找到仓库根（与 {@code ConfigRegistry.resolveConfigDir} 同一条路子）。 */
    private static Path repoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            if (Files.isDirectory(cursor.resolve("contract"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("从 " + Path.of("").toAbsolutePath()
                + " 向上回溯 6 层没找到仓库根（contract 目录），这条用例的判据就无从建立");
    }
}
