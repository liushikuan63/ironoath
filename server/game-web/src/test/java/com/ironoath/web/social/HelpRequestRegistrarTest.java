package com.ironoath.web.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.social.Squad;
import com.ironoath.web.dto.generated.HelpTargetKind;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：求助登记的准入 —— 产品裁决「求助必须有队或盟」（2026-09-18）在代码里的落点。
 * 依赖：内存存储 + 空事件发布器，不起容器。
 *
 * <p><b>两个方向都要钉</b>：只断「散人不登记」的话，一个"无条件 return"的实现照样全绿 ——
 * 而那会把组织内的求助一起掐掉，症状是"盟友永远看不到可帮列表"，且没有任何用例会红。
 * 所以下面同时有「有队的玩家仍会登记」那一条。
 *
 * <p><b>为什么拦在这里而不是三个调用方</b>：求助登记是「升级建筑 / 训练 / 治疗」的<b>锁内副作用</b>，
 * 在调用方抛错误码的后果是「没小队的玩家连建筑都升不了」—— 裁决否掉的是「求助」这件事，不是那三个动作。
 */
class HelpRequestRegistrarTest {

    /** 组织规则：只为一件事存在 —— 让 {@code Squad.create} 能造出一个队（成员上限 5）。 */
    private static Squad.Rules squadRules() {
        return new Squad.Rules(
                List.of(new Squad.LevelRule(1L, 5L, 1L, 1L, 0L, false)), 1L, 0L, 100L, 10_000L);
    }

    private static HelpRequestRegistrar registrarOf(InMemorySocialStore store) {
        // 事件发布器：本类只关心"有没有登记"，广播那条链由 SocialEndpointTest 管
        return new HelpRequestRegistrar(store, new InMemoryPlayerStore(), event -> { });
    }

    @Test
    @DisplayName("散人开建：求助不登记 —— 一条没有任何人的收到方的登记，只会躺在列表里等过期")
    void playerWithoutOrganizationGetsNoHelpRequest() {
        InMemorySocialStore store = new InMemorySocialStore();
        HelpRequestRegistrar registrar = registrarOf(store);

        registrar.register("help_1", "P-solo", HelpTargetKind.BUILDING, "b1", "主城升级到 3 级",
                1_788_000_600_000L, 1_788_000_000_000L);

        assertThat(store.helpRequests())
                .as("没有队也没有盟 ⇒ 不登记（裁决：求助必须有队或盟）")
                .isEmpty();
        assertThat(store.helpRequest("help_1")).isEmpty();
    }

    @Test
    @DisplayName("有队的玩家照旧登记：裁决掐掉的是散人那一次，不是求助本身")
    void memberStillGetsHelpRequest() {
        InMemorySocialStore store = new InMemorySocialStore();
        store.saveSquad(Squad.create("sq_1", "测试队", "P-leader", squadRules()), 0L);
        HelpRequestRegistrar registrar = registrarOf(store);
        assertThat(store.squadOf("P-leader")).as("夹具前提：队长确实在有队状态").isPresent();

        registrar.register("help_2", "P-leader", HelpTargetKind.BUILDING, "b1", "主城升级到 3 级",
                1_788_000_600_000L, 1_788_000_000_000L);

        assertThat(store.helpRequest("help_2"))
                .as("有队 ⇒ 照常登记，否则盟友永远看不到可帮列表")
                .isPresent();
    }
}
