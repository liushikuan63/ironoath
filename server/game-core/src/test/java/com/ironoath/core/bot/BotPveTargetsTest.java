package com.ironoath.core.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.world.Coord;

/**
 * 职责：Bot 的 PvE 选目标规则（B11 §三 第 3 条；收口清单 #96 定的口径）。
 * 依赖：JUnit 5 + AssertJ + game-core（纯 Java，零框架、零配置）。
 *
 * <p><b>为什么这些断言必须写在这里而不是端点层</b>：选择规则的全部输入就是一张候选列表，
 * 纯 JUnit 能把「打不过的不打」「同级取近」「并列取坐标序」这些边界一条条钉死；
 * 端点层负责验的是另一件事（选中的目标真的变成了一次出征）。
 */
class BotPveTargetsTest {

    private static final Coord HOME = Coord.of(100, 100);

    @Test
    @DisplayName("打野：在一群打得过的怪里挑等级最高的（50/78/122 vs 战力 100 ⇒ 挑 78）")
    void picksTheStrongestBeatableMonster() {
        var weak = new BotPveTargets.Monster(Coord.of(101, 100), "mapmonster_lv01", 1, 50L);
        var mid = new BotPveTargets.Monster(Coord.of(105, 100), "mapmonster_lv03", 3, 78L);
        var tooStrong = new BotPveTargets.Monster(Coord.of(102, 100), "mapmonster_lv05", 5, 122L);

        var picked = BotPveTargets.pickMonster(List.of(weak, tooStrong, mid), 100L, HOME);

        assertThat(picked).as("等级最高但打得过的那一只").contains(mid);
    }

    @Test
    @DisplayName("打野：一只都打不过时返回空 —— 玩家不会拿全部家当去白送")
    void neverPicksMonsterItCannotBeat() {
        var lv05 = new BotPveTargets.Monster(Coord.of(102, 102), "mapmonster_lv05", 5, 122L);
        var lv04 = new BotPveTargets.Monster(Coord.of(103, 101), "mapmonster_lv04", 4, 98L);

        // 战力 80：lv04(98) 与 lv05(122) 都超过它
        assertThat(BotPveTargets.pickMonster(List.of(lv05, lv04), 80L, HOME))
                .as("打不过就一只都不选（调用方安静跳过，不是错误）").isEmpty();
        // 战力为 0（新号没有任何战斗单位）同理
        assertThat(BotPveTargets.pickMonster(List.of(lv05), 0L, HOME)).isEmpty();
    }

    @Test
    @DisplayName("打野：同级取更近的；距离也并列时取坐标序（确定性，不见随机）")
    void monsterTiesGoToTheNearerThenToCoordOrder() {
        var far = new BotPveTargets.Monster(Coord.of(110, 100), "mapmonster_lv02", 2, 63L);
        var near = new BotPveTargets.Monster(Coord.of(103, 100), "mapmonster_lv02", 2, 63L);

        assertThat(BotPveTargets.pickMonster(List.of(far, near), 500L, HOME))
                .as("同等级取更近的那只").contains(near);

        // 同距（曼哈顿都是 3）的两只：按坐标序取小的 —— 顺序不同的同一份候选必须给出同一答案
        var a = new BotPveTargets.Monster(Coord.of(103, 100), "mapmonster_lv02", 2, 63L);
        var b = new BotPveTargets.Monster(Coord.of(100, 103), "mapmonster_lv02", 2, 63L);
        assertThat(BotPveTargets.pickMonster(List.of(a, b), 500L, HOME))
                .isEqualTo(BotPveTargets.pickMonster(List.of(b, a), 500L, HOME));
    }

    @Test
    @DisplayName("采集：挑最近的资源点；并列时同样取坐标序，且与列表顺序无关")
    void picksTheNearestResourceDeterministically() {
        var wood = new BotPveTargets.Resource(Coord.of(108, 100), "WOOD");
        var stone = new BotPveTargets.Resource(Coord.of(102, 100), "STONE");
        var iron = new BotPveTargets.Resource(Coord.of(104, 100), "IRON");

        assertThat(BotPveTargets.pickResource(List.of(wood, stone, iron), HOME))
                .as("最近的是 (102,100)").contains(stone);

        var a = new BotPveTargets.Resource(Coord.of(102, 100), "STONE");
        var b = new BotPveTargets.Resource(Coord.of(100, 102), "WOOD");
        assertThat(BotPveTargets.pickResource(List.of(a, b), HOME))
                .isEqualTo(BotPveTargets.pickResource(List.of(b, a), HOME));
    }

    @Test
    @DisplayName("看不见任何候选时返回空（而不是抛错）：安静跳过是正常结局")
    void emptyCandidatesYieldEmpty() {
        assertThat(BotPveTargets.pickMonster(List.of(), 999L, HOME)).isEmpty();
        assertThat(BotPveTargets.pickResource(List.of(), HOME)).isEmpty();
    }

    @Test
    @DisplayName("候选的构造期校验：行 id 缺失、等级非正、战力为负都要当场炸")
    void invalidCandidatesAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new BotPveTargets.Monster(Coord.of(1, 1), " ", 1, 50L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotPveTargets.Monster(Coord.of(1, 1), "mapmonster_lv01", 0, 50L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotPveTargets.Monster(Coord.of(1, 1), "mapmonster_lv01", 1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotPveTargets.Resource(Coord.of(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
