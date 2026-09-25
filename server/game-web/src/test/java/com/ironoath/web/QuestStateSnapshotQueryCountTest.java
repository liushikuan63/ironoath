package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.GoalType;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.dto.generated.QuestView;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：刷新一次任务面板（或问一次红点）到底把<b>同一个人的存档读了几遍</b>。
 * 依赖：test profile（内存存储）+ 一个按「方法名 + 第一个入参」计数的 {@link PlayerRepository} 代理。
 *
 * <p><b>缺陷形状</b>：{@code QuestAppService.refreshStateTargets} 逐条问状态型目标的当前值，
 * 而"攒资源"与"研究科技"这两位各自去 {@code findByPlayerId} 取<b>同一个玩家</b>的整档 ——
 * 一次刷新读两遍，而这条路径挂在面板打开（{@code list}）与 Bot 每个 tick 的红点
 * （{@code claimableCount}）上。结果完全正确，所以任何结果断言都抓不到它，只有计数能。
 *
 * <p><b>与 #425 / #428 / #429 / #430 / #431 的分别</b>：那几处省的是往返（改成批量口），
 * 这一处读的一直是同一个人 —— 省的是"同一份档在一次请求里被读 N 遍"，
 * 修法是<b>一次刷新共享一次读取</b>，不是加端口。
 *
 * <p><b>夹具前提单独钉</b>：本判据断言"读一次"。若表里只剩一条要读档的状态型目标，
 * "一次"就自动成立 —— 那是一把空刀。所以先断言"要读档的目标至少两条"，
 * 让这条判据在夹具退化时当场红，而不是安静地变成永真。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestStateSnapshotQueryCountTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private QuestAppService quests;
    @Autowired private QuestEvents questEvents;
    @Autowired private QueryCounter counter;

    /** 夹具里的"服务端此刻"：任务事件要带时间戳（铁律 5，不用系统时钟）。 */
    private static final long NOW = 1_800_000_000_000L;

    /** 需要读本人存档的状态型目标（与 {@code QuestAppService.stateValue} 里那两个分支同一条口径）。 */
    private static final List<GoalType> TYPES_READING_OWNER_SAVE =
            List.of(GoalType.REACH_RESOURCE, GoalType.RESEARCH_TECH);

    @TestConfiguration
    static class CountingBeans {

        @Bean
        QueryCounter queryCounter() {
            return new QueryCounter();
        }

        @Bean
        @Primary
        PlayerRepository countedPlayers(QueryCounter counter) {
            return counter.wrap(PlayerRepository.class, new InMemoryPlayerStore());
        }
    }

    @BeforeEach
    void resetStores() {
        counter.playerStore().clear();
        counter.reset();
    }

    @Test
    @DisplayName("打开面板：要读档的状态型目标有几条，存档也只读一次；进度值不因共享读取而漂")
    void onePanelRefreshReadsTheOwnersSaveOnceNotOncePerStateTarget() {
        String me = newPlayer("快照甲");
        quests.list(me);                       // 暖一次，把"首次读才有"的往返排除在窗口外
        counter.reset();
        var resp = quests.list(me);

        List<QuestView> saveBacked = resp.quests().stream()
                .filter(row -> TYPES_READING_OWNER_SAVE.contains(row.goalType()))
                .toList();
        assertThat(saveBacked)
                .as("夹具前提：表里至少要有一条要读档的目标，否则“一次”这条判据是空刀")
                .hasSizeGreaterThanOrEqualTo(1);
        assertThat(saveBacked)
                .as("夹具前提：这两条都要读同一个人的档，才是本判据要钉的那个乘法")
                .extracting(QuestView::goalType)
                .containsAll(TYPES_READING_OWNER_SAVE);

        assertThat(counter.readsOf("findByPlayerId", me))
                .as("两条目标共用一次读取：改之前这里是 2 —— 同一份整档在一次刷新里读两遍")
                .isEqualTo(1);
        // 正向：批量共享读取之后进度值仍要各归各（接错目标就把这两条掐成同一个数）
        assertThat(rowsById(resp.quests()).keySet())
                .as("两条状态型目标都还在面板上，没有因为改取数方式而少一行")
                .containsAll(saveBacked.stream().map(QuestView::questId).toList());
    }

    @Test
    @DisplayName("红点口径（Bot 每 tick 都问）与面板同源，也只读一次")
    void theRedDotPathSharesTheSameSingleRead() {
        String me = newPlayer("快照乙");
        quests.claimableCount(me);
        counter.reset();
        quests.claimableCount(me);

        assertThat(counter.readsOf("findByPlayerId", me))
                .as("红点为的是“有没有可领”，不该顺带把同一个人的档读两遍")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("共享一次读取不等于共享一个值：解锁攒资源那条后，它读自己的资源、科技那条仍是自己的")
    void theSharedReadStillReportsEachTargetsOwnValue() {
        String me = newPlayer("快照丙");
        // "攒资源"那条被前置锁着（QuestProgress.unlocked 要前置已领取），锁着的行进度不落账 ⇒
        // 不先做完 quest_main_01 就断言它的 current 会跟着库存动，判据是空刀（本文件第一版就是这么红的）。
        // 走生产入口把前置做完：QuestEvents.progress 正是 CityAppService 升级主城时调的那一口。
        questEvents.progress(me, com.ironoath.core.quest.GoalType.UPGRADE_BUILDING,
                "main_city", 2L, NOW);
        quests.claim(me, new QuestClaimReq("req-" + UUID.randomUUID(), "quest_main_01", "hero_sr_02"));

        QuestView resource = quests.list(me).quests().stream()
                .filter(row -> row.goalType() == GoalType.REACH_RESOURCE)
                .findFirst().orElseThrow();
        assertThat(resource.locked())
                .as("夹具前提：前置领掉之后这一行才解锁，否则它永远是 0，本判据量不到东西")
                .isFalse();
        long target = resource.current() + 3_000L;
        PlayerSave save = players.findByPlayerId(me).orElseThrow();
        PlayerResourceState state = save.resources().get(resource.goalTarget());
        save.putResource(resource.goalTarget(), new PlayerResourceState(target,
                state == null ? 10_000L : state.cap(), state == null ? 0L : state.protectedAmount(),
                state == null ? 0L : state.perHour(), state == null ? 0L : state.lastSettle()));
        players.save(save);

        List<QuestView> rows = quests.list(me).quests();
        QuestView after = rows.stream()
                .filter(row -> row.goalType() == GoalType.REACH_RESOURCE)
                .findFirst().orElseThrow();
        assertThat(after.current())
                .as("攒资源那行读的是自己那一位资源（%s）的量", resource.goalTarget())
                .isEqualTo(Math.min(target, after.goalValue()));
        // 科技那一支的取值来源**量不到**：quest_main_08 的前置链要一直领到 quest_main_06，
        // 而锁着的行进度不落账（QuestProgress.onEvent 里 unlocked 那条），夹具造到那一步的代价
        // 高于这一格的价值。所以本判据只钉得住"资源这支接错来源"，另一支留作未验证（见台账 #443）。
    }

    @Test
    @DisplayName("删号后账本还在：状态行读 0 而不是抛，共享的那一次读取也不许变成 NPE")
    void aMissingSaveReadsZeroInsteadOfThrowing() {
        String me = newPlayer("快照丁");
        quests.list(me);
        counter.playerStore().clear();     // 删号：存档没了，任务账本还留在 quest 存储里
        counter.reset();
        var resp = quests.list(me);

        assertThat(resp.quests()).as("面板仍要画得出来 —— 少一行或抛错都不是玩家该看到的").isNotEmpty();
        assertThat(resp.quests().stream()
                .filter(row -> TYPES_READING_OWNER_SAVE.contains(row.goalType()))
                .map(QuestView::current))
                .as("读不到档按 0，与改之前那两个 save == null 分支同口径")
                .containsOnly(0L);
        assertThat(counter.readsOf("findByPlayerId", me))
                .as("读不到不是多点几遍的理由")
                .isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private static Map<String, QuestView> rowsById(List<QuestView> rows) {
        Map<String, QuestView> out = new java.util.LinkedHashMap<>();
        rows.forEach(row -> out.put(row.questId(), row));
        return out;
    }

    private String newPlayer(String nickName) {
        return playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), nickName, 1_700_000_000_000L, "")).playerId();
    }

    /**
     * 按「方法名 + 第一个入参」累计调用次数。
     *
     * <p>与本族另外几份同源：键是字符串而不是符号引用，这样判据可以先于实现编译通过，
     * "先跑出红"这一步才做得了（台账要的就是那个红）。
     */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> delegates = new ConcurrentHashMap<>();

        int countOf(String methodName) {
            AtomicInteger seen = calls.get(methodName);
            return seen == null ? 0 : seen.get();
        }

        int readsOf(String methodName, String firstArg) {
            AtomicInteger seen = calls.get(methodName + "(" + firstArg + ")");
            return seen == null ? 0 : seen.get();
        }

        void reset() {
            calls.clear();
        }

        /** 计数代理背后那份真实内存实现，只给夹具清空用。 */
        InMemoryPlayerStore playerStore() {
            return (InMemoryPlayerStore) delegates.get(PlayerRepository.class);
        }

        <T> T wrap(Class<T> port, T delegate) {
            delegates.put(port, delegate);
            InvocationHandler counting = (proxy, method, args) -> {
                String firstArg = args == null || args.length == 0 || args[0] == null
                        ? "-" : String.valueOf(args[0]);
                calls.computeIfAbsent(method.getName() + "(" + firstArg + ")",
                        key -> new AtomicInteger()).incrementAndGet();
                calls.computeIfAbsent(method.getName(), key -> new AtomicInteger()).incrementAndGet();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, counting));
        }
    }
}
