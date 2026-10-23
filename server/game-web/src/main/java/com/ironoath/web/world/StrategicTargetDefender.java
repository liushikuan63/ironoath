package com.ironoath.web.world;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.social.Rally;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialStore;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 战略目标守军的只读战前输入。仅从无中立交战存档的目标取初始NPC；保存后使用真实余兵。
 * 已占空兵是零防守，退出中立后的归属缺档必须拒绝，
 * 旧档未知或断开的兵权引用必须停止争夺，不用配置守军掩盖资料缺失。
 * 本读取不申请战斗写入权；调用方仍须以冻结的目标/行军版本取得结算归属。
 */
@Component
public final class StrategicTargetDefender {
    public record Defender(TargetOccupationStore.Snapshot occupation, Long marchVersion,
                           March.Snapshot march, Map<String,Long> troops, boolean initialNpc,
                           NeutralTargetGarrisonStore.Snapshot neutral) {
        public Defender { troops=Map.copyOf(troops); }
    }
    private final ConfigRegistry configs;
    private final TargetOccupationStore occupations;
    private final MarchRepository marches;
    private final SocialStore social;
    private final NeutralTargetGarrisonStore neutralGarrisons;
    public StrategicTargetDefender(ConfigRegistry configs,TargetOccupationStore occupations,
                                   MarchRepository marches,SocialStore social,NeutralTargetGarrisonStore neutralGarrisons) {
        this.configs=configs;this.occupations=occupations;this.marches=marches;this.social=social;
        this.neutralGarrisons=Objects.requireNonNull(neutralGarrisons);
        validateNeutralCatalog(WorldTargets.from(configs));
        configs.addReloadGuard("world_target","neutral-garrison",candidate->validateNeutralCatalog(WorldTargets.from(candidate)));
    }

    public Defender read(WorldTargets.Target target) {
        Objects.requireNonNull(target,"战略目标不得为空");
        var id=target.definition().id();var owner=owner(id);var neutral=neutral(id);
        if(neutral!=null)requireNeutralAnchor(target,neutral);
        if(owner==null) {
            if(neutral!=null && neutral.phase()==NeutralTargetGarrisonStore.Phase.RETIRED)
                throw new IllegalStateException("目标已退出中立但归属档缺失，拒绝恢复配置守军");
            var input=new Defender(null,null,null,neutral==null?initialTroops(target):neutral.troops(),neutral==null,neutral);
            requireSameState(id,null,neutral);return input;
        }
        if(neutral!=null && neutral.phase()!=NeutralTargetGarrisonStore.Phase.RETIRED)
            throw new IllegalStateException("目标归属与仍有效的中立守军矛盾，须先恢复原操作");
        if(owner.x()!=target.coord().x() || owner.y()!=target.coord().y()
                || !owner.kind().name().equals(target.definition().kind().name()))
            throw new IllegalStateException("战略目标归属锚点与配置绑定不一致");
        var ref=owner.garrison();
        if(ref==null)throw new IllegalStateException("已占目标驻军资料未建立，不能推定为空或恢复配置守军");
        if(ref.kind()==TargetOccupationStore.Garrison.Kind.EMPTY) {
            requireSameState(id,owner,neutral);return new Defender(owner,null,null,Map.of(),false,neutral);
        }
        long version=marches.versionOf(ref.marchId());
        var march=marches.findById(ref.marchId()).orElseThrow(
                ()->new IllegalStateException("目标绑定的实际驻军行军缺失，拒绝恢复配置守军"));
        if(!march.hasGarrisonOwnership() || march.targetType()!=March.TargetType.STRATEGIC_TARGET
                || !id.equals(march.targetId()) || !target.coord().equals(march.to()) || !march.isRallyMarch())
            throw new IllegalStateException("目标与驻军行军的兵权绑定不一致");
        if(march.status()!=March.Status.STATIONED)
            throw new IllegalStateException("目标驻军正在交战或返程，不能重复作为驻守战斗输入");
        requireOriginalRally(march,owner,target);
        // 只排除读取期间的混档；战斗写入仍须消费这里冻结的原版本。
        var frozen=March.fromSnapshot(march.snapshot()).snapshot();
        if(marches.versionOf(ref.marchId())!=version)
            throw new IllegalStateException("实际驻军版本已变化，请重读后再申请战斗");
        requireSameState(id,owner,neutral);
        return new Defender(owner,version,frozen,frozen.units(),false,neutral);
    }

    /** 待恢复计划可能已写RETURNING；原集结身份仍须验证，不能因此回退到旧比例/旧成员。 */
    public void requireOriginalRally(March march,TargetOccupationStore.Snapshot owner,WorldTargets.Target target) {
        var rally=social.rallyOf(march.rallyId()).orElseThrow(
                ()->new IllegalStateException("实际驻军原集结缺失，不能推定国家与兵权"));
        if(rally.status()!=Rally.Status.DEPARTED && rally.status()!=Rally.Status.ARRIVED)
            throw new IllegalStateException("实际驻军原集结状态无效，尚未出发或已取消");
        if(!march.playerId().equals(rally.initiatorId()))
            throw new IllegalStateException("实际驻军指挥者与原集结发起人不一致");
        if(rally.scope()!=Rally.Scope.NATION || !owner.nationId().equals(rally.groupId()))
            throw new IllegalStateException("实际驻军集结与占领国家不一致");
        if(rally.targetX()!=target.coord().x() || rally.targetY()!=target.coord().y()
                || !"STRATEGIC_TARGET".equals(rally.targetType()))
            throw new IllegalStateException("实际驻军与原集结目标绑定不一致");
        for(var player:march.garrisonTroopsByPlayer().entrySet()) {
            var participant=rally.participant(player.getKey());
            if(participant==null)throw new IllegalStateException("实际驻军出现非原集结成员");
            for(var unit:player.getValue().entrySet())
                if(unit.getValue()>participant.troops().getOrDefault(unit.getKey(),0L))
                    throw new IllegalStateException("实际驻军兵权超过原集结出兵承诺");
        }
    }

    private TargetOccupationStore.Snapshot owner(String id) {
        return occupations.findByTargetIds(List.of(id)).get(id);
    }
    private NeutralTargetGarrisonStore.Snapshot neutral(String id) {
        return neutralGarrisons.findByTargetIds(List.of(id)).get(id);
    }
    private void requireSameState(String id,TargetOccupationStore.Snapshot before,NeutralTargetGarrisonStore.Snapshot neutralBefore) {
        if(!Objects.equals(before,owner(id)))throw new IllegalStateException("目标归属已变化，请重读后再申请战斗");
        if(!Objects.equals(neutralBefore,neutral(id)))throw new IllegalStateException("中立守军已变化，请重读后再申请战斗");
    }
    private void validateNeutralCatalog(WorldTargets targets) {
        for(var snapshot:neutralGarrisons.findAll(targets.targets().size()+1).values()) {
            var target=targets.at(com.ironoath.core.world.Coord.of(snapshot.x(),snapshot.y()));
            if(target==null)throw new IllegalStateException("中立守军目标被移位或移除，须先制定迁移："+snapshot.targetId());
            requireNeutralAnchor(target,snapshot);
        }
    }
    private void requireNeutralAnchor(WorldTargets.Target target,NeutralTargetGarrisonStore.Snapshot snapshot) {
        if(!snapshot.targetId().equals(target.definition().id()) || snapshot.x()!=target.coord().x()
                || snapshot.y()!=target.coord().y() || !snapshot.kind().name().equals(target.definition().kind().name()))
            throw new IllegalStateException("中立守军目标被移位、改类型或移除，须先制定迁移："+snapshot.targetId());
    }
    private Map<String,Long> initialTroops(WorldTargets.Target target) {
        var row=target.defender();Map<String,Long> troops=new LinkedHashMap<>();
        var units=configs.all(UnitCfg.class);
        for(var type:UnitCfg.Type.values()) {
            long count=switch(type) {
                case INFANTRY -> row.infantryCount();case CAVALRY -> row.cavalryCount();
                case ARCHER -> row.archerCount();case SIEGE -> row.siegeCount();
            };
            var matching=units.stream().filter(u->u.type()==type && u.tier()==row.unitTier()).toList();
            if(matching.size()!=1)throw new IllegalStateException("战略中立守军兵种配置缺失或重复："+type);
            if(count>0L)troops.put(matching.get(0).id(),count);
        }
        return troops;
    }
}
