package com.ironoath.web;

import com.ironoath.web.dto.generated.BattleSide;
import com.ironoath.web.dto.generated.BattleType;
import com.ironoath.web.dto.generated.SkillPhase;
import com.ironoath.web.dto.generated.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：战斗表现层协议（battle.schema.json）与 game-battle 内核枚举的一致性。
 * 依赖：JUnit + AssertJ；只比对枚举常量，不需要容器。
 *
 * <p><b>为什么这条测试必须存在</b>：生成器只能校验「协议里的枚举与配置表一致」
 * （{@code x-enum-source} 机制），而战斗协议里的这四个枚举来自 <b>game-battle 的手写枚举</b>，
 * 不是配置表 —— 生成器看不见它们，漂移不会有任何报警。
 *
 * <p>漂移的后果很具体：服务端 {@code Winner.DRAW} 序列化成 "DRAW"，
 * 而协议里没有 DRAW 这一项时，TS 侧的类型是 union，运行时拿到的是 undefined，
 * 客户端的「平局」分支永远不会走到，玩家看到的是一场没有结果的战斗。
 * 这类问题在编译期与测试期都不报错，只在线上表现为「战报显示异常」。
 */
class BattleContractParityTest {

    @Test
    @DisplayName("协议 UnitType 与 game-battle 的 UnitType、unit 表的 type 列三方一致")
    void unitTypeMatchesKernelAndConfig() {
        List<String> contract = names(UnitType.values());
        assertThat(contract)
                .as("协议侧 UnitType 来自 battle.schema.json，内核侧来自 game-battle")
                .containsExactlyElementsOf(names(com.ironoath.battle.UnitType.values()));
        // 与 army 协议里的同名枚举也要一致：两份协议各自生成一个 UnitType，
        // 漂移的表现是同一个兵种在军队界面与战报界面显示成不同的名字
        assertThat(contract)
                .as("army.schema.json 与 battle.schema.json 里的 UnitType 必须是同一套取值")
                .containsExactlyElementsOf(names(com.ironoath.web.dto.generated.UnitType.values()));
    }

    @Test
    @DisplayName("协议 BattleSide 与 game-battle 的 Winner 一致（含 DRAW）")
    void battleSideMatchesWinner() {
        assertThat(names(BattleSide.values()))
                .as("少了 DRAW 的话，平局战斗在客户端会显示成没有结果")
                .containsExactlyElementsOf(names(com.ironoath.battle.Winner.values()));
    }

    @Test
    @DisplayName("协议 BattleType 与 game-battle 的 BattleType 一致（它决定死伤比例）")
    void battleTypeMatchesKernel() {
        assertThat(names(BattleType.values()))
                .as("BattleType 决定 global 表里用哪一组 WOUND_RATIO_*，漂移会让死伤比例套错")
                .containsExactlyElementsOf(names(com.ironoath.battle.BattleType.values()));
    }

    @Test
    @DisplayName("协议 SkillPhase 与 game-battle 的 SkillPhase 一致")
    void skillPhaseMatchesKernel() {
        assertThat(names(SkillPhase.values()))
                .as("技能触发时机决定客户端播哪种特效，漂移会让 ROUND_START 的技能播成 ON_HIT 的表现")
                .containsExactlyElementsOf(names(com.ironoath.battle.SkillPhase.values()));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
