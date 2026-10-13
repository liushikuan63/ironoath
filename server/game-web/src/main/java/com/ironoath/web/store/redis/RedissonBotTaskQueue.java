package com.ironoath.web.store.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotScheduler;

/** 一Bot一游标。没有轮询消费者；请求惰性取到期项，完整动作与最早到期跨重启保留。 */
public final class RedissonBotTaskQueue implements BotScheduler.TaskQueue {
    private static final String WRITE="""
        if ARGV[4] == 'NX' and (redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1
            or redis.call('ZSCORE', KEYS[1], ARGV[1])) then return 0 end
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
        return 1
        """;
    private static final String READ="""
        local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
        local out = {}
        for _, id in ipairs(ids) do
            local body = redis.call('HGET', KEYS[2], id)
            if not body then return redis.error_reply('Bot task payload missing; cursor retained') end
            table.insert(out, id); table.insert(out, body)
        end
        return out
        """;
    private static final String CLAIM="""
        local out = {}
        for i = 2, #ARGV, 2 do
            local id, expected = ARGV[i], ARGV[i + 1]
            local due = redis.call('ZSCORE', KEYS[1], id)
            if due and tonumber(due) <= tonumber(ARGV[1])
                and redis.call('HGET', KEYS[2], id) == expected then
                redis.call('ZREM', KEYS[1], id); redis.call('HDEL', KEYS[2], id)
                table.insert(out, id)
            end
        end
        return out
        """;
    public record StoredTask(String botId,long dueAt,BotDecisionTree.Decision pending) {
        BotScheduler.BotTask task() { return new BotScheduler.BotTask(botId,dueAt,pending); }
    }
    private final RScript script;
    private final ObjectMapper mapper;
    private final List<Object> keys;

    public RedissonBotTaskQueue(RedissonClient client,ObjectMapper mapper,String namespace) {
        if(client==null||mapper==null||namespace==null||namespace.isBlank())
            throw new IllegalArgumentException("Redis/mapper/namespace必须有效");
        this.script=client.getScript(StringCodec.INSTANCE);
        this.mapper=mapper;
        String prefix=keyPrefix(namespace);
        this.keys=List.of(prefix+":due",prefix+":payload");
    }
    /** 根据实际Mongo库名隔离，hash tag保证两key位于相同Redis Cluster槽。 */
    public static String keyPrefix(String namespace) {
        if(namespace==null||namespace.isBlank()) throw new IllegalArgumentException("namespace不得为空");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(namespace.getBytes(StandardCharsets.UTF_8)));
            return "ironoath:bot:{"+hash+"}";
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private boolean write(BotScheduler.BotTask task,boolean onlyAbsent) {
        if(task==null) throw new IllegalArgumentException("task不得为空");
        try {
            String body=mapper.writeValueAsString(new StoredTask(task.botId(),task.dueAt(),task.pending()));
            Number result=script.eval(RScript.Mode.READ_WRITE,WRITE,RScript.ReturnType.INTEGER,keys,
                    task.botId(),Long.toString(task.dueAt()),body,onlyAbsent?"NX":"SET");
            return result.longValue()==1;
        } catch(JsonProcessingException failure) { throw new IllegalStateException("Bot任务序列化失败",failure); }
    }
    @Override public void schedule(BotScheduler.BotTask task) { write(task,false); }
    @Override public boolean scheduleIfAbsent(BotScheduler.BotTask task) { return write(task,true); }

    @Override public List<BotScheduler.BotTask> dueBefore(long now,int limit) {
        if(limit<=0) return List.of();
        List<String> candidates=script.eval(RScript.Mode.READ_ONLY,READ,RScript.ReturnType.MULTI,keys,
                Long.toString(now),Integer.toString(limit));
        if(candidates.isEmpty()) return List.of();
        var decoded=new LinkedHashMap<String,BotScheduler.BotTask>();
        List<Object> arguments=new ArrayList<>();arguments.add(Long.toString(now));
        // 先验证整批，再按内容CAS原子摘除；坏JSON/未知动作保持在队列，不能读坏后偷偷丢任务。
        for(int index=0;index<candidates.size();index+=2) {
            String id=candidates.get(index),body=candidates.get(index+1);
            try {
                var task=mapper.readValue(body,StoredTask.class).task();
                if(!id.equals(task.botId())||task.dueAt()>now)
                    throw new IllegalStateException("Bot游标与任务载荷不一致，未摘除");
                decoded.put(id,task);arguments.add(id);arguments.add(body);
            } catch(JsonProcessingException failure) {
                throw new IllegalStateException("Bot任务载荷无法恢复，未摘除",failure);
            }
        }
        List<String> claimed=script.eval(RScript.Mode.READ_WRITE,CLAIM,RScript.ReturnType.MULTI,keys,
                arguments.toArray());
        return claimed.stream().map(decoded::get).toList();
    }
    @Override public void cancel(String id) {
        if(id==null) return;
        script.eval(RScript.Mode.READ_WRITE,"redis.call('HDEL',KEYS[2],ARGV[1]); return redis.call('ZREM',KEYS[1],ARGV[1])",
                RScript.ReturnType.INTEGER,keys,id);
    }
    @Override public int size() {
        Number size=script.eval(RScript.Mode.READ_ONLY,"return redis.call('ZCARD',KEYS[1])",
                RScript.ReturnType.INTEGER,keys);
        return Math.toIntExact(size.longValue());
    }
    /** 仅清该命名空间的两个key，供自有验收与管理收尾；绝不FLUSHDB。 */
    public void clear() {
        script.eval(RScript.Mode.READ_WRITE,"return redis.call('DEL',KEYS[1],KEYS[2])",RScript.ReturnType.INTEGER,keys);
    }
}
