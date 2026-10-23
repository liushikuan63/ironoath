package com.ironoath.web.world;

import com.ironoath.core.world.WorldGenerator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 独立中立守军账；无归属不等于无损失。缺档仅表示尚未保存首次交战，不批量建默认档。 */
public interface NeutralTargetGarrisonStore {
    enum Phase { NEUTRAL, RETIRED }
    record Snapshot(String targetId,WorldGenerator.EntityType kind,int x,int y,
                    Map<String,Long> initialTroops,Map<String,Long> troops,Phase phase,
                    long battledAt,String operationId,long version) {
        public Snapshot {
            if(targetId==null || targetId.isBlank() || kind==null || !kind.isStrategic() || x<0 || y<0
                    || phase==null || battledAt<0 || operationId==null || operationId.isBlank() || version<1)
                throw new IllegalArgumentException("中立战略守军锚点/阶段/凭据无效");
            initialTroops=freeze(initialTroops);troops=freeze(troops);
            if(initialTroops.isEmpty())throw new IllegalArgumentException("中立守军原始兵力不得缺失");
            for(var unit:troops.entrySet())if(unit.getValue()>initialTroops.getOrDefault(unit.getKey(),0L))
                throw new IllegalArgumentException("中立余兵超过冻结原始守军");
            if(phase==Phase.RETIRED && !troops.isEmpty())throw new IllegalArgumentException("退出中立状态不能保留NPC驻军");
        }
        private static Map<String,Long> freeze(Map<String,Long> source) {
            if(source==null)throw new IllegalArgumentException("中立守军明细缺失");
            Map<String,Long> copy=new LinkedHashMap<>();long total=0;
            for(var unit:source.entrySet()) {
                if(unit.getKey()==null || unit.getKey().isBlank() || unit.getValue()==null || unit.getValue()<1)
                    throw new IllegalArgumentException("中立守军明细无效，零兵须显式空明细");
                total=Math.addExact(total,unit.getValue());copy.put(unit.getKey(),unit.getValue());
            }
            return java.util.Collections.unmodifiableMap(copy);
        }
    }
    Map<String,Snapshot> findByTargetIds(Collection<String> ids);
    Map<String,Snapshot> findAll(int limit);
    /** 调用方须先冻结目标操作权和版本；不是跨归属/行军文档事务，不能盲取新版本重试。 */
    boolean compareAndSet(Snapshot next,long expectedVersion);
    static void validateWrite(Snapshot next,long expectedVersion) {
        if(next==null || expectedVersion<0 || next.version()!=Math.addExact(expectedVersion,1))
            throw new IllegalArgumentException("中立守军版本须严格递增一");
    }
    static boolean allows(Snapshot before,Snapshot after) {
        if(before==null)return true;
        if(before.phase()==Phase.RETIRED || !before.targetId().equals(after.targetId())
                || before.kind()!=after.kind() || before.x()!=after.x() || before.y()!=after.y()
                || !before.initialTroops().equals(after.initialTroops()) || after.battledAt()<before.battledAt())return false;
        for(var unit:after.troops().entrySet())if(unit.getValue()>before.troops().getOrDefault(unit.getKey(),0L))return false;
        return true;
    }
}
