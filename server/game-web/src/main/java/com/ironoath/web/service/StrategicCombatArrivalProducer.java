package com.ironoath.web.service;

import com.ironoath.battle.BattleModifier;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.social.Rally;
import com.ironoath.web.battle.CapturedBattlePreparation;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.reward.ServerSeedSource;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.world.StrategicCombatPublisher;
import com.ironoath.web.world.StrategicTargetDefender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** Captures the actual SIEGE Simulation at a locked strategic arrival and publishes its durable COMBAT. */
@Service
public class StrategicCombatArrivalProducer {
    private record MemberSource(PlayerSave player,CityRepository.VersionedCity city,Long heroVersion,Long inventoryVersion) {}

    private final ConfigRegistry configs;
    private final CapturedBattlePreparation preparation;
    private final StrategicTargetDefender defenders;
    private final StrategicCombatPublisher publisher;
    private final com.ironoath.core.march.MarchRepository marches;
    private final com.ironoath.core.player.PlayerRepository players;
    private final CityRepository cities;
    private final com.ironoath.core.hero.HeroRepository heroes;
    private final com.ironoath.core.bag.InventoryRepository inventory;
    private final SocialStore rallies;
    private final StrategicTargetCircleGuard circle;
    private final ServerSeedSource seeds;

    public StrategicCombatArrivalProducer(ConfigRegistry configs,CapturedBattlePreparation preparation,
            StrategicTargetDefender defenders,StrategicCombatPublisher publisher,
            com.ironoath.core.march.MarchRepository marches,com.ironoath.core.player.PlayerRepository players,
            CityRepository cities,com.ironoath.core.hero.HeroRepository heroes,
            com.ironoath.core.bag.InventoryRepository inventory,SocialStore rallies,
            StrategicTargetCircleGuard circle,ServerSeedSource seeds) {
        this.configs=Objects.requireNonNull(configs);this.preparation=Objects.requireNonNull(preparation);
        this.defenders=Objects.requireNonNull(defenders);this.publisher=Objects.requireNonNull(publisher);
        this.marches=Objects.requireNonNull(marches);this.players=Objects.requireNonNull(players);
        this.cities=Objects.requireNonNull(cities);this.heroes=Objects.requireNonNull(heroes);
        this.inventory=Objects.requireNonNull(inventory);this.rallies=Objects.requireNonNull(rallies);
        this.circle=Objects.requireNonNull(circle);
        this.seeds=Objects.requireNonNull(seeds);
    }

    /** Called only after arrival has durably changed the source March to FIGHTING. */
    public void publish(March original,long expectedVersion,long now) {
        if(original==null || original.targetType()!=March.TargetType.STRATEGIC_TARGET
                || original.action()!=March.Action.ATTACK || original.status()!=March.Status.FIGHTING
                || !original.isRallyMarch() || expectedVersion<0)
            throw new IllegalArgumentException("战略到达生产者必须消费已持久的原FIGHTING集结行军");
        requireMarch(original,expectedVersion);
        var attackerRally=rallies.rallyOf(original.rallyId())
                .orElseThrow(()->new IllegalStateException("原攻方国家集结缺失"));
        var scope=preparation.capture(now);var target=scope.readTarget(defenders,original.targetId());
        var sourceIds=new TreeSet<String>(attackerRally.participants().keySet());
        Rally defenderRally=null;PlayerSave defenderLeader=null;
        var defenderMarch=target.defender().march();
        if(defenderMarch!=null) {
            defenderRally=rallies.rallyOf(defenderMarch.rallyId())
                    .orElseThrow(()->new IllegalStateException("原守方国家集结缺失"));
            sourceIds.addAll(defenderRally.participants().keySet());
        }
        var sources=readMembers(sourceIds);
        var attackerLeader=sources.get(attackerRally.initiatorId()).player();
        if(defenderMarch!=null)defenderLeader=sources.get(defenderMarch.playerId()).player();

        circle.guardArrival(attackerLeader.power().matchPower(),target.defender(),attackerRally.participants().size());

        var attackerSide=scope.playerSide(original,expectedVersion,attackerLeader.tech(),attackerRally);
        var attackerHospitals=scope.prepareHospitals(hospitals(attackerRally.participants().keySet(),sources));
        CapturedBattlePreparation.PreparedHospitals defenderHospitals=null;
        if(defenderMarch!=null)
            defenderHospitals=scope.prepareHospitals(hospitals(defenderMarch.garrisonTroopsByPlayer().keySet(),sources));
        var simulation=scope.simulateTarget(attackerSide,target,defenderLeader==null?null:defenderLeader.tech(),
                defenderRally,seeds.nextSeed(),BattleModifier.none(),BattleModifier.none());
        CapturedStrategicBattle battle=scope.freezeTargetBattle(simulation,attackerHospitals,defenderHospitals);
        verifySources(battle,sources);
        publisher.publish(battle);
    }

    private Map<String,MemberSource> readMembers(Iterable<String> ids) {
        var out=new LinkedHashMap<String,MemberSource>();
        for(String id:ids) {
            var player=players.findByPlayerId(id).orElseThrow(()->new IllegalStateException("战斗成员玩家存档缺失"));
            var city=cities.findVersionedByPlayerId(id).orElseThrow(()->new IllegalStateException("战斗成员城市快照缺失"));
            Long heroVersion=heroes.findByPlayerId(id).isPresent()?heroes.versionOf(id):null;
            Long inventoryVersion=inventory.findByPlayerId(id).isPresent()?inventory.versionOf(id):null;
            out.put(id,new MemberSource(player,city,heroVersion,inventoryVersion));
        }
        return Map.copyOf(out);
    }

    private Map<String,CapturedBattlePreparation.MemberHospital> hospitals(Iterable<String> memberIds,
            Map<String,MemberSource> sources) {
        var out=new LinkedHashMap<String,CapturedBattlePreparation.MemberHospital>();
        for(String id:memberIds) {
            var source=sources.get(id);
            if(source==null)throw new IllegalStateException("实际医院成员不在冻结来源集合中");
            out.put(id,new CapturedBattlePreparation.MemberHospital(source.city().state(),source.player()));
        }
        return Map.copyOf(out);
    }

    private void verifySources(CapturedStrategicBattle battle,Map<String,MemberSource> sources) {
        requireMarch(March.fromSnapshot(battle.attacker().march()),battle.attacker().marchVersion());
        verifyRally(battle.attacker().rally());
        if(battle.defender()!=null) {
            requireMarch(March.fromSnapshot(battle.defender().march()),battle.defender().marchVersion());
            verifyRally(battle.defender().rally());
        }
        sources.forEach((id,source)->{
            if(players.findByPlayerId(id).map(PlayerSave::version).orElse(-1L)!=source.player().version()
                    || cities.findVersionedByPlayerId(id).map(CityRepository.VersionedCity::version).orElse(-1L)!=source.city().version()
                    || !sameOptionalVersion(source.heroVersion(),heroes.findByPlayerId(id),()->heroes.versionOf(id))
                    || !sameOptionalVersion(source.inventoryVersion(),inventory.findByPlayerId(id),()->inventory.versionOf(id)))
                throw new IllegalStateException("战斗成员科技/城建/英雄/装备源版本在模拟期间变化，拒绝发布旧战果");
        });
        var target=WorldTargets.from(configs).targets().stream()
                .filter(value->value.definition().id().equals(battle.target().targetId())).findFirst()
                .orElseThrow(()->new IllegalStateException("原战略目标在模拟期间移除"));
        if(!battle.target().defender().equals(defenders.read(target)))
            throw new IllegalStateException("原战略目标守军在模拟期间变化");
    }

    private void verifyRally(StrategicRallyCasualties.RallyIdentity frozen) {
        var current=rallies.rallyOf(frozen.rallyId()).orElseThrow(()->new IllegalStateException("原战斗集结在模拟期间缺失"));
        if(!frozen.equals(StrategicRallyCasualties.RallyIdentity.freeze(current)))
            throw new IllegalStateException("原战斗集结成员/状态在模拟期间变化");
    }

    private void requireMarch(March march,long version) {
        var current=marches.findById(march.id()).orElseThrow(()->new IllegalStateException("原战略战斗行军缺失"));
        if(marches.versionOf(march.id())!=version || !current.snapshot().equals(march.snapshot()))
            throw new IllegalStateException("原战略战斗行军版本/兵权在模拟期间变化");
    }

    private static boolean sameOptionalVersion(Long expected,Optional<?> current,LongSupplier version) {
        return expected==null?current.isEmpty():current.isPresent() && version.getAsLong()==expected;
    }

}
