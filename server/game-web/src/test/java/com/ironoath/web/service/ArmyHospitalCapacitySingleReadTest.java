package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：军队总览（{@code GET /army/list}）这条路上，<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code CityAppService.withSettledCity} 已经把结算后的玩家档交到手上
 * （{@code snap.player()}），而 {@code hospitalCapacity} 为了算「科技那半的容量百分比」
 * 又自己去读了一遍整份存档（只为拿一个 tech 字段）。
 * 两次之间没有玩家档写入 ⇒ 后面那次是把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么这次能省</b>：战斗与关卡那几处调用（{@code MonsterBattleService}、
 * {@code PlayerCityBattleService}、{@code StageAppService}）手里**没有**玩家档 ⇒ 对外入口仍要自己读；
 * 但军队总览这条路的调用方手里有 ⇒ 拆出一个「已知存档」的版本给它。
 *
 * <p>反证：把已知存档那版的科技取值改回自己读档 ⇒ 判据立刻红。
 */
class ArmyHospitalCapacitySingleReadTest {

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
    @DisplayName("军队总览：医院容量复用已结算的那份存档，不为科技百分比再读一遍")
    void armyListReusesTheSettledPlayerDocument() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/ArmyAppService.java");
        String text = Files.readString(source);

        assertThat(text)
                .as("军队总览必须把已结算的存档传下去")
                .contains("hospitalCapacityFor(playerId, snap.city(), snap.player())");

        // 已知存档那版的方法体里不得再出现整档读
        int start = text.indexOf("private long hospitalCapacityFor(");
        assertThat(start).as("必须存在「已知存档」那版").isGreaterThan(0);
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start, end < 0 ? text.length() : end);
        assertThat(body)
                .as("已知存档那版不得自己再读整份存档")
                .doesNotContain("findByPlayerId");

        assertThat(text)
                .as("对外入口必须仍在（战斗与关卡那几处调用方手里没有存档）")
                .contains("public long hospitalCapacity(String playerId, CityState city)");
    }
}
