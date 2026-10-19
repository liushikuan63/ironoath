package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：建社交实体的三个请求里，<b>内容送检用的账号键是不是已经在手里</b>。
 *
 * <p><b>缺陷形状</b>：{@code ContentSecurityGuard.requireClean} 内部自己去读玩家档
 * （{@code players.findByPlayerId(playerId).map(PlayerSave::deviceId)}）只为拿一个
 * {@code deviceId}；而这三个请求（{@code POST /squad/create}、{@code POST /alliance/create}）
 * 在此之前**已经读过同一份存档**（{@code requirePlayer}）⇒ 同一份整档被搬回两次
 * （资源表、PVP 账本、科技、礼包弹窗账本、头像框集合全在里面）。
 *
 * <p><b>为什么这条判据读源码而不是跑端点</b>：{@code requireClean} 与
 * {@code requireCleanForAccount} 的<b>唯一差别</b>就是前者会读档、后者只收账号键
 * ⇒ 「这三个调用点不再走 requireClean」与「这三个请求只读一次档」是同一件事的两种说法，
 * 而后者要端点夹具（玩家 + 社交数据 + 计数代理）才能断言，前者一行文本即可钉住。
 * 反证也直接：把任何一处改回 {@code requireClean(playerId, …)}，本判据立刻红。
 *
 * <p>⚠ 路径不写死：从当前目录向上找含 {@code scripts/check.sh} 的目录当仓库根。
 */
class SocialCreateDeviceIdReuseTest {

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
    @DisplayName("建小队 / 建联盟的送检都复用已读到的 deviceId，不再整档读一次")
    void createPathsReuseTheAlreadyReadDeviceId() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/SocialAppService.java");
        String text = Files.readString(source);

        int reused = count(text, "contentSecurity.requireCleanForAccount(save.deviceId(),");
        assertThat(reused)
                .as("squad/create 与 alliance/create 的三处送检必须用已读到的那份档的 deviceId")
                .isGreaterThanOrEqualTo(3);

        // 其余 requireClean 调用点（没有 save 的那些，如 :1826）保持原样 —— 这里只钉住上面三处。
        assertThat(text)
                .as("这三处不能再退回 requireClean(playerId, …)（它会自己再读一次整档）")
                .contains("contentSecurity.requireCleanForAccount(save.deviceId(),");
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
