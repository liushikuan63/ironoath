package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：挑战关卡（{@code POST /stage/challenge}）写前那几次整档读，<b>能不能合成一次</b>。
 *
 * <p><b>缺陷形状</b>：解锁判定读一次整份存档（只要 {@code cityLevel}），
 * 体力判定又读一次（只要体力余额），而两次之间**没有任何玩家档写入** ⇒ 第二次读白搬整篇。
 *
 * <p><b>为什么这次能省</b>：两处要的是同一份存档里的两个字段 ⇒ 读一次即可。
 * ⚠ 缺档口径照抄原样：解锁判定把「读不到档」当 <b>1 级</b>（改之前是 {@code orElse(1)}）。
 *
 * <p><b>没动的</b>：扣体力（{@code wallet.deduct}）、败北打标、发奖（{@code wallet.grant}）
 * 都要整篇写回玩家档 ⇒ 传旧档会回滚并发写手的字段 ⇒ 那几处仍然自己新鲜读；
 * 战斗装配（{@code execute}）也仍然自己读 —— 它是 public 入口、有 5 个测试调用点，
 * 改它要加重载并同时改另一格判据，收益（再省一次）与改动面不成比例。
 *
 * <p>反证：把解锁判定改回「自己再读一遍」⇒ 判据立刻红。
 */
class StageChallengePlayerReadCountTest {

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
    @DisplayName("挑战关卡：解锁判定与体力判定共用一次整档读")
    void challengeReadsThePlayerDocumentOnceBeforeTheWrites() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/StageAppService.java");
        String text = Files.readString(source);

        int dstart = text.indexOf("private ChallengeStageResp doChallenge(");
        assertThat(dstart).as("必须找到 doChallenge").isGreaterThan(0);
        int dend = text.indexOf("\n    }", dstart);
        String dbody = text.substring(dstart, dend < 0 ? text.length() : dend);

        // ⚠ 这里**不钉**「doChallenge 里读了几次」：那是实现计数，会随别处的读增减而假红。
        // 本判据只钉**语义**：解锁判定与体力判定都不得自己再读整份档案（两处原本各读一次）。

        int ustart = text.indexOf("private void requireUnlocked(");
        assertThat(ustart).as("必须找到 requireUnlocked").isGreaterThan(0);
        int uend = text.indexOf("\n    }", ustart);
        String ubody = text.substring(ustart, uend < 0 ? text.length() : uend);
        assertThat(ubody)
                .as("解锁判定不得自己再读整份档案")
                .doesNotContain("findByPlayerId");
        assertThat(ubody)
                .as("它收的是调用方已经读到的等级（缺档按 1 级由调用方给）")
                .contains("int cityLevel");

        int sstart = text.indexOf("private void requireStamina(");
        assertThat(sstart).as("必须找到 requireStamina").isGreaterThan(0);
        int send = text.indexOf("\n    }", sstart);
        String sbody = text.substring(sstart, send < 0 ? text.length() : send);
        // ⚠ 这里不能断言「体内没有 findByPlayerId」：体力读在 wallet.available 内部，
        // 方法体里本来就没有那个调用 ⇒ 那条断言没有区分力。改为钉**它用的是哪一份**。
        assertThat(sbody)
                .as("体力判定必须用调用方已经读到的那份存档")
                .contains("wallet.available(knownSave,");
        assertThat(sbody)
                .as("它收的是调用方已经读到的那份存档")
                .contains("PlayerSave knownSave");
    }
}
