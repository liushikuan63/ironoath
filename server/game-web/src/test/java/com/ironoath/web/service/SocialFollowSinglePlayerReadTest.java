package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：关注 / 取关一次请求里，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code follow} / {@code unfollow} 的上游 {@code requireFollowTarget}
 * 已经 {@code requirePlayer} 过（玩家不存在时在那里就抛了），而它们最后调的
 * {@code follows(playerId)} 开头又 {@code requirePlayer} 了一次 —— 两次之间只写社交集合、
 * <b>不写玩家档</b> ⇒ 第二次读是白搬一整份存档（资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合）。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这条判据读源码</b>：要断言「一次请求只读一次」得给 {@code SocialAppService} 装一整套
 * 依赖并计数仓储（三十余个构造依赖）。而这里要钉的事实是<b>调用关系</b>：
 * {@code follow} / {@code unfollow} 走的是不再读档的那条路，而公开的 {@code follows} 入口仍先确认玩家存在。
 *
 * <p>反证：把其中任何一处改回 {@code return follows(playerId);} ⇒ 计数立刻不足 ⇒ 判据红。
 */
class SocialFollowSinglePlayerReadTest {

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
    @DisplayName("关注与取关都不再回头读一次玩家档；公开的 follows 入口保持先确认玩家存在")
    void followPathsDoNotReadThePlayerDocumentTwice() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        int viaBuild = count(text, "return buildFollows(playerId);");
        assertThat(viaBuild)
                .as("follow 与 unfollow 必须走不再读档的那条路（follows 自身内部也算一处）")
                .isGreaterThanOrEqualTo(2);

        int viaFollows = count(text, "return follows(playerId);");
        assertThat(viaFollows)
                .as("follow / unfollow 不得再回头调会读档的 follows(playerId)")
                .isZero();

        assertThat(text)
                .as("公开的 follows 入口必须仍先确认玩家存在（客户端直接打开关注列时没有上游保证）")
                .contains("public FriendListView follows(String playerId) {\n        requirePlayer(playerId);");
    }

    private static int count(String text, String needle) {
        int total = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            total++;
            at = text.indexOf(needle, at + needle.length());
        }
        return total;
    }
}
