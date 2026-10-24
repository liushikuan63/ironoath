package com.ironoath.web.battle;

import java.util.HashSet;
import java.util.List;

/** Original-season KILL board increments frozen from actual engine member contributions. */
public record StrategicBattleKillPlan(int formatVersion,String sourceId,long sequence,String operationId,
        String seasonId,List<Member> members) {
    public record Member(String playerId,String displayName,long kills,boolean leaderboardEligible) {
        public Member(String playerId,String displayName,long kills) {this(playerId,displayName,kills,true);}
        public Member {
            text(playerId,"playerId");
            if(displayName==null || displayName.isBlank())displayName=playerId;
            if(kills<0)throw invalid("成员引擎击杀不得为负");
        }
    }
    public StrategicBattleKillPlan {
        if(formatVersion!=1)throw invalid("战略KILL计划版本无效");
        text(sourceId,"sourceId");text(operationId,"operationId");
        if(sequence<1)throw invalid("战略KILL计划代次无效");
        if(seasonId!=null && seasonId.isBlank())throw invalid("原季ID为空串无效");
        if(members==null)throw invalid("原成员击杀计划缺失");
        members=List.copyOf(members);
        if(seasonId==null && !members.isEmpty())throw invalid("无活跃赛季时不能生成季榜击杀成员");
        var players=new HashSet<String>();String previous=null;
        for(var member:members) {
            if(!players.add(member.playerId()))throw invalid("战略KILL计划重复成员");
            if(previous!=null && previous.compareTo(member.playerId())>=0)throw invalid("战略KILL成员必须按playerId稳定排序");
            previous=member.playerId();
        }
    }
    public boolean active(){return seasonId!=null;}
    private static void text(String value,String field) {
        if(value==null || value.isBlank())throw invalid(field+"缺失");
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
