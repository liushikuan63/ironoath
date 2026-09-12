package com.ironoath.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 职责：钉住「mongo 模式必须<b>立刻</b>说清楚自己不可用」这件事（B16 生产化欠账的可见性）。
 * 依赖：spring-test 的 MockEnvironment；不起容器。
 *
 * <p><b>为什么要为一条「拒绝启动」写测试</b>：不写的话，下一个人为了跑通 CI 会顺手把这个
 * 守卫删掉或改成 WARN —— 那正好把我们要防的事（一个起得来但会丢档的生产环境）放出来。
 * 这条测试的作用是：删掉守卫就红，改它的动机必须先回答「剩下那些仓储补完了吗，
 * 以及每条有没有配上 {@code VersionedStoreContractTest} 的子类」。
 */
class MongoStorageGuardTest {

    @Test
    @DisplayName("storage=mongo：在任何 bean 实例化之前就拒绝，并把「已覆盖/仍欠/危险在哪」说全")
    void mongoModeFailsFastWithTheFullAccount() {
        MongoStorageGuard guard = new MongoStorageGuard();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ironoath.storage", "mongo");
        guard.setEnvironment(env);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产存储模式")
                .as("已经做完的部分要报名字：不然下一个人会以为一点都没做，顺手把守卫删了")
                .hasMessageContaining("player、幂等键、城建档、背包、武将")
                .hasMessageContaining("无条件装配的内存实现")
                .as("必须点名仍然只有内存实现的仓储")
                .hasMessageContaining("social")
                .as("每补一类存储都要出现在已覆盖那一段（这一句和下一条用例一起钉住计数）")
                .hasMessageContaining("世界、埋点")
                .as("真正的危险是「起得来但会丢/各算各的」，这句话不能被省掉")
                .hasMessageContaining("正常启动").hasMessageContaining("各算各的")
                .hasMessageContaining("不要用 prod");
    }

    /**
     * 文案比代码旧是一种真实的失效：补一类仓储时如果只改 {@code MongoStoreConfig}，
     * 守卫会继续把它算作欠账，于是"还剩多少"这个数再也无人敢信 —— 而这道守卫的全部价值
     * 就在于它给的是一个可核对的数，不是一句"还不完整"。
     *
     * <p>断言写成「已覆盖段与仍欠段必须互斥」而不是抄一份名单：抄名单的话，补仓储的人会
     * 同时改两处（于是看不出漏改），而互斥检查只改守卫文案一处就会自己变红。
     */
    @Test
    @DisplayName("守卫的欠账清单不许比代码旧：同一个存储不许既算已覆盖又算仍欠")
    void coveredStoresMustDisappearFromTheDebtList() {
        MongoStorageGuard guard = new MongoStorageGuard();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ironoath.storage", "mongo");
        guard.setEnvironment(env);

        String message = catchingMessage(guard);
        int debtStart = message.indexOf("仍然是");
        assertThat(debtStart).as("守卫文案必须分成「已覆盖」与「仍欠」两段，否则无从核对").isPositive();
        String covered = message.substring(0, debtStart);
        String debt = message.substring(debtStart);

        for (String name : covered.split("：")[1].split("、")) {
            String token = name.trim().replaceAll("。.*$", "");
            assertThat(token).as("已覆盖段里出现了空项，说明文案格式被改坏了").isNotEmpty();
            assertThat(debt).as("「%s」已经补上 Mongo 实现，不许再被算进欠账", token).doesNotContain(token);
        }
        // 英文仓储名同样不许留在欠账里（上一轮就漏过：hero 与 army 都已实现）
        assertThat(debt).doesNotContain("hero").doesNotContain("army");
        assertThat(covered).contains("军队").as("已覆盖段必须如实报数，别只写「部分完成」");
    }

    private static String catchingMessage(MongoStorageGuard guard) {
        try {
            guard.postProcessBeanFactory(null);
            throw new AssertionError("mongo 模式下守卫没有抛异常");
        } catch (IllegalStateException e) {
            return String.valueOf(e.getMessage());
        }
    }

    @Test
    @DisplayName("默认（memory）与显式 memory：开发、单测、CI 都不该被这条守卫挡住")
    void memoryModeIsUnaffected() {
        MongoStorageGuard guard = new MongoStorageGuard();

        guard.setEnvironment(new MockEnvironment());
        assertThatCode(() -> guard.postProcessBeanFactory(null)).doesNotThrowAnyException();

        MockEnvironment memory = new MockEnvironment();
        memory.setProperty("ironoath.storage", "memory");
        guard.setEnvironment(memory);
        assertThatCode(() -> guard.postProcessBeanFactory(null)).doesNotThrowAnyException();
    }
}
