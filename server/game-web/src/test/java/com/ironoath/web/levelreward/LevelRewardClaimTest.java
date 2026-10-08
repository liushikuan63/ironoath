package com.ironoath.web.levelreward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.LevelRewardCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.LevelRewardClaimReq;
import com.ironoath.web.dto.generated.LevelRewardClaimResp;
import com.ironoath.web.dto.generated.LevelRewardListResp;
import com.ironoath.web.dto.generated.LevelRewardRow;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：钉住等级奖励的领取链路（裁决②「点领取才发」与幂等）。
 * 依赖：Spring 上下文 + test profile 的内存存储（与 {@code RewardGrantIntegrationTest} 同一套夹具）。
 *
 * <p><b>为什么直接调 AppService 而不是 MockMvc</b>：本类要钉的四件事（幂等重放、重复领、未达级、
 * 表里没有的级）全是「返回值 / 抛什么码 / 存档变了没有」，与 HTTP 层无关；而 HTTP 层的形状由
 * 契约生成物与运行时量具钉（见 {@code tools/verify-level-reward-runtime.mjs}）。
 *
 * <p><b>入账数一律现读 {@code level_reward} 而不是抄字面量</b>：否则改一次表要改两处，
 * 而「改了表没改测试」的症状是测试仍然绿、玩家拿到的却是旧数 —— 本仓反复出现过的那类缺陷形状。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("等级奖励：三态视图 / 点领取才发 / requestId 幂等")
class LevelRewardClaimTest {

    @Autowired private LevelRewardAppService levelRewards;
    @Autowired private LevelRewardClaimStore claims;
    @Autowired private PlayerRepository players;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        claims.clear();
    }

    @Test
    @DisplayName("新号 1 级：40 行全在，只有 1 级可领，其余 39 行是 locked 而不是 claimed")
    void newPlayerSeesEveryLevelLockedExceptFirst() {
        String playerId = newPlayer("领取视图");

        LevelRewardListResp resp = levelRewards.list(playerId);

        assertThat(resp.rows()).hasSize(rowCountFromTable());
        assertThat(resp.mainLevel()).as("新号主城等级由服务端下发，客户端没有这一位").isEqualTo(1L);
        assertThat(resp.claimableCount()).as("1 级出生即达 ⇒ 恰好一行可领").isEqualTo(1);
        long locked = resp.rows().stream().filter(LevelRewardRow::locked).count();
        long claimed = resp.rows().stream().filter(LevelRewardRow::claimed).count();
        assertThat(locked).as("locked 与 claimable 是两回事，不能合并成一个「不可领」").isEqualTo(resp.rows().size() - 1);
        assertThat(claimed).isZero();
        assertThat(resp.rows().get(0).claimable()).isTrue();
        assertThat(resp.rows().get(0).locked()).isFalse();
        assertThat(resp.rows().get(1).claimable()).as("2 级还没到 ⇒ 不可领，但不是已领").isFalse();
        assertThat(resp.rows()).as("行序必须按等级升序（服务端排好，客户端不再排）")
                .isSortedAccordingTo((a, b) -> Long.compare(a.level(), b.level()));
    }

    @Test
    @DisplayName("读口不写任何状态：连点两次 list，账本还是空的")
    void listIsPureRead() {
        String playerId = newPlayer("纯读");
        LevelRewardListResp first = levelRewards.list(playerId);
        LevelRewardListResp second = levelRewards.list(playerId);

        // 只比「状态」那三位：serverNow 每次读都前进，把它拉进相等判据的话，
        // 这条用例会红在一次完全正常的读取上（本轮实测踩过：1791463793327 vs ...328）
        assertThat(second.rows()).as("list 不许有副作用：两次读的行内容必须一字不差").isEqualTo(first.rows());
        assertThat(second.claimableCount()).isEqualTo(first.claimableCount());
        assertThat(second.mainLevel()).isEqualTo(first.mainLevel());
        assertThat(claims.load(playerId))
                .as("读的时候不许顺手写账本 —— 写了就等于「打开面板就发奖」，而裁决②否掉的正是这个")
                .isEmpty();
    }

    @Test
    @DisplayName("点领取才入账：到账量等于表里那一行，中文名随下发，领完 claimable 归零")
    void claimPutsTableValuesIntoWalletAndMarksClaimed() {
        String playerId = newPlayer("入账");
        LevelRewardCfg row = rowOf(1L);
        Map<String, Long> before = balances(playerId);

        LevelRewardClaimResp resp = levelRewards.claim(playerId, req(1L));

        assertThat(resp.rewards()).as("一行的三列都要出现在明细里（木/石/金币）").hasSize(3);
        assertThat(balance(playerId, "WOOD")).isEqualTo(before.get("WOOD") + row.rewardWood());
        assertThat(balance(playerId, "STONE")).isEqualTo(before.get("STONE") + row.rewardStone());
        assertThat(balance(playerId, "GOLD")).isEqualTo(before.get("GOLD") + row.rewardGold());
        assertThat(resp.rewards())
                .as("屏上不许出现裸 id：明细里每一项都得带服务端解析出来的中文名")
                .allSatisfy(item -> {
                    assertThat(item.name()).isNotBlank();
                    assertThat(item.name()).isNotIn("WOOD", "STONE", "GOLD", "RESOURCE");
                    assertThat(item.name()).as("名字必须是 resource 表里那一列，不是内部码")
                            .isNotEqualTo(item.id())
                            .isEqualTo(configs.getResource(item.id()).name());
                    // type 是裸 String（生成器跨文件 $ref 的已知偏离），所以枚举域必须在这里钉：
                    // 客户端把它 cast 成 RewardType，值不在域内就是静默 undefined
                    assertThat(com.ironoath.core.reward.RewardType.valueOf(item.type()))
                            .isEqualTo(com.ironoath.core.reward.RewardType.RESOURCE);
                });
        assertThat(resp.claimableCount()).as("领完这一级就没有可领的了").isZero();
        assertThat(claims.load(playerId).orElseThrow().claimedLevels())
                .as("账本记下这一级").isEqualTo(List.of(1L));

        LevelRewardRow after = levelRewards.list(playerId).rows().get(0);
        assertThat(after.claimed()).isTrue();
        assertThat(after.claimable()).isFalse();
    }

    @Test
    @DisplayName("同一 requestId 重放只发一份：第二次被幂等键挡掉，余额一字不动")
    void sameRequestIdReplayGrantsOnlyOnce() {
        String playerId = newPlayer("重放");
        LevelRewardClaimReq replay = req(1L);
        long woodBefore = balance(playerId, "WOOD");

        levelRewards.claim(playerId, replay);
        long woodAfterFirst = balance(playerId, "WOOD");

        assertThatThrownBy(() -> levelRewards.claim(playerId, replay))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(balance(playerId, "WOOD")).as("重放不得二次入账").isEqualTo(woodAfterFirst);
        assertThat(woodAfterFirst).isGreaterThan(woodBefore);
    }

    @Test
    @DisplayName("换 requestId 再领同一级：回「已经领过」，余额不动（幂等键不是资格）")
    void reclaimSameLevelWithFreshRequestIdIsRejected() {
        String playerId = newPlayer("重复领");
        levelRewards.claim(playerId, req(1L));
        long woodAfterFirst = balance(playerId, "WOOD");

        assertThatThrownBy(() -> levelRewards.claim(playerId, req(1L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.LEVEL_REWARD_ALREADY_CLAIMED);
        assertThat(balance(playerId, "WOOD")).isEqualTo(woodAfterFirst);
    }

    @Test
    @DisplayName("缺 requestId 直接拒，且不许留下半个账本条目")
    void missingRequestIdIsRejectedBeforeAnyStateChange() {
        String playerId = newPlayer("缺键");

        assertThatThrownBy(() -> levelRewards.claim(playerId, new LevelRewardClaimReq("  ", 1L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING);
        assertThat(claims.load(playerId)).as("失败方向不许是「标记了已领但没发出去」以外的形态").isEmpty();
        assertThat(levelRewards.list(playerId).rows().get(0).claimable()).isTrue();
    }

    @Test
    @DisplayName("未达等级：回 3015 且不记账，等真升上来还能领")
    void claimUnreachedLevelIsRejectedAndDoesNotBurnTheLedger() {
        String playerId = newPlayer("未到级");

        assertThatThrownBy(() -> levelRewards.claim(playerId, req(31L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.LEVEL_REWARD_NOT_REACHED);
        assertThat(claims.load(playerId)).isEmpty();

        setMainLevel(playerId, 31);
        LevelRewardClaimResp resp = levelRewards.claim(playerId, req(31L));
        assertThat(resp.level()).isEqualTo(31L);
    }

    @Test
    @DisplayName("表里没有的等级回 3014（客户端乱点或表缩了都会走到这里）")
    void claimLevelOutsideTableIsRejected() {
        String playerId = newPlayer("表外");
        setMainLevel(playerId, 40);

        assertThatThrownBy(() -> levelRewards.claim(playerId, req(rowCountFromTable() + 1L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.LEVEL_REWARD_NOT_FOUND);
    }

    @Test
    @DisplayName("高段奖励超过仓容时：入账被上限截断，而明细回的是「真发出去的那份」")
    void highSegmentClaimIsCappedAndRespReportsActualGranted() {
        String playerId = newPlayer("高段截断");
        LevelRewardCfg row = rowOf(31L);
        long cap = players.findByPlayerId(playerId).orElseThrow().resource("WOOD").cap();
        setMainLevel(playerId, 31);
        assertThat(row.rewardWood())
                .as("这条用例的前提是 31 级的木奖励确实超过仓容（现读：奖励 %s、上限 %s）", row.rewardWood(), cap)
                .isGreaterThan(cap);

        LevelRewardClaimResp resp = levelRewards.claim(playerId, req(31L));

        assertThat(balance(playerId, "WOOD")).as("仓库存不下就停在容量上，不许凭空多出来").isEqualTo(cap);
        assertThat(resp.rewards()).as("回给客户端的必须是实入账，不是表面值 —— 否则飘字说 +97440 而资源条只涨到上限")
                .anySatisfy(item -> {
                    assertThat(item.id()).isEqualTo("WOOD");
                    assertThat(item.count()).isLessThan(row.rewardWood());
                    assertThat(item.count()).isGreaterThan(0L);
                });
        assertThat(claims.load(playerId)).as("截断不算失败：这一级就是领过了，缺的部分由邮件补").get()
                .extracting(LevelRewardClaimStore.State::claimedLevels).isEqualTo(List.of(31L));
    }

    @Test
    @DisplayName("红点数与列表里 claimable=true 的行数同源（不许各算一遍）")
    void claimableCountMatchesTheRowMarks() {
        String playerId = newPlayer("同源");
        setMainLevel(playerId, 12);
        levelRewards.claim(playerId, req(1L));
        levelRewards.claim(playerId, req(2L));

        LevelRewardListResp resp = levelRewards.list(playerId);
        long marked = resp.rows().stream().filter(LevelRewardRow::claimable).count();

        assertThat((long) resp.claimableCount()).isEqualTo(marked);
        assertThat(levelRewards.claimableCount(playerId)).as("单独问红点必须与列表同一读数")
                .isEqualTo(resp.claimableCount());
        assertThat(marked).as("12 级里领掉 2 级 ⇒ 还剩 10 级可领").isEqualTo(10L);
    }

    // ---------- 内部 ----------

    private String newPlayer(String tag) {
        return playerInitService.init(new com.ironoath.web.dto.generated.PlayerInitReq(
                "req-" + tag + "-" + UUID.randomUUID(), "dev-" + tag + "-" + UUID.randomUUID(),
                tag, 1_700_000_000_000L, "")).playerId();
    }

    private static LevelRewardClaimReq req(long level) {
        return new LevelRewardClaimReq("claim-" + UUID.randomUUID(), level);
    }

    private void setMainLevel(String playerId, int level) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(level);
        players.save(save);
    }

    private int rowCountFromTable() {
        return configs.all(LevelRewardCfg.class).size();
    }

    private LevelRewardCfg rowOf(long level) {
        return configs.all(LevelRewardCfg.class).stream().filter(r -> r.level() == level)
                .findFirst().orElseThrow(() -> new AssertionError("表里没有 level=" + level));
    }

    private long balance(String playerId, String resource) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resource).current();
    }

    private Map<String, Long> balances(String playerId) {
        return Map.of("WOOD", balance(playerId, "WOOD"),
                "STONE", balance(playerId, "STONE"),
                "GOLD", balance(playerId, "GOLD"));
    }
}
