package com.ironoath.web.battle;

import com.ironoath.battle.BattleType;
import java.util.HashSet;
import java.util.List;

/** Report identities frozen with the original COMBAT publication; recovery must not consult live names or TTL. */
public record StrategicBattleReportPlan(int formatVersion,String operationId,long createdAt,long expiresAt,
        BattleType battleType,List<Recipient> recipients) {
    public record Recipient(String reportId,String ownerId,String attackerId,String defenderId,
            String defenderName,String attackerName,List<String> attackerHeroIds,List<String> defenderHeroIds) {
        public Recipient {
            text(reportId,"reportId");text(ownerId,"ownerId");text(attackerId,"attackerId");text(defenderId,"defenderId");
            attackerHeroIds=attackerHeroIds==null?List.of():List.copyOf(attackerHeroIds);
            defenderHeroIds=defenderHeroIds==null?List.of():List.copyOf(defenderHeroIds);
            if(!ownerId.equals(attackerId) && !ownerId.equals(defenderId))throw invalid("战报主人必须是原攻守发起人之一");
            if(ownerId.equals(attackerId))text(defenderName,"defenderName");
            else text(attackerName,"attackerName");
            attackerHeroIds.forEach(id->text(id,"attackerHeroId"));defenderHeroIds.forEach(id->text(id,"defenderHeroId"));
        }
    }
    public StrategicBattleReportPlan {
        if(formatVersion!=1)throw invalid("战略战报计划版本无效");
        text(operationId,"operationId");
        if(createdAt<=0 || expiresAt<createdAt || battleType!=BattleType.SIEGE || recipients==null || recipients.isEmpty())
            throw invalid("战略战报计划时点/类型/接收者无效");
        recipients=List.copyOf(recipients);var owners=new HashSet<String>();
        for(var recipient:recipients) {
            if(!owners.add(recipient.ownerId()))throw invalid("同一战略战报主人重复");
            if(!BattleReport.idForOperation(operationId,recipient.ownerId()).equals(recipient.reportId()))
                throw invalid("战略战报ID与原操作/主人不匹配");
        }
        if(recipients.size()>2)throw invalid("首版战略战报只允许攻守发起人各一份");
    }
    public static StrategicBattleReportPlan create(CapturedStrategicBattle battle,String operationId,long expiresAt,
            String attackerName,String defenderName) {
        if(battle==null)throw invalid("原战略战果缺失");
        var attacker=battle.attacker();String attackerOwner=attacker.rally().initiatorId();
        var defender=battle.defender();String defenderOwner=defender==null?null:defender.rally().initiatorId();
        var attackerHeroes=heroIds(attacker);var defenderHeroes=defender==null?List.<String>of():heroIds(defender);
        String defenderId=defenderOwner==null?battle.target().targetId():defenderOwner;
        var rows=new java.util.ArrayList<Recipient>();
        rows.add(new Recipient(BattleReport.idForOperation(operationId,attackerOwner),attackerOwner,attackerOwner,
                defenderId,defenderName,attackerName,attackerHeroes,defenderHeroes));
        if(defenderOwner!=null)rows.add(new Recipient(BattleReport.idForOperation(operationId,defenderOwner),
                defenderOwner,attackerOwner,defenderOwner,defenderName,attackerName,attackerHeroes,defenderHeroes));
        return new StrategicBattleReportPlan(1,operationId,battle.happenedAt(),expiresAt,battle.input().battleType(),rows);
    }
    private static List<String> heroIds(StrategicRallyCasualties side) {
        var ids=new java.util.ArrayList<String>();side.rally().participants().values().forEach(p->ids.addAll(p.heroes()));
        return List.copyOf(ids);
    }
    private static void text(String value,String field) {
        if(value==null || value.isBlank())throw invalid(field+"缺失");
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
