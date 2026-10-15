package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.store.redis.RedissonBotTaskQueue;

class BotTaskBatchWriteScopeTest {
    private record Call(Object mode,String lua,Object returnType,List<?> keys,Object[] args) {}
    private final List<Call> calls=new ArrayList<>();
    private Number acknowledgement=3L;
    private RuntimeException rpcFailure;
    private final RScript script=mock(RScript.class, invocation->{
        if(invocation.getMethod().getName().equals("eval")) {
            Object[] args=invocation.getRawArguments();
            calls.add(new Call(args[0],(String)args[1],args[2],(List<?>)args[3],(Object[])args[4]));
            if(rpcFailure!=null)throw rpcFailure;
            return acknowledgement;
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    private RedissonBotTaskQueue queue(ObjectMapper mapper) {
        var client=mock(RedissonClient.class);when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        return new RedissonBotTaskQueue(client,mapper,"owned-batch-write-scope");
    }
    private List<BotScheduler.BotTask> tasks() {
        return List.of("a","b","c").stream().map(id->BotScheduler.BotTask.actionAt(id,10_000,
                new BotDecisionTree.Decision(BotDecisionTree.Action.IDLE,false,0,"pending "+id))).toList();
    }
    @Test void validBatchUsesOneWriteAndKeepsCompletePendingPayload() throws Exception {
        var mapper=new ObjectMapper();var input=tasks();
        assertThat(queue(mapper).scheduleAll(input)).isEmpty();assertThat(calls).hasSize(1);
        var call=calls.get(0);assertThat(call.mode()).isEqualTo(RScript.Mode.READ_WRITE);
        assertThat(call.returnType()).isEqualTo(RScript.ReturnType.INTEGER);
        assertThat(call.keys()).hasSize(2);assertThat(call.lua()).contains("HSET","ZADD");
        assertThat(call.args()).hasSize(9);
        for(int i=0;i<input.size();i++) {
            assertThat(call.args()[i*3]).isEqualTo(input.get(i).botId());
            assertThat(call.args()[i*3+1]).isEqualTo(Long.toString(input.get(i).dueAt()));
            var stored=mapper.readValue((String)call.args()[i*3+2],RedissonBotTaskQueue.StoredTask.class);
            assertThat(new BotScheduler.BotTask(stored.botId(),stored.dueAt(),stored.pending())).isEqualTo(input.get(i));
        }
    }
    @Test void oneSerializationFailureStillWritesTheOtherItemsTogether() throws Exception {
        var mapper=spy(new ObjectMapper());
        doAnswer(invocation->{
            var task=(RedissonBotTaskQueue.StoredTask)invocation.getArgument(0);
            if(task.botId().equals("b"))throw new JsonProcessingException("bad b") {};
            return invocation.callRealMethod();
        }).when(mapper).writeValueAsString(any());
        acknowledgement=2L;var input=tasks();
        assertThat(queue(mapper).scheduleAll(input)).containsExactly(input.get(1));
        assertThat(calls).hasSize(1);assertThat(calls.get(0).args()).hasSize(6);
        assertThat(calls.get(0).args()[0]).isEqualTo("a");assertThat(calls.get(0).args()[3]).isEqualTo("c");
    }
    @Test void unexpectedAcknowledgementLeavesEveryItemUnconfirmed() {
        acknowledgement=2L;var input=tasks();
        assertThat(queue(new ObjectMapper()).scheduleAll(input)).containsExactlyElementsOf(input);
        assertThat(calls).hasSize(1);
    }
    @Test void rpcFailureMakesOneAttemptAndReturnsEveryOriginalItemUnconfirmed() {
        rpcFailure=new IllegalStateException("reply lost; commit unknown");var input=tasks();
        assertThat(queue(new ObjectMapper()).scheduleAll(input)).containsExactlyElementsOf(input);
        assertThat(calls).hasSize(1);
    }
    @Test void nullAcknowledgementDoesNotBecomeSuccessOrTriggerASecondAttempt() {
        acknowledgement=null;var input=tasks();
        assertThat(queue(new ObjectMapper()).scheduleAll(input)).containsExactlyElementsOf(input);
        assertThat(calls).hasSize(1);
    }
    @Test void emptyAndDuplicateInputDoNotIssueRedisWrites() {
        var queue=queue(new ObjectMapper());assertThat(queue.scheduleAll(List.of())).isEmpty();
        assertThatThrownBy(()->queue.scheduleAll(List.of(tasks().get(0),BotScheduler.BotTask.decisionAt("a",20_000))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
