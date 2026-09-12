package com.ironoath.core.world;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 职责：单个玩家的迷雾状态 —— 哪些 chunk 已被探索（B07 §3）。
 * 依赖：无（纯 Java）。
 *
 * <p><b>迷雾按 chunk 记，不按格记</b>：512×512 = 262144 格，按格记的话
 * 每个玩家一份 26 万位的位图，4000 名玩家就是十亿级状态；
 * 按 chunk 记只有 256 个键，全探索也就 256 个字符串。
 * 而玩家的视觉体验本来就是按块解锁的（黑色遮罩以块为单位揭开），按格记不会更好看。
 *
 * <p><b>探索是不可逆的</b>：解锁过的 chunk 不会因为离开视野而重新变黑。
 * B07 说的「离开视野卸载」卸载的是<b>实体数据与渲染节点</b>（防内存泄漏），
 * 不是把地图重新盖上黑雾 —— 后者会让玩家觉得「我走过的地方又忘了」，
 * 那是纯粹的负反馈，没有任何策略价值。
 */
public final class FogOfWar {

    private final Set<String> exploredChunks = new LinkedHashSet<>();

    /**
     * 乐观锁版本：<b>只有仓储能推进它</b>，{@code explore} 一类业务方法一律不碰。
     *
     * <p>要它是因为迷雾写回是<b>整份覆盖</b>：两条请求各自"读 → 探索几块 → 写回"，
     * 后写的会把先写的那几块重新盖黑（玩家看到"我刚走过的地方又黑了"，而全链路不报错）。
     * {@code PlayerLock} 只挡得住同一个实例内的同玩家并发，多实例部署时只剩版本号。
     */
    private long version;

    public FogOfWar() {
    }

    /** 库里第几版。调用方读到时记下，写回时原样带回来。 */
    public long version() {
        return version;
    }

    /** 持久化成功后由仓储调用；业务代码不要调（一次没成功的写入不该占版本号）。 */
    public void incrementVersion() {
        version++;
    }

    /** 解锁一个 chunk。重复解锁是幂等的。 */
    public void exploreChunk(String chunkKey) {
        if (chunkKey == null || chunkKey.isBlank()) {
            throw new IllegalArgumentException("chunkKey 不得为空");
        }
        exploredChunks.add(chunkKey);
    }

    /** 解锁一个坐标所在的 chunk。 */
    public void explore(Coord coord, int chunkSize) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        exploreChunk(coord.chunkKey(chunkSize));
    }

    /**
     * 解锁一条路径经过的所有 chunk（B07 §3：行军经过解锁）。
     *
     * <p>沿直线以 {@code chunkSize / 2} 为步长采样：步长取半个 chunk 保证不会跳过任何一块
     * （步长取整个 chunk 时，斜向路径会从两块之间的缝隙里穿过去而两块都不解锁）。
     * 全程整数运算，采样点数量与路径长度成正比，与 chunk 尺寸无关。
     */
    public void explorePath(Coord from, Coord to, int chunkSize) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from 与 to 都不得为 null");
        }
        if (chunkSize < 2) {
            throw new IllegalArgumentException("chunkSize 至少为 2（步长取其一半），实际=" + chunkSize);
        }
        explore(from, chunkSize);
        explore(to, chunkSize);
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int steps = (Math.abs(dx) + Math.abs(dy)) * 2 / chunkSize;
        for (int i = 1; i < steps; i++) {
            // 先乘后除，避免 i/steps 先整除成 0
            int x = from.x() + dx * i / Math.max(1, steps);
            int y = from.y() + dy * i / Math.max(1, steps);
            exploreChunk(Coord.of(Math.max(0, x), Math.max(0, y)).chunkKey(chunkSize));
        }
    }

    /** 该坐标是否已探索。 */
    public boolean isExplored(Coord coord, int chunkSize) {
        if (coord == null) {
            throw new IllegalArgumentException("coord 不得为 null");
        }
        return exploredChunks.contains(coord.chunkKey(chunkSize));
    }

    /** 已探索的 chunk 键集合（只读），顺序即解锁顺序。 */
    public Set<String> chunks() {
        return Collections.unmodifiableSet(exploredChunks);
    }

    public int exploredCount() {
        return exploredChunks.size();
    }

    /**
     * 视口应当下发的 chunk：以中心 chunk 为核心的 3×3。
     *
     * <p>返回顺序是行优先（先 y 后 x），稳定可复现 —— 客户端按顺序做增量比对，
     * 顺序不稳定会让「哪些块变了」每次算出不同结果。
     *
     * @param viewportChunks 期望的块数（B07 是 9，即 3×3）。取平方根得到边长，
     *                       非完全平方数会向下取整并说明，因为半块没有意义
     */
    public static Collection<String> viewportChunks(Coord center, int chunkSize, int viewportChunks) {
        if (center == null) {
            throw new IllegalArgumentException("center 不得为 null");
        }
        if (viewportChunks < 1) {
            throw new IllegalArgumentException("viewportChunks 必须 >= 1，实际=" + viewportChunks);
        }
        int side = (int) Math.sqrt(viewportChunks);
        if (side * side != viewportChunks) {
            throw new IllegalArgumentException("viewportChunks 必须是完全平方数（3×3=9），实际="
                    + viewportChunks + "。非平方数意味着视口不是正方形，客户端的九宫格布局无从对齐");
        }
        int radius = side / 2;
        Coord origin = Coord.chunkOrigin(center.chunkKey(chunkSize), chunkSize);
        int centerX = origin.x() / chunkSize;
        int centerY = origin.y() / chunkSize;
        Set<String> out = new LinkedHashSet<>(viewportChunks);
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = centerX + dx;
                int cy = centerY + dy;
                if (cx < 0 || cy < 0) {
                    continue;   // 越界的块不下发（世界边缘）
                }
                out.add(cx + ":" + cy);
            }
        }
        return out;
    }

    /**
     * 供仓储反序列化写回。
     *
     * <p><b>先拷一份再 clear</b>：{@link #chunks()} 返回的是内部 Set 的不可变<b>视图</b>，
     * 调用方把它传回来（「读出 → 加一块 → 写回」是很自然的写法）时，
     * {@code clear()} 会先把入参一起清空，拷回来的就是空集 ——
     * 玩家已探索的整片地图会凭空变回黑雾，而且不报任何错。
     */
    public void restore(Collection<String> restored) {
        apply(restored);
    }

    /**
     * 仓储用：连"库里第几版"一起还原。
     *
     * <p>一参数那版<b>故意保留当前版本</b>而不是清成 0 —— 清掉会让一次普通改写看起来像
     * "这个玩家还没有迷雾档"，随后带版本写回时会报成建档冲突，把一个可用重试解决的情况
     * 变成一条读不懂的报错。
     */
    public void restore(Collection<String> restored, long storedVersion) {
        apply(restored);
        this.version = storedVersion;
    }

    private void apply(Collection<String> restored) {
        Set<String> copy = restored == null ? Set.of() : new LinkedHashSet<>(restored);
        exploredChunks.clear();
        exploredChunks.addAll(copy);
    }

    @Override
    public String toString() {
        return "FogOfWar{已探索 " + exploredChunks.size() + " 块}";
    }
}
