package com.ironoath.web.battle;

import com.ironoath.battle.*;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.city.CityState;
import com.ironoath.core.march.March;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.social.Rally;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.world.StrategicTargetDefender;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * 一次捕获的配置图生成原输入并亲自调用内核；不接外来BattleResult冒充同配置战果。
 * 这是只读准备组件，调用者仍须取得原玩家/集结/目标操作权并冻结真实存档版本。
 * 科技/城市事实由调用者提供，英雄/装备/组织读取仍须在其约定锁内，不能把配置隔离当跨库存档事务。
 */
@Component
public final class CapturedBattlePreparation {
    private final ConfigRegistry configs;
    private final HeroBattleMapper heroes;
    private final BattleTechBonuses tech;
    private final NationPolicyBonuses policies;
    public CapturedBattlePreparation(ConfigRegistry configs,HeroBattleMapper heroes,
                                     BattleTechBonuses tech,NationPolicyBonuses policies) {
        this.configs=Objects.requireNonNull(configs);this.heroes=Objects.requireNonNull(heroes);
        this.tech=Objects.requireNonNull(tech);this.policies=Objects.requireNonNull(policies);
    }
    public Scope capture(long happenedAt) {
        if(happenedAt<0)throw new IllegalArgumentException("原战斗时点不得为负");
        var view=configs.readOnlySnapshot();
        return new Scope(view,heroes.usingConfiguration(view),tech.usingConfiguration(view),
                policies.usingConfiguration(view),happenedAt);
    }

    public static final class PreparedSide {
        private final Scope owner;
        private final March.Snapshot march;
        private final BattleArmyFactory.Folded folded;
        private final ArmySide army;
        private final Map<String,Long> unitsById;
        private final long marchVersion;
        private final StrategicRallyCasualties.RallyIdentity rally;
        private final TargetInput target;
        private PreparedSide(Scope owner,March.Snapshot march,BattleArmyFactory.Folded folded,ArmySide army,
                             Map<String,Long> unitsById,long marchVersion,StrategicRallyCasualties.RallyIdentity rally) {
            this.owner=owner;this.march=march;this.folded=folded;this.army=army;this.unitsById=unitsById;
            this.marchVersion=marchVersion;this.rally=rally;this.target=null;
        }
        private PreparedSide(PreparedSide side,TargetInput target) {
            this.owner=side.owner;this.march=side.march;this.folded=side.folded;this.army=side.army;this.unitsById=side.unitsById;
            this.marchVersion=side.marchVersion;this.rally=side.rally;this.target=target;
        }
        public ArmySide army(){return army;}
    }
    public static final class TargetInput {
        private final Scope owner;
        private final WorldTargets.Target target;
        private final StrategicTargetDefender.Defender defender;
        private TargetInput(Scope owner,WorldTargets.Target target,StrategicTargetDefender.Defender defender) {
            this.owner=owner;this.target=target;this.defender=defender;
        }
        public WorldTargets.Target target(){return target;}
        public StrategicTargetDefender.Defender defender(){return defender;}
    }
    public record MemberHospital(CityState city,PlayerSave player) {
        public MemberHospital {Objects.requireNonNull(city,"成员原城市缺失");Objects.requireNonNull(player,"成员原科技档缺失");}
    }
    public static final class PreparedHospitals {
        private final Scope owner;
        private final Map<String,Long> capacities;
        private PreparedHospitals(Scope owner,Map<String,Long> capacities){this.owner=owner;this.capacities=capacities;}
        public Map<String,Long> capacities(){return capacities;}
    }
    /** 仅Scope可构造；Input/Result均为该次真实simulate的原对象，恢复不应再次调用simulate。 */
    public static final class Simulation {
        private final Scope owner;
        private final PreparedSide attacker,defender;
        private final BattleInput input;
        private final BattleResult result;
        private Simulation(Scope owner,PreparedSide attacker,PreparedSide defender,BattleInput input,BattleResult result) {
            this.owner=owner;this.attacker=attacker;this.defender=defender;this.input=input;this.result=result;
        }
        public BattleInput input(){return input;}
        public BattleResult result(){return result;}
        public TargetInput target(){return defender.target;}
    }
    public static final class Scope {
        private final ConfigRegistry view;
        private final BattleArmyFactory armies;
        private final HeroBattleMapper heroes;
        private final BattleTechBonuses tech;
        private final NationPolicyBonuses policies;
        private final long happenedAt;
        private Scope(ConfigRegistry view,HeroBattleMapper heroes,BattleTechBonuses tech,
                      NationPolicyBonuses policies,long happenedAt) {
            this.view=view;this.armies=new BattleArmyFactory(view);this.heroes=heroes;this.tech=tech;
            this.policies=policies;this.happenedAt=happenedAt;
        }
        /** 仅供版本追踪，版本字符串并非内容SHA或跨Scope兵权凭证。 */
        public String fingerprint(){return view.fingerprint();}
        public PreparedSide neutralSide(String sideId,Map<String,Long> troops) {
            var frozen=freezeTroops(troops);var folded=armies.fold(frozen);
            var army=armies.toSide(sideId,folded,List.of(),0L,TechBonus.none(),Long.MAX_VALUE,OrgBonus.none());
            return new PreparedSide(this,null,folded,army,frozen,-1,null);
        }
        public PreparedSide playerSide(March source,long originalMarchVersion,PlayerTech commanderTech,Rally originalRally) {
            Objects.requireNonNull(source,"原行军缺失");Objects.requireNonNull(commanderTech,"原指挥者科技缺失");
            if(originalMarchVersion<0)throw new IllegalArgumentException("原行军版本无效");
            var frozen=March.fromSnapshot(source.snapshot());
            if(happenedAt<frozen.arriveAt())throw new IllegalArgumentException("原战斗时点早于行军到达，不能提前取国策");
            requireNonempty(frozen.units());var folded=armies.fold(frozen.units());
            var identity=StrategicRallyCasualties.RallyIdentity.freeze(originalRally);
            var side=frozen.hasGarrisonOwnership()?StrategicRallyCasualties.Side.DEFENDER:StrategicRallyCasualties.Side.ATTACKER;
            StrategicRallyCasualties.requireOriginalMarch(frozen,identity,side);
            var heroSnapshots=heroes.snapshotsForMarch(frozen,originalRally);
            if(!identity.equals(StrategicRallyCasualties.RallyIdentity.freeze(originalRally)))throw new IllegalStateException("原集结在准备期间已变化");
            var members=memberSides(frozen,identity,side);
            var army=armies.toSide(frozen.playerId(),folded,heroSnapshots,0L,
                    tech.forPlayer(frozen.playerId(),commanderTech),Long.MAX_VALUE,
                    policies.combatBonusForAt(frozen.playerId(),happenedAt).plus(armies.rallyBonus(frozen)),members);
            return new PreparedSide(this,frozen.snapshot(),folded,army,freezeTroops(frozen.units()),originalMarchVersion,identity);
        }
        private List<BattleMember> memberSides(March march,StrategicRallyCasualties.RallyIdentity identity,
                                               StrategicRallyCasualties.Side side) {
            var current=new TreeMap<String,Map<String,Long>>();
            if(side==StrategicRallyCasualties.Side.ATTACKER)
                identity.participants().forEach((player,participant)->current.put(player,participant.troops()));
            else current.putAll(march.garrisonTroopsByPlayer());
            var result=new ArrayList<BattleMember>(current.size());
            current.forEach((player,units)->{
                var types=new LinkedHashMap<String,UnitType>();units.keySet().forEach(id->types.put(id,armies.unitTypeOf(id)));
                result.add(new BattleMember(player,units,types));
            });
            return List.copyOf(result);
        }
        /** 初始NPC构成也从本视图读取；实际余兵/归属/行军仍来自原仓储并由读取口复查版本。 */
        public TargetInput readTarget(StrategicTargetDefender reader,String targetId) {
            var target=WorldTargets.from(view).targets().stream().filter(t->t.definition().id().equals(targetId))
                    .findFirst().orElseThrow(()->new IllegalArgumentException("捕获视图中没有该战略目标"));
            return new TargetInput(this,target,Objects.requireNonNull(reader).readFromConfiguration(target,view));
        }
        public PreparedSide defenderSide(TargetInput original,PlayerTech commanderTech,Rally originalRally) {
            requireTarget(original);var defender=original.defender;PreparedSide prepared;
            if(defender.march()==null)prepared=neutralSide("strategic:"+original.target.definition().id(),defender.troops());
            else {
                prepared=playerSide(March.fromSnapshot(defender.march()),defender.marchVersion(),commanderTech,originalRally);
                if(defender.occupation()==null || !prepared.rally.nationId().equals(defender.occupation().nationId()))
                    throw new IllegalArgumentException("原驻军集结与目标占领国家不一致");
            }
            return new PreparedSide(prepared,original);
        }
        /** 原始组战果不先套队长医院；各成员容量及实际收容必须另存/确认后才形成最终战报。 */
        public long hospitalCapacity(CityState city,PlayerSave save) {
            return ArmyAppService.hospitalCapacityFromCapturedConfig(view,
                    Objects.requireNonNull(city,"成员原城市缺失"),Objects.requireNonNull(save,"成员原科技档缺失"));
        }
        public PreparedHospitals prepareHospitals(Map<String,MemberHospital> members) {
            if(members==null)throw new IllegalArgumentException("成员医院原事实缺失");
            Map<String,Long> capacities=new java.util.TreeMap<>();
            for(var entry:members.entrySet()) {
                if(entry.getKey()==null || entry.getKey().isBlank() || entry.getValue()==null)
                    throw new IllegalArgumentException("成员医院身份/原事实无效");
                capacities.put(entry.getKey(),hospitalCapacity(entry.getValue().city(),entry.getValue().player()));
            }
            return new PreparedHospitals(this,Collections.unmodifiableMap(capacities));
        }
        public Simulation simulate(PreparedSide attacker,PreparedSide defender,TerrainType terrain,long seed,
                                   BattleType type,BattleModifier attackModifier,BattleModifier defenseModifier) {
            requireSide(attacker);requireSide(defender);
            if((attacker.march!=null && attacker.march.status()!=March.Status.FIGHTING)
                    || (defender.march!=null && defender.march.status()!=March.Status.STATIONED))
                throw new IllegalArgumentException("原战略行军的攻守角色与模拟位置不一致");
            if((attacker.march!=null || defender.march!=null) && type!=BattleType.SIEGE)
                throw new IllegalArgumentException("真实战略行军必须使用SIEGE攻城战，不能按有无国家归属切换类型");
            if(attacker.march!=null && defender.march!=null && (!attacker.march.targetId().equals(defender.march.targetId())
                    || !attacker.march.to().equals(defender.march.to())))
                throw new IllegalArgumentException("原攻守行军的战略目标ID或坐标不一致");
            if(attacker.march!=null && defender.target!=null && (!attacker.march.targetId().equals(defender.target.target.definition().id())
                    || !attacker.march.to().equals(defender.target.target.coord())))
                throw new IllegalArgumentException("原进攻行军与捕获的战略目标不一致");
            var input=new BattleInput(attacker.army,defender.army,terrain,seed,type,attackModifier,defenseModifier,
                    attacker.folded.stats(),defender.folded.stats(),new BattleRulesAssembler(view).rules(),null);
            return new Simulation(this,attacker,defender,input,BattleSimulator.simulate(input));
        }
        /** 2026-10-23用户裁定：要塞/关卡/王城的NPC或玩家驻军均用SIEGE；不等于已取得争夺权。 */
        public Simulation simulateStrategic(PreparedSide attacker,PreparedSide defender,TerrainType terrain,long seed,
                                            BattleModifier attackModifier,BattleModifier defenseModifier) {
            return simulate(attacker,defender,terrain,seed,BattleType.SIEGE,attackModifier,defenseModifier);
        }
        /** 面向真实目标的只读准备入口；仍不执行统一闸门/占位准入或取得目标写入权。 */
        public Simulation simulateTarget(PreparedSide attacker,TargetInput target,PlayerTech defenderTech,Rally defenderRally,
                                        TerrainType terrain,long seed,BattleModifier attackModifier,BattleModifier defenseModifier) {
            requireSide(attacker);requireTarget(target);
            if(attacker.march==null)throw new IllegalArgumentException("战略进攻必须使用原国家集结行军");
            var original=March.fromSnapshot(attacker.march);
            if(!original.targetId().equals(target.target.definition().id()) || !original.to().equals(target.target.coord()))
                throw new IllegalArgumentException("原进攻行军与捕获的战略目标不一致");
            if(terrain!=target.target.battleTerrain())
                throw new IllegalArgumentException("战略战斗地形必须使用捕获目标目录的原地形");
            return simulateStrategic(attacker,defenderSide(target,defenderTech,defenderRally),terrain,seed,attackModifier,defenseModifier);
        }
        /** 生产目标战斗从同一冻结目标目录取地形；调用方不能自行给目标换地形。 */
        public Simulation simulateTarget(PreparedSide attacker,TargetInput target,PlayerTech defenderTech,Rally defenderRally,
                                        long seed,BattleModifier attackModifier,BattleModifier defenseModifier) {
            return simulateTarget(attacker,target,defenderTech,defenderRally,target.target.battleTerrain(),seed,
                    attackModifier,defenseModifier);
        }
        public StrategicRallyCasualties freezePlayerCasualties(Simulation battle,StrategicRallyCasualties.Side side,PreparedHospitals hospitals) {
            if(battle==null || battle.owner!=this || side==null)throw new IllegalArgumentException("只能冻结同一捕获视图的原模拟战果");
            if(hospitals==null || hospitals.owner!=this)throw new IllegalArgumentException("医院最大容量必须来自同一捕获配置视图");
            var selected=side==StrategicRallyCasualties.Side.ATTACKER?battle.attacker:battle.defender;
            if(selected.march==null)throw new IllegalArgumentException("NPC没有玩家成员兵权，不得生成玩家医院账");
            return armies.freezeStrategicCasualtiesFromIdentity(March.fromSnapshot(selected.march),selected.marchVersion,
                    selected.rally,battle.result,side,hospitals.capacities);
        }
        /** 仅冻结本Scope真实目标战果；写入操作槽及恢复仍需调用者持权，不重查配置或重模拟。 */
        public CapturedStrategicBattle freezeTargetBattle(Simulation battle,PreparedHospitals attackerHospitals,
                                                           PreparedHospitals defenderHospitals) {
            if(battle==null || battle.owner!=this || battle.target()==null)
                throw new IllegalArgumentException("持久目标载荷必须来自同Scope的真实目标模拟");
            var attack=freezePlayerCasualties(battle,StrategicRallyCasualties.Side.ATTACKER,attackerHospitals);
            var original=battle.target();var target=original.target;
            var facts=new CapturedStrategicBattle.TargetFacts(target.definition().id(),
                    com.ironoath.core.world.WorldGenerator.EntityType.valueOf(target.definition().kind().name()),
                    target.coord().x(),target.coord().y(),original.defender);
            StrategicRallyCasualties defense=null;Map<String,UnitType> types=new LinkedHashMap<>();Map<String,Long> survivors=Map.of();
            if(battle.defender.march==null) {
                if(defenderHospitals!=null)throw new IllegalArgumentException("NPC不得伪带玩家医院");
                original.defender.troops().keySet().forEach(id->types.put(id,armies.unitTypeOf(id)));
                survivors=npcSurvivors(battle,StrategicRallyCasualties.Side.DEFENDER);
            } else defense=freezePlayerCasualties(battle,StrategicRallyCasualties.Side.DEFENDER,defenderHospitals);
            return new CapturedStrategicBattle(2,happenedAt,fingerprint(),facts,battle.input,battle.result,
                    attack,defense,types,survivors);
        }
        /** 只冻结实际NPC余兵，不决定是否占领/退役，也不把败方幸存者伪记为击杀。 */
        public Map<String,Long> npcSurvivors(Simulation battle,StrategicRallyCasualties.Side side) {
            if(battle==null || battle.owner!=this || side==null)throw new IllegalArgumentException("只能冻结同一捕获视图的NPC原战果");
            var selected=side==StrategicRallyCasualties.Side.ATTACKER?battle.attacker:battle.defender;
            if(selected.march!=null)throw new IllegalArgumentException("玩家成员必须走实际兵权分配，不得当作NPC");
            var survivors=side==StrategicRallyCasualties.Side.ATTACKER?battle.result.atkSurvivors():battle.result.defSurvivors();
            var losses=armies.unfoldLosses(selected.unitsById,selected.folded.counts(),survivors);
            Map<String,Long> remaining=new LinkedHashMap<>();
            selected.unitsById.forEach((unit,count)->{
                long n=Math.subtractExact(count,losses.getOrDefault(unit,0L));
                if(n<0)throw new IllegalStateException("NPC实际战损超过原兵力");if(n>0)remaining.put(unit,n);
            });
            return Collections.unmodifiableMap(remaining);
        }
        private void requireSide(PreparedSide side) {
            if(side==null || side.owner!=this)throw new IllegalArgumentException("攻守输入必须来自同一捕获配置视图");
        }
        private void requireTarget(TargetInput target) {
            if(target==null || target.owner!=this)throw new IllegalArgumentException("目标必须来自同一捕获配置视图");
        }
        private static void requireNonempty(Map<String,Long> troops) {
            if(troops==null)throw new IllegalArgumentException("原兵力缺失");long total=0;
            for(var count:troops.values()) {
                if(count==null || count<0)throw new IllegalArgumentException("原兵力无效");total=Math.addExact(total,count);
            }
            if(total==0)throw new IllegalArgumentException("零守军须走明确无抵抗占领，不制造假兵进入内核");
        }
        private static Map<String,Long> freezeTroops(Map<String,Long> troops) {
            if(troops==null)throw new IllegalArgumentException("原兵力缺失");
            var copy=new LinkedHashMap<>(troops);requireNonempty(copy);return Collections.unmodifiableMap(copy);
        }
    }
}
