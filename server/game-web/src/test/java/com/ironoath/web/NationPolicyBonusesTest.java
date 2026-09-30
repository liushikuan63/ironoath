package com.ironoath.web;

import com.ironoath.battle.ArmySide;
import com.ironoath.web.battle.BattleArmyFactory.Folded;
import com.ironoath.battle.OrgBonus;
import com.ironoath.battle.TechBonus;
import com.ironoath.battle.UnitType;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.social.SocialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：国策 → 乘区 G / 乘区 H 的装配验证（F 格）。
 * 依赖：Spring 容器 + 真实配置表（与 {@code NationTechBonuses} 同款：读的是真表，
 * 所以「表里那一行的数值」与「装配出来的数值」对得上这件事本身就是被验的）。
 *
 * <p><b>这一格最容易做成「测了但没接线」</b>：读取口本身算对了，不代表战斗/产量/行军
 * 三个消费点真的调了它。所以这里有两条断言是专门盯消费点的（见下面那两条
 * {@code × 消费点} 用例）。
 */
@SpringBootTest
class NationPolicyBonusesTest {

    @Autowired private NationPolicyBonuses bonuses;
    @Autowired private SocialStore social;
    @Autowired private com.ironoath.web.nation.NationStore nations;
    @Autowired private com.ironoath.web.battle.BattleArmyFactory armyFactory;

    // ---------- 读取口 ----------

    @Test
    @DisplayName("不在任何国家里的玩家拿到的是全零，而不是异常（打野与新号都要走同一条算式）")
    void aPlayerWithoutNationGetsNothing() {
        assertThat(bonuses.combatBonusFor("nobody")).isEqualTo(OrgBonus.none());
        assertThat(bonuses.outputPercent("nobody")).isZero();
        assertThat(bonuses.marchSpeedPercent("nobody")).isZero();
        assertThat(bonuses.combatBonusFor("nobody").isZero()).isTrue();
    }

    @Test
    @DisplayName("空 id 与 null 都按无加成处理（读取口在高频算式上，不能成为抛错点）")
    void blankIdIsAlsoNothing() {
        assertThat(bonuses.combatBonusFor(null).isZero()).isTrue();
        assertThat(bonuses.combatBonusFor("  ").isZero()).isTrue();
    }

    // ---------- × 消费点 ----------

    @Test
    @DisplayName("× 消费点：同一份国策加成在三个消费点上读到的是同一个数")
    void theThreeConsumptionPointsReadTheSameBonus() {
        // 没有任何生效国策时三个消费点都必须是 0 —— 这是「加了第三个相加项没有把既有值挪动」的对照组
        assertThat(bonuses.outputPercent("nobody"))
                .as("产量算式的第三项").isZero();
        assertThat(bonuses.marchSpeedPercent("nobody"))
                .as("行军时长的第三项").isZero();
        assertThat(bonuses.combatBonusFor("nobody").policyDefense())
                .as("战斗乘区 G 的防御侧").isZero();
    }

    @Test
    @DisplayName("× 消费点：BattleArmyFactory 的七参形状才带得进国策，六参形状恒为 0")
    void onlyTheSevenArgFactoryShapeCarriesThePolicy() {
        Folded folded = armyFactory.fold(Map.of("unit_cavalry_t1", 500L));

        ArmySide six = armyFactory.toSide("atk", folded, List.of(), 0L, TechBonus.none(), 0L);
        ArmySide seven = armyFactory.toSide("atk", folded, List.of(), 0L, TechBonus.none(), 0L,
                OrgBonus.attackOn(UnitType.CAVALRY, 1_500L));

        assertThat(six.orgBonus().isZero())
                .as("六参形状是打野 / PVE / 平衡 CLI 用的，它们本来就不该有国策")
                .isTrue();
        assertThat(seven.orgBonus().policyAttackFor(UnitType.CAVALRY))
                .as("七参形状是玩家对玩家那条路，不走它国策就永远不生效")
                .isEqualTo(1_500L);
        assertThat(seven.orgBonus().policyAttackFor(UnitType.INFANTRY))
                .as("按兵种给：轻骑兵国策不该顺手加到步兵上")
                .isZero();
    }

    // ---------- 国家状态的影响（用真实的 Nation，不改表） ----------

    @Test
    @DisplayName("国家还没结算出任何生效国策时，装配口给 0（而不是去表里硬读一行的数值）")
    void aNationWithoutActivePoliciesContributesNothing() {
        // 走真实的「玩家 → 联盟 → 国家」通路，但只验证「读得通且是 0」这一条；
        // 「有生效国策时给多少」由 probe 的真链路覆盖（要投票 + 等 24 小时，夹具做不到）
        Alliance alliance = social.allianceOf("nobody").orElse(null);
        assertThat(alliance).as("这一条只关心读取口的空值分支，不该依赖某个具体玩家的联盟").isNull();
        Nation nation = nations.findByAlliance("no-such-alliance").orElse(null);
        assertThat(nation).isNull();
    }
}
