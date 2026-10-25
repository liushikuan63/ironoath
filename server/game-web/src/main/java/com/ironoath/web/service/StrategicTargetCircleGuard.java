package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.web.world.StrategicTargetDefender;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** B08's shared configured √N circle check for a real strategic NPC or player garrison. */
@Service
public final class StrategicTargetCircleGuard {
    private final ConfigRegistry configs;
    private final StrategicTargetDefender defenders;
    private final PlayerRepository players;
    private final PowerService powers;
    private final PowerRefreshService refresh;
    private final AttackGuardService guard;
    private final WarCapitalTargetResolver capitalTargets;

    public StrategicTargetCircleGuard(ConfigRegistry configs,StrategicTargetDefender defenders,
            PlayerRepository players,PowerService powers,PowerRefreshService refresh,AttackGuardService guard) {
        this(configs,defenders,players,powers,refresh,guard,null);
    }

    @Autowired
    public StrategicTargetCircleGuard(ConfigRegistry configs,StrategicTargetDefender defenders,
            PlayerRepository players,PowerService powers,PowerRefreshService refresh,AttackGuardService guard,
            WarCapitalTargetResolver capitalTargets) {
        this.configs=Objects.requireNonNull(configs);this.defenders=Objects.requireNonNull(defenders);
        this.players=Objects.requireNonNull(players);this.powers=Objects.requireNonNull(powers);
        this.refresh=Objects.requireNonNull(refresh);this.guard=Objects.requireNonNull(guard);
        this.capitalTargets=capitalTargets;
    }

    /** Departure-time precheck; rally size uses the configured cap just like the existing B08 rally guard. */
    public void guardRallyStart(String attackerId,Coord target,int rallySize) {
        var directory=capitalTargets==null?WorldTargets.from(configs):capitalTargets.resolve(configs).targets();
        var fixed=directory.at(target);
        if(fixed==null)throw new IllegalArgumentException("战略圈层预检目标不在固定目标目录");
        var defender=defenders.read(fixed);
        long targetPower=matchPowerOf(defender);
        if(targetPower<=0)return;
        long attackerPower=refresh.refresh(attackerId).power().matchPower();
        guard.guardStrategicCircle(attackerPower,targetPower,rallySize);
    }

    /** Arrival-time recheck uses the defender snapshot already captured under the target operation lock. */
    public void guardArrival(long attackerMatchPower,StrategicTargetDefender.Defender defender,int rallySize) {
        if(defender==null)throw new IllegalArgumentException("战略到达圈层缺少原守军快照");
        long targetPower=matchPowerOf(defender);
        if(targetPower>0)guard.guardStrategicCircle(attackerMatchPower,targetPower,rallySize);
    }

    private long matchPowerOf(StrategicTargetDefender.Defender defender) {
        long troops=0;for(long count:defender.troops().values())troops=Math.addExact(troops,count);
        if(troops==0)return 0;
        if(defender.march()!=null) {
            return players.findByPlayerId(March.fromSnapshot(defender.march()).playerId())
                    .orElseThrow(()->new IllegalStateException("战略玩家守军战力存档缺失"))
                    .power().matchPower();
        }
        var npc=new ArmyState();defender.troops().forEach(npc::add);
        return powers.troopsPower(npc);
    }
}
