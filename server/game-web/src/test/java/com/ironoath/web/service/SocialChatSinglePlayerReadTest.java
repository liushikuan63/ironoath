package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：发一条聊天消息时，<b>发送者自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code POST /chat/send} 一次请求里读同一份玩家档多遍 ——
 * {@code ContentSecurityGuard.requireClean} 内部自己去读（只为拿一个 deviceId），
 * 而组装消息时 {@code nickname(playerId)} 又各读一遍（只为拿一个昵称）。
 * 两次之间没有玩家档写入 ⇒ 后面几遍拖回来的整份存档（资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合）白搬。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>语义为何不变</b>：玩家不存在时，原来的 {@code requireClean} 拿到的是 {@code null} 的 deviceId
 * ⇒ 走「无 openid ⇒ 记 WARN 后放行」；新写法给的仍是 {@code null} ⇒ 同一条分支。
 * 昵称侧原本是 {@code .orElse(playerId)}（玩家不存在就把 id 当昵称）⇒ 新写法同样回退到 playerId。
 *
 * <p><b>为什么这条判据读源码</b>：{@code SocialAppService} 有三十余个构造依赖，
 * 端点级夹具还要登录态与社交数据；而这里要钉的事实是<b>调用关系</b>：
 * 这一次读取被三处复用，且方法内不再有第二次整档读。
 *
 * <p>反证：把送检改回 {@code requireClean(playerId, …)}、或把任一处昵称改回 {@code nickname(playerId)} ⇒ 判据立刻红。
 */
class SocialChatSinglePlayerReadTest {

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
    @DisplayName("发消息：玩家档只读一次，送检与两处昵称都用这一份")
    void chatSendReadsThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        assertThat(text)
                .as("chat/send 的送检必须用已读到的那份存档的 deviceId")
                .contains("contentSecurity.requireCleanForAccount(self == null ? null : self.deviceId(),");

        assertThat(count(text, "playerId, (self == null ? playerId : self.nickName()), content, now"))
                .as("sendChat 里的两处消息构造都必须复用这一份昵称")
                .isEqualTo(2);

        assertThat(count(text, "playerId, nickname(playerId), content, now"))
                .as("只剩 sendChat 之外那处（:1978，另一个方法）可以自己读档")
                .isEqualTo(1);
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
