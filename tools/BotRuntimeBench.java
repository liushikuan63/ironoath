import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bot.*;
import com.ironoath.core.player.*;
import com.ironoath.core.world.*;
import com.ironoath.web.bot.*;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;

/** 有界外部负载夹具；生产 Runtime/World/Queue 原样委托，不改规则、预算或纳秒时钟。 */
public final class BotRuntimeBench {
    static final int COUNT = 5000;
    static final Sampler sampler = new Sampler();
    static final class MongoTransportOwner implements AutoCloseable {
        final io.netty.channel.nio.NioEventLoopGroup group;
        final String mode;
        MongoTransportOwner() {
            mode=Optional.ofNullable(System.getenv("BOT_BENCH_MONGO_TRANSPORT")).orElse("socket");
            if(!List.of("socket","netty").contains(mode))throw new IllegalArgumentException("Unknown transport fixture");
            group=mode.equals("netty")?new io.netty.channel.nio.NioEventLoopGroup(2):null;
        }
        public void close() {
            if(group!=null)group.shutdownGracefully(0,10,java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        }
        boolean terminated() { return group==null || group.isTerminated(); }
    }
    static final class Sampler {
        boolean enabled;
        Set<String> eligible = new HashSet<>();
        Map<String, Long> starts = new HashMap<>(), samples = new LinkedHashMap<>();
        Map<String, BotScheduler.BotTask> latest = new HashMap<>();
        String queueDelegateClass;
        String batchDelegateMethod;
        long bulkCalls,bulkAcknowledged,bulkUnconfirmed,bulkFallbackCalls;
        Map<String,Object> batchEvidence() {
            return Map.of("delegateMethod",Objects.toString(batchDelegateMethod,"not-initialized"),
                    "calls",bulkCalls,"acknowledgedTasks",bulkAcknowledged,
                    "unconfirmedTasks",bulkUnconfirmed,"legacyFallbackCalls",bulkFallbackCalls);
        }
        void begin() { enabled=true; eligible.clear(); starts.clear(); samples.clear(); }
        void batch(List<BotScheduler.BotTask> tasks) {
            eligible.clear(); starts.clear();
            if(enabled) for(var task:tasks) eligible.add(task.botId());
        }
        void start(String id) { if(enabled && eligible.remove(id)) starts.put(id, System.nanoTime()); }
        void finish(BotScheduler.BotTask task) {
            latest.put(task.botId(),task);
            Long started=starts.remove(task.botId());
            if(enabled && started!=null && samples.putIfAbsent(task.botId(),System.nanoTime()-started)!=null)
                throw new AssertionError("重复测量同一到期tick："+task.botId());
        }
    }
    /** Public signature is intentionally valid against both old and new TaskQueue interfaces. */
    static final class MeasuredQueue implements BotScheduler.TaskQueue {
        private final BotScheduler.TaskQueue delegate;
        private final Sampler sample;
        private final java.lang.reflect.Method bulkMethod;
        MeasuredQueue(BotScheduler.TaskQueue delegate,Sampler sample) {
            this.delegate=delegate;this.sample=sample;
            java.lang.reflect.Method found;
            try {found=BotScheduler.TaskQueue.class.getMethod("scheduleAll",List.class);}
            catch(NoSuchMethodException oldInterface) {found=null;}
            bulkMethod=found;
            if(found==null)sample.batchDelegateMethod="old-interface-single-write-fallback";
            else {
                try {sample.batchDelegateMethod=delegate.getClass().getMethod("scheduleAll",List.class).getDeclaringClass().getName();}
                catch(NoSuchMethodException invalidImplementation) {throw new IllegalStateException(invalidImplementation);}
            }
        }
        public void schedule(BotScheduler.BotTask task) {delegate.schedule(task);sample.finish(task);}
        public boolean scheduleIfAbsent(BotScheduler.BotTask task) {
            boolean added=delegate.scheduleIfAbsent(task);if(added)sample.finish(task);return added;
        }
        // No @Override: old candidate jars do not declare this method.
        public List<BotScheduler.BotTask> scheduleAll(List<BotScheduler.BotTask> tasks) {
            List<BotScheduler.BotTask> input=List.copyOf(tasks);Set<String> ids=new HashSet<>();
            for(var task:input)if(!ids.add(task.botId()))throw new IllegalArgumentException("Duplicate batch identity");
            if(input.isEmpty())return List.of();
            sample.bulkCalls++;
            if(bulkMethod==null) {
                sample.bulkFallbackCalls++;List<BotScheduler.BotTask> unconfirmed=new ArrayList<>();
                for(var task:input) {
                    try {delegate.schedule(task);}
                    catch(RuntimeException unavailable) {unconfirmed.add(task);sample.bulkUnconfirmed++;continue;}
                    sample.finish(task);sample.bulkAcknowledged++;
                }
                return List.copyOf(unconfirmed);
            }
            Set<BotScheduler.BotTask> unconfirmed=new HashSet<>();
            try {
                Object returned;
                try {returned=bulkMethod.invoke(delegate,input);}
                catch(java.lang.reflect.InvocationTargetException failure) {
                    Throwable cause=failure.getCause();
                    if(cause instanceof RuntimeException runtime)throw runtime;
                    if(cause instanceof Error error)throw error;
                    throw new IllegalStateException("Bulk delegate failed",cause);
                } catch(IllegalAccessException failure) {throw new IllegalStateException("Bulk delegate inaccessible",failure);}
                if(!(returned instanceof List<?> list))throw new IllegalStateException("Invalid bulk acknowledgement");
                for(Object item:list) {
                    if(!(item instanceof BotScheduler.BotTask task)||!input.contains(task)||!unconfirmed.add(task))
                        throw new IllegalStateException("Unknown/duplicate bulk acknowledgement");
                }
            } catch(RuntimeException | Error unavailable) {
                sample.bulkUnconfirmed+=input.size();
                throw unavailable;
            }
            sample.bulkUnconfirmed+=unconfirmed.size();
            for(var task:input)if(!unconfirmed.contains(task)) {sample.finish(task);sample.bulkAcknowledged++;}
            return input.stream().filter(unconfirmed::contains).toList();
        }
        public List<BotScheduler.BotTask> dueBefore(long now,int limit) {
            var tasks=delegate.dueBefore(now,limit);sample.batch(tasks);return tasks;
        }
        public void cancel(String id) {delegate.cancel(id);sample.latest.remove(id);}
        public int size() {return delegate.size();}
    }
    @Configuration(proxyBeanMethods=false)
    static class ProbeConfig {
        @Bean(destroyMethod="close")
        MongoTransportOwner benchMongoTransportOwner() { return new MongoTransportOwner(); }
        @Bean
        org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer benchTransport(MongoTransportOwner owner) {
            return builder->{
                if(owner.group!=null)builder.transportSettings(com.mongodb.connection.TransportSettings.nettyBuilder().eventLoopGroup(owner.group).build());
            };
        }
        @Bean @Primary
        TimeService benchTime() {
            String value=System.getenv("BOT_BENCH_BUSINESS_NOW");
            if(value==null)throw new IllegalArgumentException("BOT_BENCH_BUSINESS_NOW required for comparable inputs");
            long now=Long.parseLong(value);if(now<=0)throw new IllegalArgumentException("Fixed business timestamp required");
            return new TimeService(()->now);
        }
        @Bean @Primary
        BotScheduler.World benchWorld(BotWorldAdapter delegate) {
            return new BotScheduler.World() {
                public BotProfile profileOf(String id) { sampler.start(id); return delegate.profileOf(id); }
                public BotDecisionTree.WorldState observe(String id,long now) { return delegate.observe(id,now); }
                public void execute(String id,BotDecisionTree.Decision decision,long now) { delegate.execute(id,decision,now); }
                public long matchPowerOf(String id) { return delegate.matchPowerOf(id); }
                public long humanAverageMatchPower(long now) { return delegate.humanAverageMatchPower(now); }
            };
        }
        @Bean @Primary
        BotScheduler.TaskQueue benchQueue(@Value("${ironoath.bot.task-queue:memory}") String kind,ApplicationContext context) {
            if(!List.of("memory","redis").contains(kind))throw new IllegalArgumentException("Unsupported queue fixture "+kind);
            var delegate=context.getBean(kind.equals("redis")?"redissonBotTaskQueue":"botTaskQueue",BotScheduler.TaskQueue.class);
            sampler.queueDelegateClass=delegate.getClass().getName();
            return new MeasuredQueue(delegate,sampler);
        }
    }
    public static void main(String[] args) throws Exception {
        String destination=System.getenv("BOT_BENCH_OUT");
        if(destination==null) throw new IllegalArgumentException("BOT_BENCH_OUT required");
        Path out=Path.of(destination); Files.createDirectories(out);
        var app=new SpringApplication(com.ironoath.web.Application.class,ProbeConfig.class);
        var ctx=app.run(args); ObjectMapper mapper=ctx.getBean(ObjectMapper.class);
        Map<String,Object> result=new LinkedHashMap<>(); List<Map<String,Object>> phases=new ArrayList<>();
        boolean passed=false;var transport=ctx.getBean(MongoTransportOwner.class);
        try {
            var init=ctx.getBean(PlayerInitService.class);var time=ctx.getBean(TimeService.class);
            var registry=ctx.getBean(BotRegistry.class);var players=ctx.getBean(PlayerRepository.class);
            var world=ctx.getBean(WorldRepository.class);var rules=ctx.getBean(BotRulesAssembler.class);
            var runtime=ctx.getBean(BotRuntimeService.class);var queue=ctx.getBean(BotScheduler.TaskQueue.class);
            var config=ctx.getBean(com.ironoath.config.ConfigRegistry.class);
            String storage=ctx.getEnvironment().getProperty("ironoath.storage","memory");
            if(storage.equals("mongo")) {
                String db=ctx.getBean(org.springframework.data.mongodb.core.MongoTemplate.class).getDb().getName();
                if(!db.equals(System.getenv("BOT_BENCH_DATABASE")) || !db.startsWith("ironoath_bot_bench_"))
                    throw new AssertionError("Only explicitly owned benchmark database");
                result.put("database",db);
            }
            result.put("storageMode",storage);result.put("playerStoreClass",players.getClass().getName());
            result.put("profileStoreClass",ctx.getBean(BotProfileStore.class).getClass().getName());
            result.put("queueDelegateClass",sampler.queueDelegateClass);
            result.put("playerLockClass",ctx.getBean(com.ironoath.core.lock.PlayerLock.class).getClass().getName());
            result.put("mongoTransport",transport.mode);result.put("transportFixtureOverridesDefault",transport.group!=null);
            Files.writeString(out.resolve("implementation.actual.json"),mapper.writeValueAsString(result),StandardCharsets.UTF_8);
            long roundTarget=config.longParam("BOT_FULL_ROUND_BUDGET_MS"),tickTarget=config.longParam("BOT_TICK_BUDGET_MS");
            long seededAt=time.serverNow(),seedStart=System.nanoTime();
            var human=init.init(new PlayerInitReq(UUID.randomUUID().toString(),"bench-human-"+UUID.randomUUID(),"性能真人",seededAt,"")).playerId();
            if(!world.placeCity(human,Coord.of(5,5)))throw new AssertionError("独立真人城位");
            List<String> ids=new ArrayList<>();var archetypes=rules.archetypes();var rng=Rng.of(20261014L);
            for(int i=0;i<COUNT;i++) {
                String id=init.init(new PlayerInitReq(UUID.randomUUID().toString(),"bench-bot-"+UUID.randomUUID(),"性能城"+i,seededAt,"")).playerId();
                if(!world.placeCity(id,Coord.of(10+(i%100)*3,10+(i/100)*3))) throw new AssertionError("独立城位重复");
                var original=archetypes.get(i%archetypes.size()).spawnProfile(id,config.longParam("INIT_AVATAR_ID"),rng);
                var persona=original.persona();
                // 全员活跃仅为同时到期压力输入；其余AI/失误/成长仍为原配置种子装配。
                registry.register(new BotProfile(id,original.archetypeId(),original.ai(),new BotProfile.Persona(
                    persona.nameSeed(),persona.avatarId(),persona.cityNameSeed(),IntStream.range(0,24).boxed().toList(),
                    persona.reactionDelayMinSec(),persona.reactionDelayMaxSec(),persona.mistakeRateFixed()),original.growthFactorFixed()));
                ids.add(id);
                if(i%1000==999)System.out.println("BENCH_SEEDED "+(i+1));
            }
            result.put("seedMillis",(System.nanoTime()-seedStart)/1_000_000.0);
            result.put("count",ids.size());result.put("humanCount",1);result.put("rules",rules.schedulerRules());
            result.put("java",System.getProperty("java.runtime.version"));result.put("os",System.getProperty("os.name"));
            result.put("availableProcessors",Runtime.getRuntime().availableProcessors());result.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
            result.put("businessTime",seededAt);result.put("fixedBusinessTime",System.getenv("BOT_BENCH_BUSINESS_NOW")!=null);
            result.put("thresholds",Map.of("fullRoundMillis",roundTarget,"singleTickP99Millis",tickTarget));
            result.put("fixtureBoundary","5000新身份/规则六原型/全员活跃和到期时间为显式压力输入；可控业务时钟避免不同墙钟耗时造成升级自然完工差异，计时及生产调度预算仍真实System.nanoTime；真实Runtime/World/声明的生产Queue及玩家service，存储/锁/队列实现类和Mongo命名空间单独读回；逐tick时间含profile到真实重新排期、另计全Runtime冷登记/校准与队列开销。不是5000在线玩家或战斗峰值报告。");
            Map<String,Long> lastReport=null;
            for(int phase=0;phase<3;phase++) {
                long now=time.serverNow();
                // 保留原pending，只把当前游标设置为本轮到期，避免抹掉两阶段动作。
                for(String id:ids) {
                    var prior=sampler.latest.get(id);
                    queue.schedule(new BotScheduler.BotTask(id,now,prior==null?null:prior.pending()));
                }
                sampler.begin();long start=System.nanoTime(),processed=0;int calls=0;Map<String,Long> report=null;
                while(sampler.samples.size()<COUNT && calls<1000 && System.nanoTime()-start<180_000_000_000L) {
                    report=runtime.tick(now);processed+=report.get("processed");calls++;
                }
                double totalMillis=(System.nanoTime()-start)/1_000_000.0;sampler.enabled=false;
                List<Long> values=new ArrayList<>(sampler.samples.values());Collections.sort(values);
                double p99=values.isEmpty()?Double.POSITIVE_INFINITY:values.get((int)Math.ceil(values.size()*.99)-1)/1_000_000.0;
                Map<String,Object> phaseResult=new LinkedHashMap<>();phaseResult.put("phase",phase==0?"cold":"warm"+phase);
                phaseResult.put("totalMillis",totalMillis);phaseResult.put("p99Millis",p99);phaseResult.put("samples",values.size());
                phaseResult.put("processed",processed);phaseResult.put("runtimeCalls",calls);phaseResult.put("report",report);
                boolean correct=values.size()==COUNT&&processed==COUNT&&report!=null&&report.get("failed")==0L&&registry.size()==COUNT&&queue.size()==COUNT;
                phaseResult.put("correct",correct);phaseResult.put("budgetPassed",correct&&totalMillis<roundTarget&&p99<tickTarget);
                phases.add(phaseResult);result.put("phases",phases);
                Files.writeString(out.resolve("samples-"+phase+".actual.json"),mapper.writeValueAsString(sampler.samples),StandardCharsets.UTF_8);
                Files.writeString(out.resolve("progress.actual.json"),mapper.writeValueAsString(result),StandardCharsets.UTF_8);
                System.out.println("BENCH_PHASE "+mapper.writeValueAsString(phaseResult));
                if(!correct) throw new AssertionError("完整5000唯一tick与真实计数不一致");
                lastReport=report;
            }
            // 仓库读回证明真实动作写入，不以空跑或只推进processed冒充业务路径。
            long buildingActions=lastReport.get("upgraded");
            long calibrated=ids.stream().filter(id->players.findByPlayerId(id).orElseThrow().lastBotCalibrationAt()!=null).count();
            var cities=ctx.getBean(com.ironoath.core.city.CityRepository.class);
            long persistedUpgrades=0,changedCities=0;List<Map<String,Object>> cityEvidence=new ArrayList<>();
            for(String id:ids) {
                var city=cities.findByPlayerId(id).orElseThrow();var snapshot=city.snapshot();
                long pending=snapshot.buildings().stream().filter(building->
                    building.status()==com.ironoath.core.city.BuildingStatus.UPGRADING
                    && building.upgradeFinishAt()!=null && building.upgradeFinishAt()>seededAt
                    && building.upgradeStartedAt()==seededAt).count();
                if(pending>0)changedCities++;persistedUpgrades+=pending;
                cityEvidence.add(Map.of("playerId",id,"pendingUpgradeCount",pending,"citySnapshot",snapshot));
            }
            Files.writeString(out.resolve("city-readback.actual.json"),mapper.writeValueAsString(cityEvidence),StandardCharsets.UTF_8);
            result.put("calibratedPlayers",calibrated);result.put("buildingActions",buildingActions);
            result.put("persistedUpgrades",persistedUpgrades);result.put("changedCities",changedCities);
            if(calibrated!=COUNT || buildingActions==0 || persistedUpgrades!=buildingActions || registry.isBot(human))
                throw new AssertionError("真实升级排期/校准身份读回与动作计数不符");
            passed=phases.stream().allMatch(p->Boolean.TRUE.equals(p.get("budgetPassed")));
        } catch(Throwable error) {
            result.put("error",Map.of("type",error.getClass().getName(),"message",Objects.toString(error.getMessage(),"")));
            error.printStackTrace();
        } finally {
            result.put("queueBatchBridge",sampler.batchEvidence());
            result.put("phases",phases);ctx.close();result.put("contextClosed",true);
            result.put("transportEventLoopTerminated",transport.terminated());if(!transport.terminated())passed=false;
            result.put("passed",passed);
            Files.writeString(out.resolve("result.actual.json"),mapper.writeValueAsString(result),StandardCharsets.UTF_8);
        }
        if(!passed)System.exit(1);
    }
}
