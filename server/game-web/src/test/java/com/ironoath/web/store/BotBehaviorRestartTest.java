package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.Application;
import com.ironoath.web.bot.BotAttackLimiter;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;

/** 两个真实装配上下文共用自有Mongo，恢复时不重登记、不重写水位或频控账。 */
class BotBehaviorRestartTest {
    private Path configPath() {
        Path path=Path.of("../../contract/config");
        return Files.isDirectory(path)?path:Path.of("contract/config");
    }

    private ConfigurableApplicationContext start(String database) {
        var app=new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app.run("--spring.profiles.active=test","--ironoath.storage=mongo",
                "--spring.data.mongodb.uri="+TestMongo.uri(),"--spring.data.mongodb.database="+database,
                "--ironoath.config-dir="+configPath().toAbsolutePath().normalize());
    }

    private record Actors(String bot,String human) { }

    private Actors seed(ConfigurableApplicationContext context) {
        var init=context.getBean(PlayerInitService.class);
        long now=context.getBean(TimeService.class).serverNow();
        String bot=init.init(new PlayerInitReq(UUID.randomUUID().toString(),"owned-behavior-"+UUID.randomUUID(),"水位铁卫",now,"")).playerId();
        String human=init.init(new PlayerInitReq(UUID.randomUUID().toString(),"owned-behavior-"+UUID.randomUUID(),"水位旅人",now,"")).playerId();
        context.getBean(CityAppService.class).list(bot);
        context.getBean(CityAppService.class).list(human);
        context.getBean(BotRegistry.class).register(new BotProfile(bot,"bot_linju",
                new BotProfile.AiProfile(3_000,5_000,0,10_000),
                new BotProfile.Persona(42,7,99,IntStream.range(0,24).boxed().toList(),3,3,0),10_000));
        return new Actors(bot,human);
    }

    private void blocked(BotAttackLimiter limiter,Actors actors,long now) {
        assertThatThrownBy(()->limiter.requireQuota(actors.bot(),actors.human(),now))
                .isInstanceOfSatisfying(BizException.class,error->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.BOT_ATTACK_QUOTA_EXCEEDED));
    }

    @Test void uncertainHumanBattleCannotReplayInFreshMongoContext() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();Actors actors;String marchId;long defenders;
            try(var context=start(db.template().getDb().getName())) {
                actors=seed(context);var players=context.getBean(PlayerRepository.class);var world=context.getBean(com.ironoath.core.world.WorldRepository.class);
                assertThat(world.placeCity(actors.bot(),com.ironoath.core.world.Coord.of(256,256))).isTrue();
                assertThat(world.placeCity(actors.human(),com.ironoath.core.world.Coord.of(300,300))).isTrue();
                var armies=context.getBean(com.ironoath.core.army.ArmyRepository.class);
                for(String id:List.of(actors.bot(),actors.human())) {
                    var player=players.findByPlayerId(id).orElseThrow();player.setProtectUntil(null);players.save(player);
                    armies.insertIfAbsent(id,new com.ironoath.core.army.ArmyState());var army=armies.findByPlayerId(id).orElseThrow();long version=armies.versionOf(id);
                    army.add("unit_infantry_t1",1_200);armies.save(id,army,version);context.getBean(com.ironoath.web.service.PowerRefreshService.class).refresh(id);
                }
                var service=context.getBean(com.ironoath.web.service.MarchAppService.class);
                var home=context.getBean(com.ironoath.web.service.WorldAppService.class).homeOf(actors.bot());
                marchId=service.send(actors.human(),new com.ironoath.web.dto.generated.MarchReq(UUID.randomUUID().toString(),home.x(),home.y(),
                        List.of(new com.ironoath.web.dto.generated.MarchUnit("unit_infantry_t1",1_200)),List.of(),com.ironoath.web.dto.generated.MarchAction.ATTACK)).march().marchId();
                var refresh=org.mockito.Mockito.spy(context.getBean(com.ironoath.web.service.PowerRefreshService.class));
                org.mockito.Mockito.doThrow(new BizException(ErrorCode.SYSTEM_ERROR,"owned human post-loss fault")).when(refresh).refresh(actors.human());
                org.springframework.test.util.ReflectionTestUtils.setField(context.getBean(com.ironoath.web.battle.PlayerCityBattleService.class),"powerRefreshService",refresh);
                var marches=context.getBean(com.ironoath.core.march.MarchRepository.class);var march=marches.findById(marchId).orElseThrow();long version=marches.versionOf(marchId);
                march.restore(march.startAt(),march.returnArriveAt(),march.returnStartAt(),march.returnFrom(),march.load(),march.status(),march.gatherStartAt(),march.units());marches.save(march,version);
                context.getBean(com.ironoath.core.march.MarchDueQueue.class).reschedule(marchId,march.startAt());service.processDue(actors.human(),context.getBean(TimeService.class).serverNow());
                defenders=armies.findByPlayerId(actors.bot()).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L);
                assertThat(defenders).as("真人战斗确有战损，非前检拒绝").isLessThan(1_200);
            }
            try(var context=start(db.template().getDb().getName())) {
                var marches=context.getBean(com.ironoath.core.march.MarchRepository.class);var march=marches.findById(marchId).orElseThrow();
                // 新上下文显式重投旧due事件，证明持久隔离而非进程内旗标。
                context.getBean(com.ironoath.core.march.MarchDueQueue.class).reschedule(marchId,march.arriveAt());
                context.getBean(com.ironoath.web.service.MarchAppService.class).processDue(actors.human(),context.getBean(TimeService.class).serverNow()+3_600_000);
                assertThat(context.getBean(com.ironoath.core.army.ArmyRepository.class).findByPlayerId(actors.bot()).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L))
                        .as("没有Bot额度槽的真人行军在新实例也不能重打一仗").isEqualTo(defenders);
                assertThat(marches.findById(marchId).orElseThrow().status()).isEqualTo(com.ironoath.core.march.March.Status.FIGHTING);
                assertThatThrownBy(()->context.getBean(com.ironoath.web.service.MarchAppService.class).recall(actors.human(),
                        new com.ironoath.web.dto.generated.MarchIdReq(UUID.randomUUID().toString(),marchId))).isInstanceOf(BizException.class);
            }
        }
    }

    @Test void twoActualCounterAttacksInFreshContextsMustNotReuseGlobalRequestId() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();Actors actors;
            try(var first=start(db.template().getDb().getName())) {
                actors=seed(first);var registry=first.getBean(BotRegistry.class);var old=registry.profileOf(actors.bot());
                registry.register(new BotProfile(old.botId(),old.archetypeId(),new BotProfile.AiProfile(9_000,5_000,0,10_000),old.persona(),old.growthFactorFixed()));
            }
            for(int round=0;round<2;round++) {
                try(var context=start(db.template().getDb().getName())) {
                    assertThat(context.getBean(BotRegistry.class).profileOf(actors.bot()).ai().aggression()).isEqualTo(9_000); // 恢复两次都不重新register。
                    var players=context.getBean(PlayerRepository.class);var world=context.getBean(com.ironoath.core.world.WorldRepository.class);
                    if(round==0) {
                        assertThat(world.placeCity(actors.bot(),com.ironoath.core.world.Coord.of(256,256))).isTrue();
                        assertThat(world.placeCity(actors.human(),com.ironoath.core.world.Coord.of(300,300))).isTrue();
                    }
                    var armies=context.getBean(com.ironoath.core.army.ArmyRepository.class);
                    for(String id:List.of(actors.bot(),actors.human())) {
                        var save=players.findByPlayerId(id).orElseThrow();save.setProtectUntil(null);players.save(save);
                        armies.insertIfAbsent(id,new com.ironoath.core.army.ArmyState());
                        var army=armies.findByPlayerId(id).orElseThrow();long version=armies.versionOf(id);
                        army.add("unit_infantry_t1",500);armies.save(id,army,version);
                        context.getBean(com.ironoath.web.service.PowerRefreshService.class).refresh(id);
                    }
                    long hit=context.getBean(TimeService.class).serverNow()+round;
                    var save=players.findByPlayerId(actors.bot()).orElseThrow();save.setPvp(save.pvp().withAttackerHits(Map.of(actors.human(),hit)));players.save(save);
                    context.getBean(BotWorldAdapter.class).execute(actors.bot(),new BotDecisionTree.Decision(BotDecisionTree.Action.REACT_ATTACK,false,3_000,"实际跨上下文反击"),hit);
                    assertThat(context.getBean(com.ironoath.core.march.MarchRepository.class).activeCountOf(actors.bot()))
                            .as("新上下文的第二次实际反击不能因进程序号碰撞丢动作").isEqualTo(round+1);
                }
            }
        }
    }

    @Test void postBattleLedgerFaultKeepsMongoMarchAndActualReturnWithoutReplay() {
        for(boolean committedBeforeFault:List.of(false,true)) {
            try(TestMongo db=TestMongo.tryOpen()) {
                assertThat(db).isNotNull();
                try(var context=start(db.template().getDb().getName())) {
                    var actors=seed(context);var players=context.getBean(PlayerRepository.class);
                    var world=context.getBean(com.ironoath.core.world.WorldRepository.class);
                    assertThat(world.placeCity(actors.bot(),com.ironoath.core.world.Coord.of(256,256))).isTrue();
                    assertThat(world.placeCity(actors.human(),com.ironoath.core.world.Coord.of(300,300))).isTrue();
                    var armies=context.getBean(com.ironoath.core.army.ArmyRepository.class);
                    for(String id:List.of(actors.bot(),actors.human())) {
                        var player=players.findByPlayerId(id).orElseThrow();player.setProtectUntil(null);players.save(player);
                        armies.insertIfAbsent(id,new com.ironoath.core.army.ArmyState());
                        var army=armies.findByPlayerId(id).orElseThrow();long version=armies.versionOf(id);
                        army.add("unit_infantry_t1",1_200);armies.save(id,army,version);
                        context.getBean(com.ironoath.web.service.PowerRefreshService.class).refresh(id);
                    }
                    var marchService=context.getBean(com.ironoath.web.service.MarchAppService.class);
                    var target=context.getBean(com.ironoath.web.service.WorldAppService.class).homeOf(actors.human());
                    String id=marchService.send(actors.bot(),new com.ironoath.web.dto.generated.MarchReq(UUID.randomUUID().toString(),target.x(),target.y(),
                            List.of(new com.ironoath.web.dto.generated.MarchUnit("unit_infantry_t1",1_200)),List.of(),com.ironoath.web.dto.generated.MarchAction.ATTACK)).march().marchId();
                    var ledger=org.mockito.Mockito.spy(context.getBean(com.ironoath.web.bot.BotAttackLedgerStore.class));
                    var once=new java.util.concurrent.atomic.AtomicBoolean();
                    org.mockito.Mockito.doAnswer(invocation->{
                        if(once.compareAndSet(false,true)) {
                            if(committedBeforeFault) invocation.callRealMethod();
                            throw new IllegalStateException("owned Mongo ledger reply/write fault");
                        }
                        return invocation.callRealMethod();
                    }).when(ledger).record(org.mockito.ArgumentMatchers.any());
                    var limiter=context.getBean(BotAttackLimiter.class);
                    org.springframework.test.util.ReflectionTestUtils.setField(limiter,"ledger",ledger);
                    var marches=context.getBean(com.ironoath.core.march.MarchRepository.class);
                    var march=marches.findById(id).orElseThrow();long version=marches.versionOf(id);
                    march.restore(march.startAt(),march.returnArriveAt(),march.returnStartAt(),march.returnFrom(),march.load(),march.status(),march.gatherStartAt(),march.units());
                    marches.save(march,version);context.getBean(com.ironoath.core.march.MarchDueQueue.class).reschedule(id,march.startAt());
                    long now=context.getBean(TimeService.class).serverNow();marchService.processDue(actors.bot(),now);
                    var persisted=marches.findById(id).orElseThrow();
                    assertThat(persisted.status()).as("Mongo重新读取的是已保存战后返程，非同一内存对象").isEqualTo(com.ironoath.core.march.March.Status.RETURNING);
                    long survivors=persisted.totalUnits();
                    long defenders=armies.findByPlayerId(actors.human()).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L);
                    assertThat(ledger.occupied(actors.human(),now)).isEqualTo(1);
                    var reports=context.getBean(com.ironoath.web.battle.BattleReportStore.class);
                    assertThat(reports.reportsOf(actors.human())).hasSize(1);
                    limiter.evict(now);marchService.processDue(actors.bot(),now+3_600_000);
                    assertThat(marches.findById(id)).isEmpty();
                    assertThat(armies.findByPlayerId(actors.bot()).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(survivors);
                    assertThat(armies.findByPlayerId(actors.human()).orElseThrow().troops().getOrDefault("unit_infantry_t1",0L)).isEqualTo(defenders);
                    assertThat(reports.reportsOf(actors.human())).hasSize(1);assertThat(ledger.count(actors.human(),now)).isEqualTo(1);
                }
            }
        }
    }

    @Test void reactionWatermarkSurvivesFreshContextWithoutRespondingAgain() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).as("真实Mongo必须可用，跳过不算恢复验收").isNotNull();
            Actors actors;long hitAt;
            try(var first=start(db.template().getDb().getName())) {
                actors=seed(first);hitAt=first.getBean(TimeService.class).serverNow();
                var players=first.getBean(PlayerRepository.class);var save=players.findByPlayerId(actors.bot()).orElseThrow();
                save.setPvp(save.pvp().withAttackerHits(Map.of(actors.human(),hitAt)));players.save(save);
                var adapter=first.getBean(BotWorldAdapter.class);
                assertThat(adapter.observe(actors.bot(),hitAt).underAttack()).isTrue();
                // 受击和低攻击性是明确输入夹具；回应走真实adapter，当前规则选择龟缩。
                adapter.execute(actors.bot(),new BotDecisionTree.Decision(BotDecisionTree.Action.REACT_ATTACK,false,3_000,"持久水位夹具"),hitAt);
                assertThat(adapter.reactedUpToOf(actors.bot())).isEqualTo(hitAt);
                assertThat(adapter.observe(actors.bot(),hitAt+1).underAttack()).isFalse();
            }
            try(var second=start(db.template().getDb().getName())) {
                var adapter=second.getBean(BotWorldAdapter.class);
                assertThat(adapter.reactedUpToOf(actors.bot())).as("新上下文不能遗忘已回应水位").isEqualTo(hitAt);
                assertThat(adapter.observe(actors.bot(),hitAt+1).underAttack()).as("同一旧攻击不能再次进入待回应").isFalse();
            }
        }
    }

    @Test void actualQuotaRecordsSurviveFreshContext() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();Actors actors;long at;
            try(var first=start(db.template().getDb().getName())) {
                actors=seed(first);at=first.getBean(TimeService.class).serverNow();
                var limiter=first.getBean(BotAttackLimiter.class);
                for(int i=0;i<3;i++) limiter.recordAttack(actors.bot(),actors.human(),at+i);
                blocked(limiter,actors,at+10);
            }
            try(var second=start(db.template().getDb().getName())) {
                assertThat(second.getBean(BotRegistry.class).isBot(actors.bot())).isTrue();
                blocked(second.getBean(BotAttackLimiter.class),actors,at+10);
            }
        }
    }

    @Test void actualQuotaRecordsSurviveRuleHotReload() throws Exception {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();
            try(var context=start(db.template().getDb().getName())) {
                Actors actors=seed(context);long at=context.getBean(TimeService.class).serverNow();
                var limiter=context.getBean(BotAttackLimiter.class);
                for(int i=0;i<3;i++) limiter.recordAttack(actors.bot(),actors.human(),at+i);
                blocked(limiter,actors,at+10);
                var mapper=context.getBean(ObjectMapper.class);
                var root=mapper.readTree(Files.readString(configPath().resolve("global.json")));
                for(var row:root.path("rows")) if("BOT_ATTACK_LIMIT_PER_24H".equals(row.path("id").asText())) {
                    ((ObjectNode)row).put("value",2); // 仅自有上下文的降低上限夹具，不改配置文件。
                }
                context.getBean(ConfigRegistry.class).reload("global",GlobalCfg.class,mapper.writeValueAsString(root));
                blocked(limiter,actors,at+10);
            }
        }
    }
}
