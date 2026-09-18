package com.ironoath.web.social;

import java.util.List;

import com.ironoath.core.social.Squad;

/**
 * 职责：测试夹具 —— 让某个玩家"有一个组织"。
 * 依赖：{@code SocialStore} 的 {@code saveSquad}（内存与 Mongo 两套实现都有）。
 *
 * <p><b>为什么需要它</b>：产品裁决「求助必须有队或盟」（2026-09-18）之后，
 * `HelpRequestRegistrar` 对既无小队也无同盟的玩家<b>不再登记求助</b>。
 * 于是所有"要断言求助登记/可帮列表/一键帮助/红点"的夹具都必须先把玩家放进一个组织里 ——
 * 这不是为了让测试变绿，而是**那条断言在新口径下本来就不该对散人成立**。
 *
 * <p><b>为什么直接写存储而不走 {@code /squad/create}</b>：建队要主城 5 级起，
 * 而这些用例测的是求助链路，不该顺带把城建等级也抬上去（那会让失败原因变得难判）。
 * 这里造的是"已经是某队队长"的状态，与端点写入的形状一致。
 */
public final class TestSquads {

    private TestSquads() {
    }

    /** 小队规则：只为一件事存在 —— 让 {@code Squad.create} 能造出一个队（成员上限 5）。 */
    public static Squad.Rules rules() {
        return new Squad.Rules(
                List.of(new Squad.LevelRule(1L, 5L, 1L, 1L, 0L, false)), 1L, 0L, 100L, 10_000L);
    }

    /** 让该玩家成为一个新队的队长（即"有组织"，可以求助）。 */
    public static void leaderOf(SocialStore store, String playerId) {
        store.saveSquad(Squad.create("sq_" + playerId, "夹具队-" + playerId, playerId, rules()), 0L);
    }
}
