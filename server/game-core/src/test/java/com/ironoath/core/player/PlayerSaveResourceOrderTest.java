package com.ironoath.core.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link PlayerSave#resources()} 的**迭代顺序**（#617）。
 *
 * <p><b>为什么这条要单独钉</b>：它原本返回 {@code Map.copyOf(resources)}，而那个实现按 SALT
 * 散列摆放键，SALT 每个 JVM 进程都不同 ⇒ 顺序跨进程变、进程内恒定。链路上它一路传到
 * {@code /city/list} 的 {@code resources} 字段、再传到客户端资源条的格子顺序，于是
 * <b>玩家每次重新登录看到的资源排列可能不同</b>，而按「第 N 格」写死假设的量具会时绿时红。
 *
 * <p><b>这条用例怎么才算能失败</b>：它断言的是 {@code keySet()} 的**逐位相等**，而不是
 * 「含某几个键」或「size 对」。改回 {@code Map.copyOf} 时，本进程内顺序有较大概率仍是
 * 配置表顺序（只有一部分 SALT 会打乱），所以单跑这一条未必红 —— 因此补了
 * {@link #顺序在多次取用之间不会自己变()} 与 {@link #视图不可改()}，
 * 并且**真正的判据是 {@code tools/MapCopyOrderProof.java} 跨 JVM 的那 6/8 次不一致**
 * （见 #617）。本类负责把「约定是什么」钉在仓库里，复现器负责证明「约定为什么必要」。
 */
class PlayerSaveResourceOrderTest {

    private static final long NOW = 1_700_000_000_000L;

    /** 配置表 `resource.json` 里的声明序。 */
    private static final List<String> CONFIG_ORDER =
            List.of("WOOD", "STONE", "IRON", "GRAIN", "GOLD", "STAMINA");

    private static PlayerSave saveWithConfigOrder() {
        Map<String, PlayerResourceState> initial = new LinkedHashMap<>();
        for (String key : CONFIG_ORDER) {
            initial.put(key, new PlayerResourceState(10L, 100L, 0L, 1L, NOW));
        }
        return PlayerSave.createNew("p-order-1", "d-order-1", "顺序钉子", 1, NOW, 1,
                initial, new PlayerPower(1L, 1L, 1L), null);
    }

    @Test
    void 资源视图的键序逐位等于配置表顺序() {
        List<String> actual = new ArrayList<>(saveWithConfigOrder().resources().keySet());
        assertThat(actual).isEqualTo(CONFIG_ORDER);
    }

    @Test
    void 顺序在多次取用之间不会自己变() {
        PlayerSave save = saveWithConfigOrder();
        List<String> first = new ArrayList<>(save.resources().keySet());
        List<String> second = new ArrayList<>(save.resources().keySet());
        assertThat(second).isEqualTo(first).isEqualTo(CONFIG_ORDER);
    }

    @Test
    void 覆盖写已有资源不改变它在序列里的位置() {
        PlayerSave save = saveWithConfigOrder();
        // 惰性结算每次都整表写回（ResourceRateService 就是这么用的）。
        // 若实现换成 Map.copyOf 这类散列摆放的结构，覆盖写会让键位重排 ⇒ 这条会红。
        for (String key : CONFIG_ORDER) {
            save.putResource(key, new PlayerResourceState(20L, 100L, 0L, 1L, NOW));
        }
        assertThat(new ArrayList<>(save.resources().keySet())).isEqualTo(CONFIG_ORDER);
        // 再取一次仍然一致：不是「碰巧这一次对」
        for (String key : CONFIG_ORDER) {
            save.putResource(key, new PlayerResourceState(30L, 100L, 0L, 1L, NOW));
        }
        assertThat(new ArrayList<>(save.resources().keySet())).isEqualTo(CONFIG_ORDER);
    }

    @Test
    void 视图不可改() {
        Map<String, PlayerResourceState> view = saveWithConfigOrder().resources();
        assertThatThrownBy(() -> view.put("X", new PlayerResourceState(0L, 1L, 0L, 0L, NOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
