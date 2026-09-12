package com.ironoath.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 职责：钉住「mongo 模式下每一个存储端口都必须是 Mongo 实现」这件事。
 * 依赖：纯函数判定 + MockEnvironment；不起容器、不连 Mongo。
 *
 * <p><b>这道守卫的历史与两段形态见 {@link MongoStorageGuard} 的类注释</b>：
 * 它曾经的任务是"mongo 起不来时把欠账说清楚"，现在的任务是"mongo 起得来之后，
 * 不许有端口悄悄退回内存实现"。测试保留了这两个方向：判定必须能收下 Mongo 实现（前者退役的前提），
 * 也必须能拒绝内存实现与缺实现（后者仍然要守）。
 */
class MongoStorageGuardTest {

    @Test
    @DisplayName("全部端口都是 Mongo 实现：判定放行")
    void allMongoImplementationsAreAccepted() {
        Map<Class<?>, List<String>> byPort = new LinkedHashMap<>();
        byPort.put(com.ironoath.core.player.PlayerRepository.class,
                List.of("com.ironoath.web.store.mongo.MongoPlayerStore"));
        byPort.put(com.ironoath.web.social.SocialStore.class,
                List.of("com.ironoath.web.store.mongo.MongoSocialStore"));

        assertThat(MongoStorageGuard.offenders(byPort)).isEmpty();
    }

    @Test
    @DisplayName("任何一个端口还是内存实现：判定必须点名，不许静默放行")
    void memoryImplementationsAreRefused() {
        Map<Class<?>, List<String>> byPort = new LinkedHashMap<>();
        byPort.put(com.ironoath.core.player.PlayerRepository.class,
                List.of("com.ironoath.web.store.mongo.MongoPlayerStore"));
        byPort.put(com.ironoath.web.social.SocialStore.class,
                List.of("com.ironoath.web.store.memory.InMemorySocialStore"));

        assertThat(MongoStorageGuard.offenders(byPort))
                .as("混进一个内存实现就等于那部分状态在 mongo 模式下随进程消失")
                .hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("SocialStore")
                        .contains("InMemorySocialStore"));
    }

    @Test
    @DisplayName("端口没有任何实现：同样算不合格，错误信息不依赖 Spring 的报错顺序")
    void missingImplementationsAreRefused() {
        Map<Class<?>, List<String>> byPort = new LinkedHashMap<>();
        byPort.put(com.ironoath.core.player.PlayerRepository.class, List.of());
        byPort.put(com.ironoath.web.social.SocialStore.class, null);

        assertThat(MongoStorageGuard.offenders(byPort))
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("没有任何实现"));
    }

    @Test
    @DisplayName("默认（memory）与显式 memory：开发、单测、CI 都不该被这条守卫挡住")
    void memoryModeIsUnaffected() {
        MongoStorageGuard guard = new MongoStorageGuard();

        guard.setEnvironment(new MockEnvironment());
        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();

        MockEnvironment memory = new MockEnvironment();
        memory.setProperty("ironoath.storage", "memory");
        guard.setEnvironment(memory);
        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}