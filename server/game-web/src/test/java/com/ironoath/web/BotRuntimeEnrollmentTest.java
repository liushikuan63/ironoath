package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;

/** 真实runtime登记漏斗；只注入一次NX存储故障，不伪造成功游标。 */
@SpringBootTest
@ActiveProfiles("test")
class BotRuntimeEnrollmentTest {
    @Autowired BotRuntimeService runtime;
    @Autowired BotRegistry bots;
    @Autowired com.ironoath.web.service.PlayerInitService init;
    @Autowired com.ironoath.web.service.CityAppService city;
    @SpyBean(name="botTaskQueue") BotScheduler.TaskQueue queue;

    @Test void failedFirstNxWriteDoesNotPermanentlyMarkBotAsEnrolled() {
        runtime.reset();bots.clear();
        long now=System.currentTimeMillis();
        String id=init.init(new com.ironoath.web.dto.generated.PlayerInitReq(UUID.randomUUID().toString(),
                "owned-enrollment-"+UUID.randomUUID(),"登记候选",now,"")).playerId();
        city.list(id);
        var profile=new BotProfile(id, "bot_jielue",
                new BotProfile.AiProfile(8_000,4_000,0,6_000),
                new BotProfile.Persona(42,7,99,java.util.stream.IntStream.range(0,24).boxed().toList(),3,30,1_000),10_000);
        bots.register(profile);
        try {
            doThrow(new IllegalStateException("one Redis NX write failure")).doCallRealMethod()
                    .when(queue).scheduleIfAbsent(any());
            // 登记失败本轮是否向入口传播不是本例判据，检查真实完成状态与下一轮重试。
            try { runtime.tick(now); } catch(RuntimeException expectedStorageFailure) { }
            assertThat(queue.size()).isZero();
            assertThat(runtime.enrolledCount()).as("游标未落成不能宣称已登记").isZero();
            runtime.tick(now+1);
            assertThat(runtime.enrolledCount()).isEqualTo(1);assertThat(queue.size()).isEqualTo(1);
        } finally {
            org.mockito.Mockito.reset(queue);runtime.reset();bots.clear();
        }
    }
}
