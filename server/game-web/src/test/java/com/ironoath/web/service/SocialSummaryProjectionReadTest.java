package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：小队视图（{@code GET /social/summary} 里的 {@code toSquadView}）的成员行，
 * <b>是逐个点查整份档案，还是一次投影批读</b>。
 *
 * <p><b>缺陷形状</b>：成员行逐个 {@code findByPlayerId} —— 一支小队 N 个人就是 **N 趟整份存档读取**
 * （资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合全在里面），
 * 而这一列只要昵称 / 展示战力 / 最近登录 / 主城等级四项。
 * 同文件的联盟名单早就改成了 {@code players.findBriefs(page)} 一次批读（{@code allianceApplications}），
 * 小队这一列没跟上。
 *
 * <p><b>口径为何不变</b>：投影的四列与整档逐字同源 ——
 * {@code PlayerDocumentMapper.toDomain} 对 nickName / lastLoginAt / cityLevel 是直传，
 * {@code PlayerPower} 与 {@code PowerDoc} 是同构 record（displayPower 直传，缺档为 0），
 * 而 {@code PlayerBriefEquivalenceTest} / {@code PlayerBriefProjectionQueryTest} 钉住两者一致；
 * 读不到档的四条兜底也照抄原样（昵称「未知玩家」、战力 0、最近登录 now、等级 0）。
 *
 * <p>反证：把成员行改回逐个点查 ⇒ 判据立刻红。
 */
class SocialSummaryProjectionReadTest {

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
    @DisplayName("小队成员行：一次投影批读，不再逐个点查整份档案")
    void squadMemberRowsUseTheProjectedBatchRead() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        int start = text.indexOf("private SquadView toSquadView(");
        assertThat(start).as("必须找到 toSquadView").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);

        assertThat(body)
                .as("成员行必须走投影批读")
                .contains("players.findBriefs(memberIds)");

        assertThat(body)
                .as("成员行不得再逐个点查整份档案")
                .doesNotContain("players.findByPlayerId(");

        assertThat(body)
                .as("四条兜底必须照抄原口径")
                .contains("PlayerDtoMapper.UNKNOWN_PLAYER_NAME")
                .contains("brief == null ? 0L : brief.displayPower()")
                .contains("brief == null ? now : brief.lastLoginAt()")
                .contains("brief == null ? 0 : brief.cityLevel()");
    }
}
