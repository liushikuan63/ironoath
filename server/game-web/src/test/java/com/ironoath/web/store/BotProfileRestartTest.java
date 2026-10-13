package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.Application;
import com.ironoath.web.bot.BotRegistry;

/** 两次真实生产装配共享自有随机Mongo，第二次不重新注册画像。独立JVM验收另执行。 */
class BotProfileRestartTest {
    private ConfigurableApplicationContext start(String database,String... extra) {
        Path config=Path.of("../../contract/config");
        if (!config.toFile().isDirectory()) config=Path.of("contract/config");
        SpringApplication app=new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        var arguments=new java.util.ArrayList<>(List.of("--spring.profiles.active=test", "--ironoath.storage=mongo",
                "--spring.data.mongodb.uri="+TestMongo.uri(),
                "--spring.data.mongodb.database="+database,
                "--ironoath.config-dir="+config.toAbsolutePath().normalize()));
        arguments.addAll(List.of(extra));
        return app.run(arguments.toArray(String[]::new));
    }

    @Test void explicitRedisQueueConfigurationCannotSilentlyRemainInMemory() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).as("真实Mongo必须可用").isNotNull();
            try(var context=start(db.template().getDb().getName(),"--ironoath.lock=redisson",
                    "--ironoath.bot.task-queue=redis")) {
                assertThat(context.getBean(com.ironoath.core.bot.BotScheduler.TaskQueue.class)
                        .getClass().getSimpleName()).as("已要求Redis就不能静默回内存").isEqualTo("RedissonBotTaskQueue");
            }
        }
    }

    @Test void mongoProfileSurvivesFreshProductionContextWithoutRegistration() {
        try (TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).as("真实Mongo必须可用，零测试或跳过不算恢复验证").isNotNull();
            String database=db.template().getDb().getName();
            var expected=new BotProfile("owned-profile", "bot_jielue",
                    new BotProfile.AiProfile(8_000,4_000,2_000,6_000),
                    new BotProfile.Persona(42,7,99,List.of(0,12,23),3,27,1_000),9_000);
            try (var first=start(database)) {
                assertThat(first.getBean(MongoTemplate.class).getDb().getName()).isEqualTo(database);
                first.getBean(BotRegistry.class).register(expected);
                assertThat(first.getBean(BotRegistry.class).profileOf(expected.botId())).isEqualTo(expected);
            }
            try (var second=start(database)) {
                var restored=second.getBean(BotRegistry.class);
                assertThat(restored.profileOf(expected.botId()))
                        .as("新进程装配不得让已保存画像消失或重随机性格/作息").isEqualTo(expected);
                assertThat(restored.isBot(expected.botId())).isTrue();
                assertThat(restored.botIds()).containsExactly(expected.botId());
                assertThat(restored.humansIn(List.of(expected.botId(),"owned-human"))).containsExactly("owned-human");
            }
        }
    }
}
