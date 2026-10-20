package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：创建小队/联盟的门槛结论（{@code GET /social/createPolicy}）这条路上，
 * <b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：方法开头 {@code requirePlayer} 已经读到整份存档（要给
 * {@code allianceCreateBlock} 与 {@code squadCreateBlock} 用），而算「金币够不够」时
 * 又走 {@code balanceOf(playerId, …)} ⇒ 那里再读一遍整份档案，只为拿一个余额。
 * 两次之间没有任何写 ⇒ 第二次读把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这次能省</b>：余额只是只读（{@code available} 不落库）⇒ 复用同一份即可。
 * ⚠ 写路径（{@code deduct}）不适用：它会整篇 {@code save}。
 *
 * <p>反证：把这一处改回「让钱包自己读」⇒ 判据立刻红。
 */
class SocialCreatePolicySinglePlayerReadTest {

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("scripts").resolve("check.sh"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 scripts/check.sh）");
    }

    @Test
    @DisplayName("创建门槛结论：金币余额复用已读到的存档，不再读第二遍")
    void createPolicyReadsThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        int start = text.indexOf("public SocialCreatePolicyResp createPolicy(");
        assertThat(start).as("必须找到 createPolicy").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);

        assertThat(body)
                .as("金币余额必须用已读到的存档")
                .contains("balanceOf(save, GOLD_RESOURCE_ID, now)");
        assertThat(body)
                .as("不得再让钱包自己读一遍档案")
                .doesNotContain("balanceOf(playerId, GOLD_RESOURCE_ID, now)");

        int bstart = text.indexOf("private long balanceOf(PlayerSave knownSave, String resourceType, long now) {");
        assertThat(bstart).as("必须有「已知存档」那版").isGreaterThan(0);
        int bend = text.indexOf("\n    }", bstart);
        String bbody = text.substring(bstart, bend < 0 ? text.length() : bend);
        assertThat(bbody)
                .as("那版不得自己再读档案")
                .doesNotContain("findByPlayerId");
    }
}
