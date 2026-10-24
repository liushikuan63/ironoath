package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Original unit-id兵权 for a player inside a merged battle side. */
public record BattleMember(String playerId, Map<String,Long> unitsById, Map<String,UnitType> unitTypes) {
    public BattleMember {
        if(playerId==null || playerId.isBlank() || unitsById==null || unitTypes==null
                || !unitsById.keySet().equals(unitTypes.keySet()))
            throw new IllegalArgumentException("战斗成员身份/兵权/兵种映射缺失");
        var units=new TreeMap<String,Long>();unitsById.forEach((id,count)->{
            if(id==null || id.isBlank() || count==null || count<=0)throw new IllegalArgumentException("战斗成员兵权无效");
            units.put(id,count);
        });
        var types=new TreeMap<String,UnitType>();unitTypes.forEach((id,type)->{
            if(id==null || id.isBlank() || type==null)throw new IllegalArgumentException("战斗成员兵种映射无效");
            types.put(id,type);
        });
        unitsById=Collections.unmodifiableMap(new LinkedHashMap<>(units));
        unitTypes=Collections.unmodifiableMap(new LinkedHashMap<>(types));
        if(unitsById.isEmpty())throw new IllegalArgumentException("战斗成员必须有实际兵力");
    }

    public Map<UnitType,Long> units() {
        var counts=new EnumMap<UnitType,Long>(UnitType.class);for(UnitType type:UnitType.values())counts.put(type,0L);
        unitsById.forEach((id,count)->counts.merge(unitTypes.get(id),count,Math::addExact));
        return Collections.unmodifiableMap(counts);
    }
}
