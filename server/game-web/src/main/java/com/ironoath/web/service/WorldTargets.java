package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.world.Coord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定战略目标目录与落城预留。只读配置/城索引，不迁城、不改归属、不激活攻城。
 * 防守引用既有难度表；发布地图图层前必须另查旧城冲突，不能用目标覆盖玩家数据。
 */
public final class WorldTargets {
    public record Target(WorldTargetCfg definition, Coord coord, MapmonsterCfg defender) {
        public long troops() {
            return Math.addExact(Math.addExact(defender.infantryCount(), defender.cavalryCount()),
                    Math.addExact(defender.archerCount(), defender.siegeCount()));
        }
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
        Map<Coord, Target> targets = new LinkedHashMap<>();
        for (WorldTargetCfg row : configs.all(WorldTargetCfg.class)) {
            Coord coord = Coord.of(Math.toIntExact(row.x()), Math.toIntExact(row.y()));
            if (!coord.withinWorld(size)) {
                throw new IllegalArgumentException("战略目标坐标越界：" + row.id() + " " + coord);
            }
            MapmonsterCfg defender = defenders.get(row.defenderId());
            if (defender == null) {
                throw new IllegalArgumentException("战略目标防守配置缺失：" + row.id());
            }
            Target previous = targets.putIfAbsent(coord, new Target(row, coord, defender));
            if (previous != null) {
                throw new IllegalArgumentException("战略目标坐标重复：" + previous.definition().id()
                        + " / " + row.id() + " " + coord);
            }
        }
        return new WorldTargets(targets);
    }

    public boolean reserved(Coord coord) {
        return byCoord.containsKey(coord);
    }

    public List<Target> targets() {
        return byCoord.values().stream().sorted(java.util.Comparator.comparing(t -> t.definition().id())).toList();
    }

    /** 调用方提供当前城快照。冲突只返回报告，旧城由后续显式迁移方案处理。 */
    public List<Conflict> conflicts(Map<String, Coord> cities) {
        return cities.entrySet().stream().filter(e -> byCoord.containsKey(e.getValue()))
                .map(e -> new Conflict(byCoord.get(e.getValue()).definition().id(), e.getValue(), e.getKey()))
                .sorted(java.util.Comparator.comparing(Conflict::targetId).thenComparing(Conflict::playerId))
                .toList();
    }
}
