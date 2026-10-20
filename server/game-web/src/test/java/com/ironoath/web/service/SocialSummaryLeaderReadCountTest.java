package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：小队视图（{@code GET /social/summary} 里的 {@code toSquadView}）上，
 * <b>队长那份档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：成员行逐个读整份存档（每人一次），而 {@code memberCap} 又单独读了一遍
 * <b>队长</b>的存档 —— 队长必然在成员名单里（建队与转让都保留队长）
 * ⇒ 每一次摘要都把队长那份档读两遍，而两次之间没有任何写。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>口径为何不变</b>：循环里取等级用的是 {@code save == null ? 0 : save.cityLevel()}，
 * 与 {@code cityLevelOf} 的 {@code orElse(0)} 逐字同义；队长不在成员名单里时仍回落自己读。
 *
 * <p>反证：把 {@code memberCap} 的参数改回直接读队长 ⇒ 判据立刻红。
 */
class SocialSummaryLeaderReadCountTest {

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
    @DisplayName("小队视图：队长等级复用成员行那一次读，不再单独读一遍")
    void squadViewReadsTheLeaderDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        int start = text.indexOf("private SquadView toSquadView(");
        assertThat(start).as("必须找到 toSquadView").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);

        assertThat(body)
                .as("队长等级必须来自成员行那一次读")
                .contains("memberId.equals(squad.leaderId())")
                .contains("leaderCityLevel");

        assertThat(body)
                .as("memberCap 不得再直接读一遍队长")
                .doesNotContain("squad.memberCap(cityLevelOf(squad.leaderId())");

        // ⚠ 这里**不钉取值的字面量**：成员行后来改成了投影批读（#2247），
        // 字面量随之变化而语义没变；「取哪几列、缺档怎么兜底」由同族的
        // SocialSummaryProjectionReadTest 钉；这里只钉「队长等级来自成员行」。
        assertThat(body)
                .as("队长的等级必须来自成员行那一次读")
                .contains("memberCap(leaderCityLevel");
    }
}
