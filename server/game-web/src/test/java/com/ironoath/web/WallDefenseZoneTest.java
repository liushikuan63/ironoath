package com.ironoath.web;

import com.ironoath.battle.OrgBonus;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.web.battle.PlayerCityBattleService;
import com.ironoath.config.ConfigRegistry;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：钉住乘区 H 的生产方 —— 城墙等级**真的**被折成 {@code OrgBonus.wallDefense}。
 * 依赖：起真实 Spring 上下文（要 {@code ConfigRegistry} 与 {@code PlayerCityBattleService}），
 * 但**不跑一场仗**：断言的是装配这一步。
 *
 * <p><b>为什么这一格必须有它</b>：城墙的幅度（+10%）与形状（线性 1→40 级）都是
 * {@code balance-sim --wall} 量出来的，而那一侧是**直接构造 OrgBonus** 的 ——
 * 它证明「乘区 H 在结算里管用」，不证明「玩家的城墙等级会进到那个字段」。
 * 缺了这一条，线上就会是「城墙建满了、加成恒为 0」而两边都全绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class WallDefenseZoneTest {

    @Autowired
    private PlayerCityBattleService service;

    @Autowired
    private ConfigRegistry configs;

    /** 反射调用私有的 wallBonus —— 它是装配的**唯一**入口，不给它开包可见性。 */
    private OrgBonus wallBonus(CityState city) throws Exception {
        Method method = PlayerCityBattleService.class.getDeclaredMethod("wallBonus", CityState.class);
        method.setAccessible(true);
        return (OrgBonus) method.invoke(service, city);
    }

    private static CityState cityWithWall(int level) {
        CityState city = new CityState();
        city.restoreBuilding(new BuildingInstance("b-wall", "wall", level, 3, 3));
        return city;
    }

    @Test
    @DisplayName("城墙等级越高，给守方的防御加成越大；满级恰好等于配置里的满值")
    void wallLevelDrivesTheDefenseBonus() throws Exception {
        long full = configs.fixedParam("WALL_DEFENSE_BONUS_FIXED");
        assertThat(full).as("满值应当是裁决定的 +10%").isEqualTo(1000L);
        int maxLevel = (int) configs.get(com.ironoath.config.cfg.BuildingCfg.class, "wall").maxLevel();

        // **对照组一：没有城墙** —— 新号没有城墙这件事本身必须给出 0，
        // 否则「加成恒等于满值」也会让上面那两条一起绿。
        assertThat(wallBonus(null).isZero()).as("没有城市存档 → 没有城墙加成").isTrue();
        assertThat(wallBonus(new CityState()).isZero())
                .as("城里一座建筑都没有 → 没有城墙加成").isTrue();
        CityState noWall = new CityState();
        noWall.restoreBuilding(new BuildingInstance("b-farm", "farm", 12, 1, 1));
        assertThat(wallBonus(noWall).isZero())
                .as("城里只有农场、没有城墙 → 加成仍是 0（别把别的建筑当成城墙）").isTrue();

        long quarter = wallBonus(cityWithWall(maxLevel / 4)).wallDefense();
        long half = wallBonus(cityWithWall(maxLevel / 2)).wallDefense();
        long top = wallBonus(cityWithWall(maxLevel)).wallDefense();

        assertThat(quarter).as("四分之一级").isPositive().isLessThan(half);
        assertThat(half).as("半级").isGreaterThan(quarter).isLessThan(top);
        assertThat(top).as("满级恰好等于配置里的满值").isEqualTo(full);
        // **线性**：四分之一级应当是满值的四分之一（±1 是定点取整的余量）
        assertThat(Math.abs(quarter * 4L - full)).as("线性：四分之一级 = 满值/4").isLessThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("等级不归零也不越顶：等级 0 没有加成，超出上限按上限算")
    void levelIsClamped() throws Exception {
        int maxLevel = (int) configs.get(com.ironoath.config.cfg.BuildingCfg.class, "wall").maxLevel();
        long full = configs.fixedParam("WALL_DEFENSE_BONUS_FIXED");

        assertThat(wallBonus(cityWithWall(0)).isZero()).as("0 级 = 没有城墙").isTrue();
        assertThat(wallBonus(cityWithWall(maxLevel + 9)).wallDefense())
                .as("超出上限按上限算（存档被改过也不能白拿加成）").isEqualTo(full);
    }
}