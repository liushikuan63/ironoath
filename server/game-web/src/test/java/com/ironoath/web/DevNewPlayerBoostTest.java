package com.ironoath.web;

import com.ironoath.web.config.DevNewPlayerBoost;
import com.ironoath.web.config.NewPlayerBoost;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：钉住 {@code NewPlayerBoost} 的两条承重结论（B13 正向链路能跑通全靠它）。
 * 依赖：纯逻辑（两个静态方法）+ 反射读注解，**不起 Spring 上下文**。
 *
 * <p><b>为什么这三条要能失败</b>：
 * ① 覆盖口写错成"没设时退回 0"，新号会变成 0 级主城，而症状要等玩家进内城才看得见；
 * ② 有人把 {@code @Profile("dev")} 摘掉（"反正本地设了环境变量就行"），
 *    那么 prod 只要有人设了同名环境变量，新号开局就是 16 级主城 + 巨额资源 ——
 *    这是本仓最贵的那类事故（拿验收环境换一条测试），而它不会有任何报错；
 * ③ 两处主城等级各读一份（存档 / 建筑），提速档只抬一处就造出一份自相矛盾的存档。
 */
class DevNewPlayerBoostTest {

    @Test
    @DisplayName("覆盖值 > 0 才生效；没设（0）与写错（负数）都原样退回配置表")
    void overrideOnlyWhenPositive() {
        assertThat(NewPlayerBoost.cityLevel(1L, 16)).isEqualTo(16);
        assertThat(NewPlayerBoost.cityLevel(1L, 0)).isEqualTo(1);
        assertThat(NewPlayerBoost.cityLevel(1L, -5)).isEqualTo(1);
        // 配置值本身也要原样带出去：不能顺手夹成 1（那是"退回"而不是"覆盖"）
        assertThat(NewPlayerBoost.cityLevel(3L, 0)).isEqualTo(3);
        assertThat(NewPlayerBoost.startAmount(5_000L, 200_000L)).isEqualTo(200_000L);
        assertThat(NewPlayerBoost.startAmount(5_000L, 0L)).isEqualTo(5_000L);
        assertThat(NewPlayerBoost.startAmount(5_000L, -1L)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("没有装配任何覆盖时（prod）拿到的是空实现，两个值都是 0 = 不覆盖")
    void noneIsSafe() {
        assertThat(NewPlayerBoost.NONE.cityLevel()).isZero();
        assertThat(NewPlayerBoost.NONE.startAmount()).isZero();
        assertThat(NewPlayerBoost.cityLevel(1L, NewPlayerBoost.NONE.cityLevel())).isEqualTo(1L);
        assertThat(NewPlayerBoost.startAmount(5_000L, NewPlayerBoost.NONE.startAmount())).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("dev 实现必须挂在 @Profile(\"dev\") 上：prod 读不到它是结构事实，不是\"恰好没设环境变量\"")
    void devImplementationIsProfileGated() {
        Profile profile = DevNewPlayerBoost.class.getAnnotation(Profile.class);
        assertThat(profile).as("DevNewPlayerBoost 上没有 @Profile 注解 —— prod 就会注册它").isNotNull();
        assertThat(profile.value()).containsExactly("dev");

        // 环境变量的名字也钉住：改名要同步改验收脚本，而脚本不在编译期依赖这里
        assertThat(DevNewPlayerBoost.CITY_LEVEL_ENV).isEqualTo("IRONOATH_DEV_CITY_LEVEL");
        assertThat(DevNewPlayerBoost.START_AMOUNT_ENV).isEqualTo("IRONOATH_DEV_START_AMOUNT");
    }

    @Test
    @DisplayName("两个服务各接一份同一个覆盖口：只给一边接上就会造出「存档 16 / 建筑 1」的自相矛盾存档")
    void bothServicesWireTheBoost() {
        assertThat(declaresBoostField(PlayerInitService.class))
                .as("PlayerInitService 不再读提速档 ⇒ 新号存档的主城等级与建筑会不一致").isTrue();
        assertThat(declaresBoostField(CityAppService.class))
                .as("CityAppService 不再读提速档 ⇒ 新号主城建筑会停在 1 级").isTrue();
    }

    /** 按类型找字段（不看字段名：改名不该让这条红，删掉接线才该红）。 */
    private static boolean declaresBoostField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == NewPlayerBoost.class) {
                return true;
            }
        }
        return false;
    }
}
