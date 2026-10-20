package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：商店这条路上（{@code GET /shop/list} 与 {@code POST /shop/buy}），<b>玩家自己的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：列表里算金币余额要读一次整份存档（{@code PlayerWallet.available}），
 * 取主城等级又读一次（{@code cityLevelOf}）；购买时还多一处「是否已拥有该头像框」的整档读。
 * 这些读取之间**没有玩家档写入** ⇒ 后面几次是把资源表 / PVP 账本 / 科技 / 礼包弹窗账本 / 头像框集合白搬回来。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>哪些读是必要的、不能省</b>：扣款前（{@code wallet.deduct} 会整篇 {@code save}）、
 * 扣款后的余额读、失败分支的读 —— 传旧档进去会回滚并发写手的字段（Mongo 的 save 是宽字段 {@code $set}）。
 * 判据因此只钉「只读那几处共用一份」，不碰写路径。
 *
 * <p>反证：把列表里的余额读改回「让钱包自己读」⇒ 判据立刻红。
 */
class ShopSinglePlayerReadTest {

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
    @DisplayName("商店：列表与购买各只读一遍玩家档，余额/等级/头像框共用这一份")
    void shopPathsReadThePlayerDocumentOnce() throws Exception {
        Path source = repoRoot().resolve(
                "server/game-web/src/main/java/com/ironoath/web/service/ShopAppService.java");
        String text = Files.readString(source);

        assertThat(text)
                .as("钱包必须按具体类注入，才能调那个「已知存档」的只读重载")
                .contains("PlayerWallet wallet;");

        assertThat(text)
                .as("金币余额那一支必须用已知存档")
                .contains("wallet.available(knownSave, ResourceIds.GOLD, now)");

        int cstart = text.indexOf("private int cityLevelOf(PlayerSave knownSave) {");
        assertThat(cstart).as("cityLevelOf 必须吃存档").isGreaterThan(0);
        assertThat(text.substring(cstart, cstart + 300))
                .as("cityLevelOf 不得自己再读档案")
                .doesNotContain("findByPlayerId");

        int astart = text.indexOf("private boolean alreadyOwnsFrame(PlayerSave knownSave, String frameId) {");
        assertThat(astart).as("alreadyOwnsFrame 必须吃存档").isGreaterThan(0);
        assertThat(text.substring(astart, astart + 300))
                .as("alreadyOwnsFrame 不得自己再读档案")
                .doesNotContain("findByPlayerId");

        int lstart = text.indexOf("public ShopListResp list(String playerId, ShopCurrency currency) {");
        assertThat(lstart).as("必须找到 list").isGreaterThan(0);
        String lbody = text.substring(lstart, text.indexOf("\n    }", lstart));
        assertThat(lbody)
                .as("列表必须把已读到的存档喂给余额与等级")
                .contains("balanceOf(playerId, currency, now, save)")
                .contains("cityLevelOf(save)");

        int bstart = text.indexOf("public ShopBuyResp buy(");
        if (bstart < 0) {
            bstart = text.indexOf(" buy(");
        }
        assertThat(bstart).as("必须找到 buy").isGreaterThan(0);
        String bbody = text.substring(bstart, text.indexOf("\n    }", bstart));
        assertThat(bbody)
                .as("购买必须把已读到的存档喂给等级/余额/头像框三处")
                .contains("cityLevelOf(save)")
                .contains("balanceOf(playerId, req.currency(), now, save)")
                .contains("alreadyOwnsFrame(save, row.frameId())");

        assertThat(bbody)
                .as("扣款后那次余额读必须新读（传 null ⇒ 自己读）")
                .contains("balanceOf(playerId, req.currency(), now, null)");
    }
}
