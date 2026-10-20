package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：出征（{@code POST /world/march}）这条路上，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：闭城判定读一次整份存档（只要 {@code pvp().closedUntil()}），
 * 行军装配又读一次（只要 {@code tech()}），而两次之间<b>没有任何写</b>
 * ⇒ 第二次读把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 *
 * <p><b>为什么这次能省</b>：两处要的是同一份存档里的两个字段。
 * ⚠ <b>共享读必须放在 {@code processDue} 之后</b>：它在行军到点时会写玩家档，
 * 读在前面就拿到结算前的旧快照。判据把这条顺序钉住了。
 * ⚠ 集结那条路读的是<b>发起人</b>（多半不是请求人）且锁语义不同 ⇒ 那里传 null，仍自己读。
 *
 * <p>反证：把闭城判定改回「自己再读一遍」⇒ 判据立刻红。
 */
class MarchSendSinglePlayerReadTest {

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
    @DisplayName("出征：玩家档只读一遍，闭城判定与行军装配共用这一份")
    void marchSendReadsThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/MarchAppService.java");
        String text = Files.readString(source);

        int dstart = text.indexOf("private MarchResp doSend(");
        assertThat(dstart).as("必须找到 doSend").isGreaterThan(0);
        int dend = text.indexOf("\n    }", dstart);
        String dbody = text.substring(dstart, dend < 0 ? text.length() : dend);

        int reads = dbody.split("findByPlayerId", -1).length - 1;
        assertThat(reads).as("doSend 里玩家档只能读一次").isEqualTo(1);

        int settleAt = dbody.indexOf("processDue(");
        int readAt = dbody.indexOf("findByPlayerId");
        assertThat(settleAt).as("必须能看到 processDue").isGreaterThan(0);
        assertThat(readAt)
                .as("共享读必须放在 processDue 之后（它会写玩家档，读在前面是结算前的旧快照）")
                .isGreaterThan(settleAt);

        int gstart = text.indexOf("private void requireNotClosedForGather(");
        assertThat(gstart).as("必须找到 requireNotClosedForGather").isGreaterThan(0);
        int gend = text.indexOf("\n    }", gstart);
        String gbody = text.substring(gstart, gend < 0 ? text.length() : gend);
        assertThat(gbody)
                .as("闭城判定不得自己再读整份档案")
                .doesNotContain("findByPlayerId");

        int tstart = text.indexOf("private com.ironoath.core.player.PlayerTech techOf(");
        assertThat(tstart).as("必须找到 techOf").isGreaterThan(0);
        int tend = text.indexOf("\n    }", tstart);
        String tbody = text.substring(tstart, tend < 0 ? text.length() : tend);
        assertThat(tbody)
                .as("集结那条路（读发起人、锁语义不同）仍要能自己读 ⇒ null 分支必须保留")
                .contains("findByPlayerId");
        assertThat(tbody)
                .as("给定存档时用它，不再自己读")
                .contains("knownSave");
    }
}
