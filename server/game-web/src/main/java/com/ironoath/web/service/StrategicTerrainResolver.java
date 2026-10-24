package com.ironoath.web.service;

import com.ironoath.battle.TerrainType;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.world.Coord;
import java.util.Objects;

/**
 * B13 用户选定的方位规则：王城城下；四个具名关卡按北山/东林/南河/西原；
 * 要塞按当前world_target坐标相对王城的主轴分类，平手取南北。当前地形加成配置仍为0，
 * 地形分类与中文名用于地图显示并冻结到战斗事实，不改变现有地形乘区数值。
 */
final class StrategicTerrainResolver {
    private StrategicTerrainResolver() {}

    static TerrainType resolve(WorldTargetCfg target,Coord coordinate,Coord capital) {
        Objects.requireNonNull(target,"world target");Objects.requireNonNull(coordinate,"target coordinate");
        Objects.requireNonNull(capital,"capital coordinate");
        return switch(target.kind()) {
            case CAPITAL -> TerrainType.CITY_WALL;
            case GATE -> switch(target.id()) {
                case "gate_north" -> TerrainType.MOUNTAIN;
                case "gate_east" -> TerrainType.FOREST;
                case "gate_south" -> TerrainType.RIVER;
                case "gate_west" -> TerrainType.PLAIN;
                default -> throw new IllegalStateException("固定关卡缺少已裁决的方位地形规则："+target.id());
            };
            case FORTRESS -> {
                int dx=Math.subtractExact(coordinate.x(),capital.x());
                int dy=Math.subtractExact(coordinate.y(),capital.y());
                if(dx==0 && dy==0)throw new IllegalStateException("要塞不能与王城共用坐标："+target.id());
                if(Math.abs((long)dx)>Math.abs((long)dy))
                    yield dx>0?TerrainType.FOREST:TerrainType.PLAIN;
                yield dy<0?TerrainType.MOUNTAIN:TerrainType.RIVER;
            }
        };
    }
}
