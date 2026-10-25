package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.world.Coord;
import com.ironoath.battle.TerrainType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定战略目标目录与落城预留。只读配置/城索引，不迁城、不改归属、不激活攻城。
 * 防守引用既有难度表；发布地图图层前必须另查旧城冲突，不能用目标覆盖玩家数据。
 */
public final class WorldTargets {
    public record Target(WorldTargetCfg definition, Coord coord, MapmonsterCfg defender,TerrainType battleTerrain) {
        public long troops() {
            return Math.addExact(Math.addExact(defender.infantryCount(), defender.cavalryCount()),
                    Math.addExact(defender.archerCount(), defender.siegeCount()));
        }
        public String terrainName(){return battleTerrain.displayName();}
    }

    public record Conflict(String targetId, Coord coord, String playerId) {}

    private final Map<Coord, Target> byCoord;

    private WorldTargets(Map<Coord, Target> byCoord) {
        this.byCoord = Map.copyOf(byCoord);
    }

    /** 每次操作冻结一份目录；不缓存世界占位，不把热更后的坐标与旧索引混用。 */
    public static WorldTargets from(ConfigRegistry configs) {
        int size = Math.toIntExact(configs.longParam("WORLD_SIZE"));
        Map<String, MapmonsterCfg> defenders = new LinkedHashMap<>();
        configs.all(MapmonsterCfg.class).forEach(row -> defenders.put(row.id(), row));
        List<WorldTargetCfg> rows=configs.all(WorldTargetCfg.class);
        if(rows.isEmpty())return new WorldTargets(Map.of());
        Map<Coord,WorldTargetCfg> definitionsByCoord=new LinkedHashMap<>();
        for(WorldTargetCfg row:rows) {
            Coord coord=Coord.of(Math.toIntExact(row.x()),Math.toIntExact(row.y()));
            if(!coord.withinWorld(size))throw new IllegalArgumentException("战略目标坐标越界："+row.id()+" "+coord);
            WorldTargetCfg previous=definitionsByCoord.putIfAbsent(coord,row);
            if(previous!=null)throw new IllegalArgumentException("战略目标坐标重复："+previous.id()+" / "+row.id()+" "+coord);
        }
        var capitals=rows.stream().filter(row->row.kind()==WorldTargetCfg.Kind.CAPITAL).toList();
        if(capitals.size()!=1)throw new IllegalArgumentException("固定战略目标目录必须且只能包含一个王城");
        Coord capital=Coord.of(Math.toIntExact(capitals.get(0).x()),Math.toIntExact(capitals.get(0).y()));
        Map<Coord, Target> targets = new LinkedHashMap<>();
        for (var definition:definitionsByCoord.entrySet()) {
            Coord coord=definition.getKey();WorldTargetCfg row=definition.getValue();
            MapmonsterCfg defender = defenders.get(row.defenderId());
            if (defender == null) {
                throw new IllegalArgumentException("战略目标防守配置缺失：" + row.id());
            }
            TerrainType battleTerrain=StrategicTerrainResolver.resolve(row,coord,capital);
            targets.put(coord,new Target(row,coord,defender,battleTerrain));
        }
        return new WorldTargets(targets);
    }

    public boolean reserved(Coord coord) {
        return byCoord.containsKey(coord);
    }

    public Target at(Coord coord) {
        return byCoord.get(coord);
    }

    public List<Target> targets() {
        return byCoord.values().stream().sorted(java.util.Comparator.comparing(t -> t.definition().id())).toList();
    }

    /** Move the single world-layer CAPITAL slot to the persisted capital of the latest War target nation. */
    public WorldTargets withCapitalCoord(Coord capitalCoord) {
        if (capitalCoord == null) throw new IllegalArgumentException("王城动态坐标不得为空");
        List<Target> capitals = byCoord.values().stream()
                .filter(target -> target.definition().kind() == WorldTargetCfg.Kind.CAPITAL).toList();
        if (capitals.size() != 1) throw new IllegalStateException("动态王城投影要求固定目录恰好有一个 CAPITAL 候选");
        Target capital = capitals.get(0);
        if (capital.coord().equals(capitalCoord)) return this;
        var moved = new LinkedHashMap<Coord, Target>();
        for (Target target : byCoord.values()) {
            Coord coord = target == capital ? capitalCoord : target.coord();
            Target projected = target == capital
                    ? new Target(target.definition(), capitalCoord, target.defender(), target.battleTerrain())
                    : target;
            Target previous = moved.putIfAbsent(coord, projected);
            if (previous != null) throw new IllegalStateException("王城现坐标与其它战略目标冲突");
        }
        return new WorldTargets(moved);
    }

    /** 调用方提供当前城快照。冲突只返回报告，旧城由后续显式迁移方案处理。 */
    public List<Conflict> conflicts(Map<String, Coord> cities) {
        return cities.entrySet().stream().filter(e -> byCoord.containsKey(e.getValue()))
                .map(e -> new Conflict(byCoord.get(e.getValue()).definition().id(), e.getValue(), e.getKey()))
                .sorted(java.util.Comparator.comparing(Conflict::targetId).thenComparing(Conflict::playerId))
                .toList();
    }
}
