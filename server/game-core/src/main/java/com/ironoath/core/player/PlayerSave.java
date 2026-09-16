package com.ironoath.core.player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：玩家存档聚合根 —— 服务端权威状态的唯一载体（铁律 3）。
 * 依赖：无（纯 Java，不依赖 Spring / MongoDB / game-config）。
 *
 * <p>为什么是可变类而不是 record：玩家存档在整个会话期被多个系统反复修改（建造、结算、战斗、社交），
 * 每次改动都复制一份不可变对象会产生大量临时对象与拷贝代码。这里采用「聚合根可变 + 仓储整体持久化」
 * 的经典做法，并用 {@code version} 字段做乐观锁，避免并发写覆盖。
 *
 * <p>持久化映射在 game-web（MongoDB 文档），本类不含任何存储注解 —— 这样 game-core 的玩法逻辑
 * 可以脱离数据库跑单测（C00 公理四·五）。
 */
public final class PlayerSave {

    private String playerId;
    private String deviceId;
    private String nickName;
    private int avatarId;
    private long createdAt;
    private long lastLoginAt;
    private int cityLevel;
    private final Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
    private PlayerPower power;
    /** PVP 侧状态（战力峰值时点 / 暴虐值 / 连续受害护盾），B08 引入。 */
    private PlayerPvp pvp = PlayerPvp.empty();
    /** 新手保护到期时间（服务端毫秒时间戳）；null 表示无保护。 */
    private Long protectUntil;
    /**
     * 荣耀三件套（B14 §4 唯一允许留在主存档的赛季数据）。
     *
     * <p><b>它是派生缓存，不是第二份真相</b>：真相在赛季结算账本，这三项随时可由账本重算；
     * 缓存落后于账本时由读路径以账本为准修一次。默认 {@link PlayerGlory#empty()} 而不是 null ——
     * 这轮之前的号本来就没有这些，「从零开始」是唯一正确的读法（与 {@link #pvp} 同一条纪律）。
     */
    private PlayerGlory glory = PlayerGlory.empty();
    /**
     * 新手引导进度（B18）。<b>只有「该显示哪一步」与「还显不显示」这两位</b>，
     * 每步做到没有是读任务账本算出来的（见 {@link PlayerGuide} 的类注释）。
     *
     * <p>默认 {@link PlayerGuide#empty()} 而不是 null：B18 之前的号本来就没走过引导，
     * 「从没开始过」是唯一正确的读法（与 {@link #pvp}、{@link #glory} 同一条纪律）。
     */
    private PlayerGuide guide = PlayerGuide.empty();
    /** 乐观锁版本号，每次持久化自增。 */
    private long version;

    /** 反序列化用的空构造器，业务代码请用 {@link #createNew}。 */
    public PlayerSave() {
    }

    /**
     * 创建新号存档。
     *
     * <p>所有初始数值都由调用方（game-web 的 PlayerInitService）从配置表解析后传入，
     * 本方法不读配置 —— 这是 game-core 保持「只依赖 game-common」的代价，也是它能脱离容器单测的原因。
     *
     * @param now          服务端当前时间戳，由调用方从 TimeService 取（铁律 5：不用系统时钟）
     * @param protectUntil 新手保护到期时间，null 表示无保护
     */
    public static PlayerSave createNew(String playerId,
                                       String deviceId,
                                       String nickName,
                                       int avatarId,
                                       long now,
                                       int cityLevel,
                                       Map<String, PlayerResourceState> initialResources,
                                       PlayerPower initialPower,
                                       Long protectUntil) {
        requireText(playerId, "playerId");
        requireText(deviceId, "deviceId");
        requireText(nickName, "nickName");
        if (now <= 0L) {
            throw new IllegalArgumentException("now 必须为正的服务端时间戳，实际=" + now);
        }
        if (cityLevel < 1) {
            throw new IllegalArgumentException("cityLevel 必须 >= 1，实际=" + cityLevel);
        }
        if (initialResources == null || initialResources.isEmpty()) {
            throw new IllegalArgumentException("初始资源不得为空：新号必须带齐全部资源类型");
        }
        if (initialPower == null) {
            throw new IllegalArgumentException("初始战力不得为 null");
        }

        PlayerSave save = new PlayerSave();
        save.playerId = playerId;
        save.deviceId = deviceId;
        save.nickName = nickName;
        save.avatarId = avatarId;
        save.createdAt = now;
        save.lastLoginAt = now;
        save.cityLevel = cityLevel;
        save.resources.putAll(initialResources);
        save.power = initialPower;
        // 峰值衰减的锚点必须是建号时刻：留 0 的话第一次读战力就会按「已经过了几十年」补算衰减，
        // 峰值当场归零。结果不会出错（新号峰值本就等于当前值），但日志里会多出一次莫名的衰减
        save.pvp = PlayerPvp.empty().withPeakTouchedAt(now);
        save.protectUntil = protectUntil;
        save.version = 0L;
        return save;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }

    // ---------- 读取 ----------

    public String playerId() {
        return playerId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String nickName() {
        return nickName;
    }

    public int avatarId() {
        return avatarId;
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastLoginAt() {
        return lastLoginAt;
    }

    public int cityLevel() {
        return cityLevel;
    }

    public PlayerPower power() {
        return power;
    }

    /** PVP 侧状态。永不为 null（老存档反序列化时会补 {@link PlayerPvp#empty()}）。 */
    public PlayerPvp pvp() {
        return pvp;
    }

    /** 荣耀三件套缓存。永不为 null（没打过赛季就是 {@link PlayerGlory#empty()}）。 */
    public PlayerGlory glory() {
        return glory;
    }

    /** 新手引导进度。永不为 null（没走过引导就是 {@link PlayerGuide#empty()}）。 */
    public PlayerGuide guide() {
        return guide;
    }

    public Long protectUntil() {
        return protectUntil;
    }

    public long version() {
        return version;
    }

    /** 资源快照的只读视图（保持配置表顺序）。 */
    public Map<String, PlayerResourceState> resources() {
        return Map.copyOf(resources);
    }

    /**
     * 取单一资源状态。
     *
     * @throws IllegalArgumentException 当资源类型不存在 —— 绝不返回 null
     */
    public PlayerResourceState resource(String resourceType) {
        PlayerResourceState state = resources.get(resourceType);
        if (state == null) {
            throw new IllegalArgumentException("玩家[" + playerId + "]存档中不存在资源类型 "
                    + resourceType + "，现有=" + resources.keySet());
        }
        return state;
    }

    public boolean hasResource(String resourceType) {
        return resources.containsKey(resourceType);
    }

    // ---------- 修改 ----------

    /** 替换单一资源状态（惰性结算后写回）。 */
    public void putResource(String resourceType, PlayerResourceState state) {
        requireText(resourceType, "resourceType");
        if (state == null) {
            throw new IllegalArgumentException("资源状态不得为 null：" + resourceType);
        }
        if (!resources.containsKey(resourceType)) {
            throw new IllegalArgumentException("玩家[" + playerId + "]存档中不存在资源类型 "
                    + resourceType + "，不允许新增未知资源（新资源必须先加配置表）");
        }
        resources.put(resourceType, state);
    }

    public void setCityLevel(int cityLevel) {
        if (cityLevel < 1) {
            throw new IllegalArgumentException("cityLevel 必须 >= 1，实际=" + cityLevel);
        }
        this.cityLevel = cityLevel;
    }

    public void setPower(PlayerPower power) {
        if (power == null) {
            throw new IllegalArgumentException("战力不得为 null");
        }
        this.power = power;
    }

    public void setProtectUntil(Long protectUntil) {
        this.protectUntil = protectUntil;
    }

    /**
     * 整体替换 PVP 侧状态。
     *
     * <p>不提供逐字段的 setter：暴虐值与它的推进时刻必须同时改
     * （{@link PlayerPvp} 的紧凑构造器强制了这一点），
     * 逐字段 setter 会留出「改了值忘了改时刻」的口子，而那等于衰减永久失效。
     */
    public void setPvp(PlayerPvp pvp) {
        if (pvp == null) {
            throw new IllegalArgumentException("PVP 状态不得为 null");
        }
        this.pvp = pvp;
    }

    /**
     * 覆盖荣耀缓存。<b>唯一写者应当是赛季结算的写回路径</b>（{@code SeasonSettlementService}）
     * 与读路径上那一次「以账本为准」的自愈修正 —— 别处不该顺手写它，
     * 因为它是派生物：多写一处，就多一个可能与账本不一致的来源。
     */
    public void setGlory(PlayerGlory glory) {
        if (glory == null) {
            throw new IllegalArgumentException("荣耀缓存不得为 null（没打过赛季请传 PlayerGlory.empty()）");
        }
        this.glory = glory;
    }

    /**
     * 覆盖引导进度。<b>唯一写者是 {@code GuideAppService} 的推进路径</b>（B18）：
     * 引导不发数值奖励，所以没有第二个系统需要动这一位；开第二个写者的下场与「在引导里另开一条
     * 发奖口」是同一类问题 —— 两处写的状态没人能对账。
     */
    public void setGuide(PlayerGuide guide) {
        if (guide == null) {
            throw new IllegalArgumentException("引导进度不得为 null（没走过引导请传 PlayerGuide.empty()）");
        }
        this.guide = guide;
    }

    public void setNickName(String nickName) {
        requireText(nickName, "nickName");
        this.nickName = nickName;
    }

    public void setAvatarId(int avatarId) {
        this.avatarId = avatarId;
    }

    /**
     * 记录一次登录（只更新时间戳，不做任何数值结算）。
     *
     * <p>语义是<b>单调取最大值</b>，而不是「必须晚于上次」：
     * 并发登录时各线程取到的 {@code now} 不同，较旧的那个若抛异常，玩家看到的就是「登录失败」——
     * 而 lastLoginAt 本来就只该往前走。服务端时钟被 NTP 回拨时同理，保持不动即可。
     * 时钟回拨属于需要告警的运维事件，但不该让玩家的登录请求失败，由调用方记日志。
     *
     * @return true 表示时间戳真的前进了；false 表示传入的 now 不晚于已有值（已忽略）
     */
    public boolean touchLogin(long now) {
        if (now <= lastLoginAt) {
            return false;
        }
        this.lastLoginAt = now;
        return true;
    }

    /** 持久化成功后由仓储调用，自增乐观锁版本。 */
    public void incrementVersion() {
        this.version++;
    }

    /** 供仓储反序列化写回全部字段。业务代码不要用。 */
    public void restore(String playerId, String deviceId, String nickName, int avatarId,
                        long createdAt, long lastLoginAt, int cityLevel,
                        Map<String, PlayerResourceState> restoredResources,
                        PlayerPower restoredPower, PlayerPvp restoredPvp,
                        Long protectUntil, PlayerGlory restoredGlory, PlayerGuide restoredGuide,
                        long version) {
        this.playerId = playerId;
        this.deviceId = deviceId;
        this.nickName = nickName;
        this.avatarId = avatarId;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
        this.cityLevel = cityLevel;
        this.resources.clear();
        this.resources.putAll(restoredResources);
        this.power = restoredPower;
        // 老存档没有这个字段：补空状态而不是抛异常。
        // B08 之前建的号本来就没有暴虐值与护盾，让它们「从零开始」是唯一正确的读法
        this.pvp = restoredPvp == null ? PlayerPvp.empty() : restoredPvp;
        this.protectUntil = protectUntil;
        // 老存档没有这一项（本轮之前的号压根没存过荣耀）：补 empty 而不是抛，也不留 null ——
        // 读的人不该为了一个派生缓存到处判空
        this.glory = restoredGlory == null ? PlayerGlory.empty() : restoredGlory;
        // 老存档没有这一位（B18 之前不存在引导）：读成 empty = 从未开始，而不是抛或留 null
        this.guide = restoredGuide == null ? PlayerGuide.empty() : restoredGuide;
        this.version = version;
    }

    @Override
    public String toString() {
        return "PlayerSave(playerId=" + playerId + ", nickName=" + nickName
                + ", cityLevel=" + cityLevel + ", version=" + version + ")";
    }

    /** 深拷贝，用于并发修改前的快照对比与回滚。 */
    public PlayerSave copy() {
        PlayerSave copy = new PlayerSave();
        copy.restore(playerId, deviceId, nickName, avatarId, createdAt, lastLoginAt, cityLevel,
                new LinkedHashMap<>(resources), power, pvp, protectUntil, glory, guide, version);
        return copy;
    }
}
