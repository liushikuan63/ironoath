package com.ironoath.core.world;

import com.ironoath.common.num.FixedPoint;

import java.util.List;

/**
 * 职责：世界地图规则参数 + 确定性生成（B07 §1）。
 * 依赖：game-common 的 Rng 与 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>世界是生成出来的，不是存出来的</b>：512×512 = 262144 格，
 * 存成表就是几十 MB 的静态数据，每个 kingdom 一份，改一次密度就要重新生成全表。
 * 用 {@code WORLD_SEED} 确定性生成之后，「同一个坐标永远是什么」可复现
 * （客户端重进不会看到野怪换了位置），Bot 与真人也看到同一个世界（B00 铁律）。
 *
 * <p><b>只有玩家造成的变化才需要存储</b>：玩家城的位置、被采空的资源点、被打掉的野怪。
 * 生成结果 + 少量覆盖 = 完整世界状态。这让存储量从「格数」降到「玩家数 + 被改动数」。
 */
public final class WorldGenerator {

    /**
     * 世界规则。全部数值来自 global 表（铁律 1：不硬编码）。
     *
     * @param worldSize          世界边长（格）
     * @param chunkSize          chunk 边长（格），必须是 2 的幂
     * @param viewportChunks     客户端同时持有的 chunk 数（3×3 = 9）
     * @param payloadMaxBytes    单次 viewport 响应的字节上限
     * @param monsterDensityFixed 野怪占格比例（定点）
     * @param resourceDensityFixed 资源点占格比例（定点）
     * @param monsterLevelRing   每离中心多少格，野怪等级 +1
     * @param spawnMargin        出生点距边界的最小格数
     * @param seed               世界种子
     */
    public record Rules(int worldSize,
                        int chunkSize,
                        int viewportChunks,
                        long payloadMaxBytes,
                        long monsterDensityFixed,
                        long resourceDensityFixed,
                        int monsterLevelRing,
                        int spawnMargin,
                        long seed) {

        public Rules {
            if (worldSize < 3) {
                throw new IllegalArgumentException("worldSize 至少为 3，实际=" + worldSize);
            }
            if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
                throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂（chunk 键用位移算），实际="
                        + chunkSize);
            }
            if (worldSize % chunkSize != 0) {
                throw new IllegalArgumentException("worldSize 必须能被 chunkSize 整除，否则边缘会出现残块："
                        + worldSize + " / " + chunkSize);
            }
            if (viewportChunks < 1) {
                throw new IllegalArgumentException("viewportChunks 必须 >= 1，实际=" + viewportChunks);
            }
            if (payloadMaxBytes < 1) {
                throw new IllegalArgumentException("payloadMaxBytes 必须为正，实际=" + payloadMaxBytes);
            }
            requireRatio(monsterDensityFixed, "monsterDensityFixed");
            requireRatio(resourceDensityFixed, "resourceDensityFixed");
            if (monsterDensityFixed + resourceDensityFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("野怪与资源点的密度之和不得超过 100%，否则同一格会被两种实体争抢");
            }
            if (monsterLevelRing < 1) {
                throw new IllegalArgumentException("monsterLevelRing 必须 >= 1，实际=" + monsterLevelRing);
            }
            if (spawnMargin < 0 || spawnMargin * 2 >= worldSize) {
                throw new IllegalArgumentException("spawnMargin 必须让出生区非空：margin=" + spawnMargin
                        + ", worldSize=" + worldSize);
            }
        }

        private static void requireRatio(long fixed, String field) {
            if (fixed < 0L || fixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + fixed);
            }
        }

        public Coord center() {
            return Coord.of(worldSize / 2, worldSize / 2);
        }

        /** chunk 网格的边长（worldSize / chunkSize）。 */
        public int chunkGridSize() {
            return worldSize / chunkSize;
        }

        /** 该坐标的野怪等级：1 + 切比雪夫环数 / monsterLevelRing，封顶由 catalog 决定。 */
        public int levelAt(Coord coord) {
            return 1 + coord.ringOf(center()) / monsterLevelRing;
        }
    }

    /**
     * 生成所需的「等级 → 实体 id」目录。由调用方从配置表解析后传入（core 不读配置）。
     *
     * @param monsterIdsByLevel 下标 = 等级 - 1，值 = mapmonster 表的行 id。
     *                          等级超出表范围时取最后一项（封顶）
     * @param resourceTypes     资源点可能产出的资源 id，顺序即抽取顺序（必须稳定才可复现）
     */
    public record Catalog(List<String> monsterIdsByLevel, List<String> resourceTypes) {

        public Catalog {
            if (monsterIdsByLevel == null || monsterIdsByLevel.isEmpty()) {
                throw new IllegalArgumentException("monsterIdsByLevel 不得为空，否则野怪格无法确定是哪一种怪");
            }
            if (resourceTypes == null || resourceTypes.isEmpty()) {
                throw new IllegalArgumentException("resourceTypes 不得为空，否则资源点无法确定产什么");
            }
            monsterIdsByLevel = List.copyOf(monsterIdsByLevel);
            resourceTypes = List.copyOf(resourceTypes);
        }

        /** 按等级取野怪 id，超出范围取最后一项。 */
        public String monsterAt(int level) {
            if (level < 1) {
                throw new IllegalArgumentException("野怪等级必须 >= 1，实际=" + level);
            }
            return monsterIdsByLevel.get(Math.min(level, monsterIdsByLevel.size()) - 1);
        }
    }

    /** 地图实体类型。EMPTY 表示空地（可驻扎）。 */
    public enum EntityType {
        EMPTY,
        CITY,
        MONSTER,
        RESOURCE,
        BUILDING
    }

    /**
     * 一格的<b>生成结果</b>（不含玩家造成的变化）。
     *
     * @param entityType   实体类型
     * @param entityId     野怪 → mapmonster 行 id；资源点 → 资源 id；空地 → null
     * @param level        野怪等级；其余为 0
     */
    public record Cell(EntityType entityType, String entityId, int level) {

        public Cell {
            if (entityType == null) {
                throw new IllegalArgumentException("entityType 不得为 null");
            }
            if (level < 0) {
                throw new IllegalArgumentException("level 不得为负：" + level);
            }
            if (entityType == EntityType.MONSTER && (entityId == null || entityId.isBlank())) {
                throw new IllegalArgumentException("野怪格必须有 entityId（mapmonster 行 id）");
            }
            if (entityType == EntityType.RESOURCE && (entityId == null || entityId.isBlank())) {
                throw new IllegalArgumentException("资源点必须有 entityId（资源 id）");
            }
            if (entityType == EntityType.EMPTY && entityId != null) {
                throw new IllegalArgumentException("空地不该有 entityId，实际=" + entityId);
            }
        }

        public static Cell empty() {
            return new Cell(EntityType.EMPTY, null, 0);
        }
    }

    private WorldGenerator() {
    }

    /**
     * 生成某一格的内容。
     *
     * <p><b>确定性</b>：同一 (seed, x, y) 永远返回同一结果，且不依赖调用顺序。
     * 每格用 {@code Rng.of(seed).fork(cellIndex)} 取独立子流 ——
     * fork 的意义是「将来在格内多加一个随机点（例如资源点的储量）不会平移其它格的结果」，
     * 否则世界生成器改一行，全服的野怪就集体搬家了。
     *
     * <p>玩家城不在此生成（它们由玩家占领，存在仓储里作为覆盖）。
     */
    public static Cell generate(Rules rules, Catalog catalog, Coord coord) {
        if (rules == null || catalog == null || coord == null) {
            throw new IllegalArgumentException("rules / catalog / coord 都不得为 null");
        }
        if (!coord.withinWorld(rules.worldSize())) {
            throw new IllegalArgumentException("坐标越界：" + coord + "，世界边长 " + rules.worldSize());
        }
        long cellIndex = (long) coord.y() * rules.worldSize() + coord.x();
        com.ironoath.common.rng.Rng rng = com.ironoath.common.rng.Rng.of(rules.seed()).fork(cellIndex);

        // 先判类型再判细节：两个 roll 用不同的 fork 子流，
        // 这样以后调整资源点储量的算法不会改变「哪些格是野怪」
        long typeRoll = rng.range(0L, FixedPoint.SCALE - 1);
        if (typeRoll < rules.monsterDensityFixed()) {
            int level = Math.min(rules.levelAt(coord), catalog.monsterIdsByLevel().size());
            return new Cell(EntityType.MONSTER, catalog.monsterAt(level), level);
        }
        if (typeRoll < rules.monsterDensityFixed() + rules.resourceDensityFixed()) {
            com.ironoath.common.rng.Rng resRng =
                    com.ironoath.common.rng.Rng.of(rules.seed()).fork(cellIndex * 31 + 7);
            int index = (int) resRng.range(0L, catalog.resourceTypes().size() - 1L);
            // 资源点只出现在 BASE 资源上，金币不作为地图采集物：
            // 金币是付费货币，让它躺在地上等人捡会直接削掉 B15 的付费点
            return new Cell(EntityType.RESOURCE, catalog.resourceTypes().get(index),
                    rules.levelAt(coord));
        }
        return Cell.empty();
    }

    /**
     * 新号的出生坐标。
     *
     * <p>由 playerId 确定性推导，而不是随机找空格：随机会让「同一个玩家重进游戏」
     * 落到不同坐标（除非额外存一份），而确定性推导天然幂等。
     * 冲突由调用方（仓储的唯一约束）解决并向外螺旋找空位。
     *
     * @param index 第几个玩家（0 起），用于把不同玩家铺开
     */
    public static Coord spawnCoord(Rules rules, long index) {
        if (index < 0) {
            throw new IllegalArgumentException("index 不得为负：" + index);
        }
        int usable = rules.worldSize() - rules.spawnMargin() * 2;
        if (usable <= 0) {
            throw new IllegalStateException("出生区为空：worldSize=" + rules.worldSize()
                    + ", spawnMargin=" + rules.spawnMargin());
        }
        // 用黄金角螺旋把玩家均匀铺开：直接取模排成一行会让所有新号挤在同一条横线上，
        // 而 SLG 的开局体验取决于「附近有没有邻居」—— 挤成一行意味着大多数人周围是空的
        long goldenRatio = 2654435761L;   // 2^32 / 黄金比，整数哈希用的标准常量
        long mixed = (index + 1) * goldenRatio;
        int x = rules.spawnMargin() + (int) Math.floorMod(mixed, usable);
        int y = rules.spawnMargin() + (int) Math.floorMod(mixed / usable, usable);
        return Coord.of(x, y);
    }

    /**
     * 以某点为中心、在 [{@code minDist}, {@code maxDist}] 的<b>曼哈顿距离环带</b>里取一个候选坐标。
     *
     * <p>给「新 Bot 孵化在真人附近 5~15 格」这条落点规则用（B11 §四）。
     * 距离口径取曼哈顿：与 {@link Coord#distanceTo} 同一个口径，而目标搜索的半径也用这个 ——
     * 两处用不同的距离定义，会出现「孵化时说 8 格内、搜索时说 12 格内」这种谁也说不清的差异。
     *
     * <p><b>整数抽样，不引入三角函数</b>（B00 禁止浮点参与结算，而落点也是会被写进存档的数字）：
     * 在 [-max, max]² 的方格里拒绝采样，取曼哈顿距离落在环带内的第一个点。
     * [5,15] 的环带在 31×31 的方格里接受率约 47%，所以重试上限取 64 次时
     * 「找不到」的概率小到只可能出现在地图边界外（中心离边太近）——
     * 那种情况返回 empty，由调用方换中心或退回 {@link #spawnCoord} 的确定性螺旋。
     *
     * @param rng 随机源。同一个种子给出同一条候选序列（铁律 4：随机可复现）
     * @return 环带内且坐标非负的候选；重试耗尽或全部落在负坐标时返回 empty
     */
    public static java.util.Optional<Coord> ringAround(Coord center, int minDist, int maxDist,
                                                       com.ironoath.common.rng.Rng rng) {
        if (center == null) {
            throw new IllegalArgumentException("center 不得为 null");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng 不得为 null（随机必须可复现，禁止 Math.random）");
        }
        if (minDist < 1 || maxDist < minDist) {
            throw new IllegalArgumentException("环带区间非法：min=" + minDist + ", max=" + maxDist);
        }
        int span = maxDist * 2 + 1;
        for (int attempt = 0; attempt < 64; attempt++) {
            int dx = (int) rng.range(0L, span - 1L) - maxDist;
            int dy = (int) rng.range(0L, span - 1L) - maxDist;
            int dist = Math.abs(dx) + Math.abs(dy);
            if (dist < minDist || dist > maxDist) {
                continue;
            }
            int x = center.x() + dx;
            int y = center.y() + dy;
            if (x < 0 || y < 0) {
                // 贴边时环带会伸到地图外（Coord 的构造器直接拒绝负数），换下一抽
                continue;
            }
            return java.util.Optional.of(Coord.of(x, y));
        }
        return java.util.Optional.empty();
    }
}
