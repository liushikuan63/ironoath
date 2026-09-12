package com.ironoath.core.world;

/**
 * 职责：世界坐标与 chunk 键（B07 §1）。
 * 依赖：无（纯数据）。
 *
 * <p><b>距离用曼哈顿而不是欧几里得</b>：欧几里得要开平方，而 B00 铁律禁止 float/double
 * 参与结算，定点开方要么自己实现要么引入误差。曼哈顿距离是纯整数、无误差、可手算复核，
 * 在方格地图上也是玩家直觉能理解的距离。
 *
 * <p><b>chunk 键用位移而不是除法</b>：WORLD_CHUNK_SIZE 是 2 的幂（32），
 * 位移与除法在负数上行为不同（Java 的 {@code >>} 是算术右移，向负无穷取整，
 * 而 {@code /} 向零取整）。世界坐标恒为非负，所以两者等价，
 * 但用位移能明确表达「这里依赖 chunk 尺寸是 2 的幂」这个前提。
 */
public record Coord(int x, int y) {

    public Coord {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("世界坐标不得为负：x=" + x + ", y=" + y);
        }
    }

    public static Coord of(int x, int y) {
        return new Coord(x, y);
    }

    /** 曼哈顿距离。 */
    public int distanceTo(Coord other) {
        if (other == null) {
            throw new IllegalArgumentException("other 不得为 null");
        }
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    /** 切比雪夫距离（用于「离中心多少环」的等级分布，方形环比圆形环更容易手算复核）。 */
    public int ringOf(Coord center) {
        if (center == null) {
            throw new IllegalArgumentException("center 不得为 null");
        }
        return Math.max(Math.abs(x - center.x), Math.abs(y - center.y));
    }

    /**
     * 所属 chunk 的键。
     *
     * @param chunkSize 必须是 2 的幂，否则位移与除法不等价
     */
    public String chunkKey(int chunkSize) {
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂，实际=" + chunkSize
                    + "。chunk 键用位移计算，非 2 的幂会让相邻格子被算进不同的块");
        }
        int shift = Integer.numberOfTrailingZeros(chunkSize);
        return (x >> shift) + ":" + (y >> shift);
    }

    /** chunk 键对应的左上角坐标，供客户端定位与调试。 */
    public static Coord chunkOrigin(String chunkKey, int chunkSize) {
        String[] parts = chunkKey.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("chunk 键格式必须是 cx:cy，实际=" + chunkKey);
        }
        return new Coord(Integer.parseInt(parts[0]) * chunkSize,
                Integer.parseInt(parts[1]) * chunkSize);
    }

    /**
     * 一格的身份标识（存储键）。两套世界存储、坐标索引、消耗格集合都用它，
     * 所以这条编码必须在领域层只有一份 —— 各自编一份的结局是"一边写成 x,y 一边写成 x:y"，
     * 而那种不一致的表现不是报错，是<b>同一格在两份索引里是两个东西</b>。
     *
     * <p><b>刻意不复用 {@link #toString()}</b>：toString 是给人看的（日志、报错），
     * 格式一变（加个空格、换成 x=1 y=2）存储键的解析就会静默出错，
     * 而消费它的地方出错表现为"刚采空的资源点又出现了"，排查时完全看不出是格式问题。
     */
    public String storageKey() {
        return x + ":" + y;
    }

    /** {@link #storageKey()} 的反向操作。格式不对是数据被写坏了，必须响而不是回一个默认坐标。 */
    public static Coord parseStorageKey(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new IllegalStateException("坐标存储键格式必须是 x:y，实际=" + key);
        }
        try {
            return Coord.of(Integer.parseInt(key.substring(0, colon)),
                    Integer.parseInt(key.substring(colon + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("坐标存储键里的两个段必须是整数，实际=" + key, e);
        }
    }

    /** 是否在边长为 worldSize 的正方形世界内。 */
    public boolean withinWorld(int worldSize) {
        if (worldSize <= 0) {
            throw new IllegalArgumentException("worldSize 必须为正：" + worldSize);
        }
        return x < worldSize && y < worldSize;
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }
}
