package com.ironoath.web;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GachaCfg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：gacha 表里「计价方式」这组跨列一致性 —— 协议 description 承诺过的那条守卫。
 * 依赖：game-config ＋ contract/config 的真实表；不起容器（纯数据，起容器只会慢十倍）。
 *
 * <p><b>为什么必须是机器判据</b>：服务端只有一句
 * {@code boolean paidByResource = pool.costResource() != null;}，于是两种配置错误都不会报错——
 * <ul>
 *   <li>两个都填：资源价静默赢，道具那条路永远走不到，玩家看到的价格与服务端扣的东西可能不是一回事；</li>
 *   <li>两个都不填：paidByResource 为 false，接着拿 {@code null} 的 costItemId 走扣道具分支。</li>
 * </ul>
 * 两者的症状都是「这个池子抽起来行为奇怪」而不是「配置写错了」，排查的人会去改代码，
 * 配置的错就一直留着 —— 所以这里把它变成一次编译期就能跑出来的红。
 */
class GachaConfigConsistencyTest {

    @Test
    @DisplayName("每个池子的 costItemId 与 costResource 恰好一个非空（hero.schema.json 承诺的就是这条）")
    void eachPoolPricesWithExactlyOneOfItemOrResource() {
        List<GachaCfg> pools = ConfigRegistry.loadFromDirectory(Path.of("contract/config"))
                .all(GachaCfg.class);
        assertThat(pools).as("空表会让这条判据空转，先确认表真的加载到了").isNotEmpty();
        for (GachaCfg pool : pools) {
            boolean byItem = pool.costItemId() != null;
            boolean byResource = pool.costResource() != null;
            assertThat(byItem ^ byResource)
                    .as("池子 %s（%s）：costItemId=%s 与 costResource=%s 必须恰好一个非空 —— "
                            + "两个都填时按资源扣、道具价形同虚设，两个都不填时会拿 null 去走扣道具分支",
                            pool.id(), pool.poolType(), pool.costItemId(), pool.costResource())
                    .isTrue();
        }
    }
}
