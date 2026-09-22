package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.WorldEntity;
import com.ironoath.web.dto.generated.WorldEntityType;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：装配视野内玩家城<b>发几次存储往返</b>（收口清单本格：{@code WorldAppService.entityAt} 的 N+1）。
 * 依赖：test profile（内存存储）+ 一个按方法名计数的 {@link PlayerRepository} 代理。
 *
 * <p><b>改之前每座城两趟点查</b>：昵称一趟，等级又绕经 {@code playerLevelOf} 一趟。城是"视野内
 * 有多少真人就有多少座"，而这条装配同时挂在拖图（{@code viewport}，每次滑动 9 个块）与 Bot 的
 * 目标选择（{@code visibleEntitiesOf}，每个 tick 每个 Bot）上 —— 本仓第二重的 N+1。
 *
 * <p><b>为什么量 {@code visibleEntitiesOf} 而不是 {@code viewport}</b>：两个都要装配同一批块、
 * 走同一个 {@code entityAt}，判据等价；而 {@code viewport} 那条路径上还挂着两个惰性 sweep
 * （公敌广播按人点查存档 —— 那是同族另一处，已另记；Bot 孵化本身就走批量口），
 * 拿它计数的话"榜长为零"这句判据会被别人的往返污染，红点落在不是本格的缺陷上。
 *
 * <p><b>判据是「次数」不是「结果」</b>：批量与逐个点查给出的城行一字不差，全量端点测试抓不到它。
 * 计数键是<b>方法名字符串</b>，所以批量口没接上时这条判据照样编译、照样能先跑出红
 * （与 {@code AllianceMemberQueryCountTest} 同一手法）。
 */
@SpringBootTest
@ActiveProfiles("test")
class WorldCityQueryCountTest {

    private static final int CHUNK_SIZE = 32;

    @Autowired private WorldAppService world;
    @Autowired private WorldRepository worldStore;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private QueryCounter counter;

    @TestConfiguration
    static class CountingBeans {

        @Bean
        QueryCounter queryCounter() {
            return new QueryCounter();
        }

        /**
         * 以 {@code @Primary} 顶掉容器那份玩家仓储，好让 {@code WorldAppService} 拿到计数代理。
         *
         * <p>代理持有自己的内存实现：{@code @Bean} 工厂方法的返回类型常声明成端口接口
         * （{@code MemoryStoreConfig} 正是这么写的），按实现类注入不保证拿得到。
         */
        @Bean
        @Primary
        PlayerRepository countedPlayers(QueryCounter counter) {
            return counter.wrap(PlayerRepository.class, new InMemoryPlayerStore());
        }
    }

    @BeforeEach
    void resetStores() {
        counter.playerStore().clear();
        ((com.ironoath.web.store.memory.InMemoryWorldStore) worldStore).clear();
        counter.reset();
    }

    @Test
    @DisplayName("视野内的四座玩家城：一次批量读，一次点查都不许有；每座城的名字与等级各归各")
    void cityAssemblyReadsAllOwnersInOneBatch() {
        String me = newPlayer("视角主", 7);
        worldStore.placeCity(me, Coord.of(100, 100));
        world.ensureHomeExplored(me);
        String north = newPlayer("邻居甲", 12);
        worldStore.placeCity(north, Coord.of(101, 100));
        String east = newPlayer("邻居乙", 3);
        worldStore.placeCity(east, Coord.of(102, 101));
        String lone = newPlayer("邻居丙", 20);
        worldStore.placeCity(lone, Coord.of(103, 102));
        // 视野外（另一个块）的城：不许被这一趟批量读顺带捞进来
        String far = newPlayer("远处丁", 40);
        worldStore.placeCity(far, Coord.of(100 + CHUNK_SIZE * 3, 100));

        // 暖一次，排除任何首次读才有的往返，再开始记账
        world.visibleEntitiesOf(me);
        counter.reset();
        List<WorldEntity> rows = world.visibleEntitiesOf(me);

        // ---- 判据①：点查必须归零。改之前这里是 4 座城 × 2 趟 = 8 次 ----
        assertThat(counter.countOf("findByPlayerId"))
                .as("逐个 findByPlayerId 是每座城读两趟整份存档，且随视野内的城数线性增长")
                .isZero();
        // ---- 判据②：正向断言，批量口真的接上了（只查"坏东西不存在"会在批量口整个没接上时 also 全绿）----
        assertThat(counter.countOf("findByPlayerIds"))
                .as("本块玩家城的存档一次批量读回")
                .isEqualTo(1);

        // ---- 判据③：内容一字不变，且等级/名字不许接错档（四个人的等级各不相同）----
        Map<String, WorldEntity> cities = new HashMap<>();
        rows.forEach(row -> {
            if (row.type() == WorldEntityType.CITY) {
                cities.put(row.id(), row);
            }
        });
        assertThat(cities.keySet())
                .as("视野内这一块上的城一座不少、一块外的不多")
                .containsExactlyInAnyOrder(me, north, east, lone);
        assertThat(cities.get(me).ownerName()).isEqualTo("视角主");
        assertThat(cities.get(me).level()).isEqualTo(7);
        assertThat(cities.get(north).ownerName()).isEqualTo("邻居甲");
        assertThat(cities.get(north).level()).isEqualTo(12);
        assertThat(cities.get(east).ownerName()).isEqualTo("邻居乙");
        assertThat(cities.get(east).level()).isEqualTo(3);
        assertThat(cities.get(lone).ownerName()).isEqualTo("邻居丙");
        assertThat(cities.get(lone).level()).isEqualTo(20);
    }

    @Test
    @DisplayName("往返次数与城数无关：同一块上从 1 座城加到 6 座，玩家存档仍只读一次")
    void theReadCountDoesNotGrowWithTheNumberOfCities() {
        String me = newPlayer("视角主", 7);
        worldStore.placeCity(me, Coord.of(100, 100));
        world.ensureHomeExplored(me);

        world.visibleEntitiesOf(me);
        counter.reset();
        assertThat(world.visibleEntitiesOf(me)).isNotEmpty();
        int withOneCity = counter.countOf("findByPlayerId") + counter.countOf("findByPlayerIds");

        for (int i = 0; i < 5; i++) {
            String neighbor = newPlayer("邻居" + i, 3 + i);
            worldStore.placeCity(neighbor, Coord.of(101 + i, 100));
        }
        world.visibleEntitiesOf(me);
        counter.reset();
        List<WorldEntity> rows = world.visibleEntitiesOf(me);
        int withSixCities = counter.countOf("findByPlayerId") + counter.countOf("findByPlayerIds");

        assertThat(rows).filteredOn(row -> row.type() == WorldEntityType.CITY)
                .as("夹具前提：五座新城都落在同一块上，都被装配出来了").hasSize(6);
        assertThat(withSixCities)
                .as("改之前这里是「一座城 2 次」× 6 = 12 对 2 —— 次数跟着城数长就是 N+1 又活了")
                .isEqualTo(withOneCity);
        assertThat(counter.countOf("findByPlayerId"))
                .as("这一格真正要归零的是点查那一路")
                .isZero();
    }

    // ---------- 夹具 ----------

    /** 建号并把主城等级设为给定值 —— 等级是城行上唯一会被批量接错档的字段，四人各取不同值。 */
    private String newPlayer(String nickName, int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        players.save(save);
        return playerId;
    }

    /** 按方法名累计调用次数（批量口在改之前不存在，所以键只能是字符串）。 */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> delegates = new ConcurrentHashMap<>();

        int countOf(String methodName) {
            AtomicInteger seen = calls.get(methodName);
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
