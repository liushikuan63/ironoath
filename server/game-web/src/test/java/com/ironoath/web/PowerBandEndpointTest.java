package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.PowerBandGuard;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SearchTargetsReq;
import com.ironoath.web.power.MatchPool;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：B08 战力圈层的端到端验证 —— 验收 3（峰值记忆）、验收 12（不下发精确距离）、
 * 验收 13（服务端独立校验，客户端零校验）。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储 + JVM 内锁）。
 *
 * <p><b>为什么这一层非走 HTTP 不可</b>：验收 13 的原文是「把客户端校验逻辑全部删掉，
 * 服务端仍能正确拒绝」。服务层单测证明不了这一点 —— 它调用的是 Java 方法，
 * 而真实的攻击请求是一个不带任何客户端诚意的 HTTP 报文。
 * 只有从 MVC 走一遍，才能证明拒绝是服务端自己算出来的，
 * 而不是「客户端已经过滤过了所以服务端没遇到坏输入」。
 *
 * <p>验收 12 的原文判定方式是「抓包」。这里用 MockMvc 取到<b>真实的响应字节</b>再解析 JSON，
 * 与抓包等价：断言的是序列化之后的字段名，不是 Java record 的字段名 ——
 * 后者会因为一个 {@code @JsonProperty} 或一次 record 重命名而与线上报文脱节。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PowerBandEndpointTest {

    private static final String SEARCH_URL = "/world/searchTargets";
    private static final String MARCH_URL = "/world/march";
    private static final String POWER_URL = "/player/power";
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PowerService powerService;
    @Autowired private PowerRefreshService powerRefreshService;
    @Autowired private MatchPool matchPool;

    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private com.ironoath.core.bag.InventoryRepository inventories;
    @Autowired private ArmyRepository armies;
    @Autowired private com.ironoath.core.hero.HeroRepository heroes;
    @Autowired private WorldRepository world;
    @Autowired private MarchRepository marches;
    @Autowired private MarchDueQueue dueQueue;
    @Autowired private com.ironoath.core.gacha.GachaStateRepository gachaStates;
    @Autowired private com.ironoath.core.gacha.GachaLogStore gachaLogs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryHeroStore) heroes).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        ((InMemoryGachaStateStore) gachaStates).clear();
        ((InMemoryGachaLogStore) gachaLogs).clear();
        // 匹配池是进程内缓存，跨用例残留会让「守方战力读存档」这条路径被悄悄替换成读缓存
        matchPool.clear();
    }

    // ---------- 验收 12：响应里没有精确距离 ----------

    @Test
    @DisplayName("验收12：搜索响应的字段名里没有任何距离数值，只有 distanceBand 三档")
    void searchResponseCarriesNoExactDistance() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        liftProtection(self);
        long selfPower = powerRefreshService.refresh(self).matchPower();
        // 必须显式设定候选的匹配战力：新号只有 INIT_MATCH_POWER，
        // 而自己带了 100 兵，两者差一个数量级，不设定的话候选会全部落在圈层之外
        setMatchPower(newPlayerAt(260, 256, "同段位对手", 1.0d, true), selfPower);
        setMatchPower(newPlayerAt(280, 256, "远一点的同段位", 1.0d, true), scale(selfPower, "1.2"));
        setMatchPower(newPlayerAt(370, 256, "半径边缘", 1.0d, true), scale(selfPower, "0.9"));

        JsonNode data = post200(SEARCH_URL, self, new SearchTargetsReq(128, 50));
        JsonNode targets = data.get("targets");
        assertThat(targets).as("半径内应当搜到目标").isNotEmpty();

        Set<String> fieldNames = collectFieldNames(targets);
        assertThat(fieldNames)
                .as("搜索结果的字段名必须与契约完全一致；多出一个字段就意味着多下发了一份情报")
                .containsExactlyInAnyOrder("id", "name", "coord", "matchPower", "powerRatio",
                        "distanceBand", "resourceHint", "isShielded", "tyrannyLevel", "x", "y");
        assertThat(fieldNames)
                .as("响应体中不得出现任何距离数值字段（验收 12 的原文判定）")
                .noneMatch(name -> !name.equals("distanceBand") && name.toLowerCase().contains("dist"));
        assertThat(fieldNames)
                .as("资源也只给档位，不给数字：精确库存是侦查才该拿到的情报")
                .noneMatch(name -> name.toLowerCase().contains("resource") && !name.equals("resourceHint"));

        for (JsonNode target : targets) {
            assertThat(target.get("distanceBand").asText())
                    .as("距离只能是三档之一").isIn("NEAR", "MID", "FAR");
            assertThat(target.get("resourceHint").asText()).isIn("RICH", "NORMAL", "POOR");
            assertThat(target.get("isShielded").booleanValue())
                    .as("护盾目标不进候选池，所以搜到的一定不是护盾状态").isFalse();
        }
        // 370 距离自己 114 格，落在 60% × 128 = 77 之外，必须是 FAR
        assertThat(bandOf(targets, "半径边缘"))
                .as("114 格 / 半径 128 应当是 FAR").isEqualTo("FAR");
        assertThat(bandOf(targets, "同段位对手")).as("4 格应当是 NEAR").isEqualTo("NEAR");
    }

    // ---------- 验收 13：服务端独立校验 ----------

    @Test
    @DisplayName("验收13：客户端不做任何校验，直接发一个 2.1x 目标的攻击请求，服务端自己拒绝")
    void serverRejectsOutOfBandAttackWithoutAnyClientSideCheck() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        liftProtection(self);
        long selfPower = powerRefreshService.refresh(self).matchPower();
        // 2.1 倍正好在区间外一点：验收 1 要求的边界，也是最容易被「四舍五入放行」的地方
        String tooStrong = newPlayerAt(262, 256, "两倍强的对手", 1.0d, true);
        setMatchPower(tooStrong, scale(selfPower, "2.1"));

        JsonNode root = postRoot(MARCH_URL, self, new MarchReq(newRequestId(), 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        assertThat(root.get("code").asInt())
                .as("服务端必须自己算出这个目标超出圈层并拒绝，实际响应=%s", root)
                .isEqualTo(com.ironoath.common.ErrorCode.MARCH_POWER_OUT_OF_BAND.code());
        assertThat(root.get("detail").asText())
                .as("拒绝必须带明确文案（Result.detail），绝不静默失败；msg 只是错误码的通用文案")
                .contains("对方实力远超于你");
        assertThat(marches.activeCountOf(self)).as("被拒绝时不能留下行军").isZero();
        assertThat(armies.findByPlayerId(self).orElseThrow().countOf(UNIT))
                .as("被拒绝时不能扣兵").isEqualTo(100L);
    }

    @Test
    @DisplayName("区间内的目标能通过圈层闸门并成功出征，证明闸门没有过度拒绝")
    void inBandTargetPassesTheGuardAndTheMarchDeparts() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        liftProtection(self);
        long selfPower = powerRefreshService.refresh(self).matchPower();
        String inBand = newPlayerAt(262, 256, "区间内对手", 1.0d, true);
        setMatchPower(inBand, scale(selfPower, "1.5"));
        // 给守方 1 个兵：玩家城 PVP 已接通，出征预检会拒绝打空城
        giveTroops(inBand, 1L);

        JsonNode root = postRoot(MARCH_URL, self, new MarchReq(newRequestId(), 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        // 圈层放行了 ⇒ 行军真的出发。
        // 这条断言的价值在于它证明上一条例子不是因为「ATTACK 一律被拒」而通过的
        assertThat(root.get("code").asInt())
                .as("区间内的目标必须过闸门并成功出征，实际响应=%s", root)
                .isZero();
        assertThat(marches.activeCountOf(self)).as("放行就该留下一支在途行军").isEqualTo(1);
    }

    @Test
    @DisplayName("0.5x 与 2.0x 都放行，0.49x 与 2.01x 都拒绝：闭区间在 HTTP 层同样成立")
    void bandBoundariesHoldOverHttp() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 1000L);
        liftProtection(self);
        // 战力基线由 assertBandBoundary 自己逐次重算（见该方法的注释），
        // 这里不再预先算一份传进去 —— 传进去的那一份会让四次探测彼此耦合
        assertBandBoundary(self, "2.00", true);
        assertBandBoundary(self, "0.50", true);
        assertBandBoundary(self, "2.01", false);
        assertBandBoundary(self, "0.49", false);
    }

    // ---------- 验收 3：峰值记忆 ----------

    @Test
    @DisplayName("验收3：卸掉全部兵后 matchPower 仍约为峰值的 80%，2.1x 对手依然被拒绝")
    void disarmingDoesNotLowerMatchPowerBelowPeakMemory() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 1000L);
        liftProtection(self);
        long peak = powerRefreshService.refresh(self).matchPower();

        // 卸兵：直接把兵力清零。这是「压分刷小号」的第一步
        removeAllTroops(self);
        PlayerPower after = powerRefreshService.refresh(self).power();
        long expected = FixedPoint.round(FixedPoint.mul(FixedPoint.of(peak),
                configs.fixedParam("PEAK_POWER_MEMORY_RATIO")));
        assertThat(after.matchPower())
                .as("卸兵后匹配战力必须停在峰值 × 0.8，否则压分立刻有收益")
                .isEqualTo(expected);
        assertThat(after.peakPower()).as("峰值本身不因卸兵而下降").isEqualTo(peak);

        // 峰值的 2.1 倍对手：卸兵前打不了，卸兵后依然打不了
        String tooStrong = newPlayerAt(262, 256, "两倍强的对手", 1.0d, true);
        setMatchPower(tooStrong, scale(peak, "2.1"));
        JsonNode root = postRoot(MARCH_URL, self, new MarchReq(newRequestId(), 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        assertThat(root.get("code").asInt())
                .as("卸兵之后 2.1x 对手依然要被拒绝，实际响应=%s", root)
                .isEqualTo(com.ironoath.common.ErrorCode.MARCH_POWER_OUT_OF_BAND.code());
    }

    // ---------- 搜索候选池 ----------

    @Test
    @DisplayName("搜索候选池排除：超出圈层、护盾中、半径外、以及自己")
    void searchExcludesOutOfBandShieldedFarawayAndSelf() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 1000L);
        liftProtection(self);
        long selfPower = powerRefreshService.refresh(self).matchPower();

        String inBand = newPlayerAt(262, 256, "区间内", 1.0d, true);
        setMatchPower(inBand, selfPower);
        String tooStrong = newPlayerAt(264, 256, "太强", 1.0d, true);
        setMatchPower(tooStrong, scale(selfPower, "2.1"));
        String tooWeak = newPlayerAt(266, 256, "太弱", 1.0d, true);
        setMatchPower(tooWeak, scale(selfPower, "0.3"));
        // 护盾中的玩家：战力完全合法，但仍在保护期内
        String shielded = newPlayerAt(268, 256, "新手保护中", 1.0d, false);
        setMatchPower(shielded, selfPower);
        // 半径外：129 格 > SEARCH_MAX_RADIUS 的截断值 128
        String faraway = newPlayerAt(400, 400, "半径外", 1.0d, true);
        setMatchPower(faraway, selfPower);

        JsonNode data = post200(SEARCH_URL, self, new SearchTargetsReq(128, 50));
        List<String> ids = names(data.get("targets"));
        assertThat(ids).containsExactly("区间内");
        assertThat(data.get("selfMatchPower").asLong()).isEqualTo(selfPower);
        assertThat(data.get("bandLower").asLong())
                .as("响应要照实下发自己当前的区间，玩家才知道为什么这些人搜不到")
                .isEqualTo(FixedPoint.round(FixedPoint.mul(FixedPoint.of(selfPower),
                        configs.fixedParam("PVP_POWER_MIN_RATIO"))));
        assertThat(data.get("bandUpper").asLong())
                .isEqualTo(FixedPoint.round(FixedPoint.mul(FixedPoint.of(selfPower),
                        configs.fixedParam("PVP_POWER_MAX_RATIO"))));
    }

    @Test
    @DisplayName("B08 §1：战力变更由事件驱动更新匹配池，没有任何轮询")
    void powerChangeIsPublishedAndMatchPoolSubscribes() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        liftProtection(self);
        assertThat(matchPool.find(self)).as("还没有任何写操作，池里不该有他").isEmpty();

        long matchPower = powerRefreshService.refresh(self).matchPower();
        assertThat(matchPool.find(self))
                .as("PowerChangedEvent 必须被 MatchPool 订阅到（B08 §1 禁止轮询）")
                .isPresent();
        assertThat(matchPool.find(self).orElseThrow().matchPower()).isEqualTo(matchPower);
        assertThat(matchPool.matchPowerOr(self, -1L)).isEqualTo(matchPower);
        assertThat(matchPool.matchPowerOr("不存在的玩家", -1L))
                .as("池里没有的人必须回落到调用方给的值，而不是悄悄返回 0")
                .isEqualTo(-1L);
    }

    // ---------- B08 §1：明细面板 ----------

    @Test
    @DisplayName("B08 §1：战力明细五项之和精确等于展示战力，且 matchPower 能被玩家自己复算出来")
    void powerDetailPanelIsSelfExplaining() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        liftProtection(self);

        JsonNode data = get200(POWER_URL, self);
        JsonNode breakdown = data.get("breakdown");
        long sum = breakdown.get("building").asLong()
                + breakdown.get("troops").asLong()
                + breakdown.get("heroes").asLong()
                + breakdown.get("tech").asLong()
                + breakdown.get("equipment").asLong();
        assertThat(sum)
                .as("明细逐项相加必须等于展示战力：对不上是「这游戏在骗我」最直接的证据，"
                        + "比任何数值不平衡都伤信任")
                .isEqualTo(data.get("power").get("displayPower").asLong());
        assertThat(breakdown.get("troops").asLong()).as("带了 100 兵，部队战力必须为正").isPositive();

        // matchPower = max(当前实际战力, 峰值 × 记忆比率)。两个输入都下发了，
        // 所以玩家可以自己验算 —— 这是「规则可自查」与「规则需要相信」的区别
        long current = data.get("currentMatchPower").asLong();
        long floor = data.get("peakMemoryFloor").asLong();
        assertThat(data.get("power").get("matchPower").asLong())
                .as("matchPower 必须等于两个输入的最大值，否则面板上的公式就是假的")
                .isEqualTo(Math.max(current, floor));
        assertThat(floor)
                .as("峰值记忆托底线 = round(峰值 × PEAK_POWER_MEMORY_RATIO)")
                .isEqualTo(FixedPoint.round(FixedPoint.mul(
                        FixedPoint.of(data.get("power").get("peakPower").asLong()),
                        configs.fixedParam("PEAK_POWER_MEMORY_RATIO"))));
        assertThat(data.get("serverNow").asLong())
                .as("客户端不得用自己的时钟推算峰值衰减，所以必须下发服务端时刻")
                .isPositive();
    }

    @Test
    @DisplayName("卸兵后明细面板照实显示「部队战力掉了，但匹配战力被峰值托住」")
    void powerDetailPanelExplainsPeakMemoryAfterDisarming() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 1000L);
        liftProtection(self);
        long peak = get200(POWER_URL, self).get("power").get("matchPower").asLong();

        removeAllTroops(self);
        JsonNode data = get200(POWER_URL, self);
        assertThat(data.get("breakdown").get("troops").asLong()).as("兵卸光了").isZero();
        assertThat(data.get("currentMatchPower").asLong())
                .as("当前实际战力确实掉下来了").isLessThan(peak);
        assertThat(data.get("power").get("matchPower").asLong())
                .as("但匹配战力被峰值记忆托住，所以压分没有收益（验收 3）")
                .isEqualTo(data.get("peakMemoryFloor").asLong());
        assertThat(data.get("power").get("peakPower").asLong()).isEqualTo(peak);
    }

    // ---------- 夹具 ----------

    private void assertBandBoundary(String self, String ratio, boolean allowed)
            throws Exception {
        // **每次探测都重算一遍自己的战力**：玩家城 PVP 接通之后，放行的那几次探测会真的派兵出发，
        // 于是攻方城内兵力一路减少、战力跟着漂。四个探测共用一个开头算好的 selfPower 时，
        // 漂移到第四次（0.49x）刚好把 2.04 的比值推到 2.00 的边界上，本该拒绝的变成放行 ——
        // 而这不是圈层的 bug，是夹具让四次探测彼此耦合了。
        // 重算之后每次探测都是自洽的一对（纯计算层与 HTTP 层看到同一个 selfPower）
        long selfPower = powerRefreshService.refresh(self).matchPower();
        long targetPower = scale(selfPower, ratio);
        // 用当前时间戳拼出唯一坐标，避免同一条用例里的多个目标重叠
        int x = 256 + 20 + Math.abs(ratio.hashCode()) % 30;
        String targetId = newPlayerAt(x, 256, "边界" + ratio, 1.0d, true);
        setMatchPower(targetId, targetPower);
        // 给守方 1 个兵：玩家城 PVP 已接通，出征预检会拒绝打空城，
        // 而这条用例要验的是圈层闸门，不该被「守军为空」这道门挡住
        giveTroops(targetId, 1L);

        // 先断言纯计算层的判定，再断言 HTTP 层给出同一个答案：
        // 两者不一致就意味着有一条绕过 PowerBandGuard 的路径（B08 禁止项）
        PowerBandGuard.BandCheckResult expected = PowerBandGuard.check(
                selfPower, targetPower, 1, powerService.bandRules());
        assertThat(expected.allowed())
                .as("倍率 %s 的纯计算判定应当是 %s", ratio, allowed).isEqualTo(allowed);

        JsonNode root = postRoot(MARCH_URL, self, new MarchReq(newRequestId(), x, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        int code = root.get("code").asInt();
        if (allowed) {
            // 放行的信号曾经是 NOT_IMPLEMENTED（战斗结算未交付），
            // 现在玩家城 PVP 已接通，所以放行就意味着行军真的出发了
            assertThat(code)
                    .as("倍率 %s 应当放行到战斗结算并成功出征，实际响应=%s", ratio, root)
                    .isZero();
        } else {
            assertThat(code)
                    .as("倍率 %s 应当被圈层拒绝，实际响应=%s", ratio, root)
                    .isEqualTo(com.ironoath.common.ErrorCode.MARCH_POWER_OUT_OF_BAND.code());
        }
    }

    /** 递归收集一段 JSON 里出现过的全部字段名（含嵌套对象）。 */
    private static Set<String> collectFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collect(item, names);
            }
            return;
        }
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String name = fields.next();
                names.add(name);
                collect(node.get(name), names);
            }
        }
    }

    private static String bandOf(JsonNode targets, String name) {
        for (JsonNode target : targets) {
            if (target.get("name").asText().equals(name)) {
                return target.get("distanceBand").asText();
            }
        }
        throw new AssertionError("搜索结果里没有 " + name + "，实际=" + targets);
    }

    private static List<String> names(JsonNode targets) {
        List<String> out = new ArrayList<>();
        for (JsonNode target : targets) {
            out.add(target.get("name").asText());
        }
        return out;
    }

    private long scale(long power, String ratio) {
        return FixedPoint.round(FixedPoint.mul(FixedPoint.of(power), FixedPoint.parse(ratio)));
    }

    /**
     * 直接写存档里的战力。
     *
     * <p>守方战力在 {@code AttackGuardService} 与 {@code TargetSearchService} 里都是
     * <b>读存档</b>而不是当场重算的（逐个重算等于一次搜索打出几千次存储读），
     * 所以这里写的值就是判定时用的值。这也顺便验证了「读存档」这条路径本身是对的。
     */
    private void setMatchPower(String playerId, long matchPower) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPower(new PlayerPower(matchPower * 2, matchPower, matchPower));
        players.save(save);
    }

    private void giveTroops(String playerId, long count) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
    }

    private void removeAllTroops(String playerId) {
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.deduct(UNIT, army.countOf(UNIT));
        armies.save(playerId, army, version);
    }

    /** 解除新手保护（B08 §7：主动攻击则解除）。不解除的话新号既搜不到人也打不了人。 */
    private void liftProtection(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    private String newPlayerAt(int x, int y) {
        return newPlayerAt(x, y, "圈层测试", 1.0d, true);
    }

    /**
     * 建号并落到指定坐标。
     *
     * @param ratio        初始匹配战力相对 INIT_MATCH_POWER 的倍率（随后一般会被 setMatchPower 覆盖）
     * @param liftProtect  是否顺手解除新手保护
     */
    private String newPlayerAt(int x, int y, String nickName, double ratio, boolean liftProtect) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName, 1_700_000_000_000L))
                .playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)，占位说明坐标与别的用例撞了", x, y).isTrue();
        if (ratio != 1.0d) {
            PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
            long base = save.power().matchPower();
            long scaled = FixedPoint.round(FixedPoint.mul(FixedPoint.of(base),
                    FixedPoint.parse(String.valueOf(ratio))));
            save.setPower(new PlayerPower(scaled * 2, scaled, scaled));
            players.save(save);
        }
        if (liftProtect) {
            liftProtection(playerId);
        }
        return playerId;
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        JsonNode root = postRoot(url, playerId, req);
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        assertThat(root.get("data")).as("成功响应必须带 data").isNotNull();
        return root.get("data");
    }

    /** GET 并解出 Result.data；code 非 0 直接失败。 */
    private JsonNode get200(String url, String playerId) throws Exception {
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get(url).header(PLAYER_HEADER, playerId);
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        JsonNode root = JsonUtils.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }

    /** POST 并返回 Result 信封的根节点（业务错误也是 HTTP 200 + code，所以这里不断言 code）。 */
    private JsonNode postRoot(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req));
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文昵称会变乱码
        return JsonUtils.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
