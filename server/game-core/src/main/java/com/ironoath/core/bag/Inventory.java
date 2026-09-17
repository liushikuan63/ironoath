package com.ironoath.core.bag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：玩家背包聚合根 —— 道具持有量、堆叠上限、容量上限（B04 §3），
 * 以及<b>装备实例账本</b>（B20 §五⑤：装备从「itemId → 数量」变成「一件一个 {@code uid}」）。
 * 依赖：无（纯 Java，game-core 只依赖 game-common）。
 *
 * <p><b>堆叠上限由调用方传入</b>而不是存在背包里：上限来自 item 表的 stackMax 字段，
 * 属于配置数据。背包若自己存一份，配置热更后就会出现两个真相。
 * 这与 game-core 一贯的约定一致 —— 核心逻辑接受「已解析好的参数」，不自己查配置。
 * 装备实例同理：本类不知道"哪个 itemId 是装备"，那由调用方（{@code PlayerBag}）按 item 表判定后
 * 决定走 {@link #add} 还是 {@link #mintEquips}。
 *
 * <p>两条语义与发奖相反，必须分清：
 * <ul>
 *   <li>{@link #add} 是<b>尽力入包</b>：受堆叠上限与容量约束，返回实际入包量，
 *       差额由发放器转邮件（B04 验收 2）</li>
 *   <li>{@link #remove} 是<b>原子扣减</b>：不足则完全不扣并返回 0，绝不扣成负数
 *       也绝不扣一半（B04 验收 10、禁止项「不要让资源出现负数」）</li>
 * </ul>
 * 理由是两个方向的失败后果不对称：少给了可以补发邮件，多扣了玩家会直接投诉且难以追回。
 *
 * <p><b>实例化装备改变了三件事的说法，别让它们停留在"只是多了个 id"</b>：
 * <ol>
 *   <li>{@link #countOf} 对装备返回的是<b>件数</b>（账本里同 {@code equipId} 的实例数），
 *       装备永远不占 {@link #counts} 那格 —— 否则同 id 的两件会共享一个数字，
 *       而强化等级是挂在件上的；</li>
 *   <li><b>按件占格</b>：10 件铁剑占 10 格（旧模型占 1 格）。这是「每件不同」的必然代价，
 *       写在这里是为了让人知道它是一次玩家可见的变化，而不是一处遗漏；</li>
 *   <li><b>穿上不是「从背包删除」</b>：{@link #equipToSlot} 只打一个 worn 标志，实例始终留在账本里 ——
 *       强化等级是这件装备的财产，跟着槽位走就会出现「换一件白板装还带着 +3」那条可刷收益（§五⑤）。</li>
 * </ol>
 */
public final class Inventory {

    /**
     * 一件装备。
     *
     * @param uid        实例号，玩家生命周期内唯一，与本行的 {@code equipId} 形状不同（见 {@link Inventory} 的迁移注释）
     * @param equipId    {@code equip.json} 的行 id
     * @param forgeLevel 强化等级（+N），0 是合法的初始值
     * @param worn       是否正穿在某个武将身上。<b>「穿在谁身上」的权威不在这里</b>，在
     *                   {@code HeroInstance.equips}；本位只为「按件占格」这一个算式服务（派生索引），
     *                   两处不一致的症状是格子数不对，而不是属性算错
     */
    public record EquipInstance(String uid, String equipId, int forgeLevel, boolean worn) {

        public EquipInstance {
            if (uid == null || uid.isBlank()) {
                throw new IllegalArgumentException("装备实例必须有 uid");
            }
            if (equipId == null || equipId.isBlank()) {
                throw new IllegalArgumentException("装备实例必须知道自己是哪一行：" + uid);
            }
            if (forgeLevel < 0) {
                throw new IllegalArgumentException("强化等级不得为负：" + uid + " = " + forgeLevel);
            }
        }
    }

    /** itemId → 持有量。用 LinkedHashMap 保持插入顺序，让背包列表的展示顺序稳定可复现。 */
    private final Map<String, Long> counts = new LinkedHashMap<>();

    /** uid → 一件装备。同样按插入顺序，"先拿到的排前面"是界面上可复现的顺序。 */
    private final Map<String, EquipInstance> equips = new LinkedHashMap<>();

    /**
     * 铸造实例用的序号。落进存档而不是取随机数：随机 uid 会让「同一次读取」在两处
     * （内存对象与 Mongo 副本）铸出两套不同的号，而武将槽位里存的正是 uid —— 那时症状是
     * 装备凭空消失，且只在重启之后出现。
     */
    private int nextEquipUid = 1;

    /** 背包格子容量上限。0 表示无上限（不推荐：那是放弃了付费扩容点，见 B04 开放问题 1）。 */
    private int capacityMax;

    public Inventory(int capacityMax) {
        if (capacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + capacityMax);
        }
        this.capacityMax = capacityMax;
    }

    /** 空背包。 */
    public static Inventory empty(int capacityMax) {
        return new Inventory(capacityMax);
    }

    public int capacityMax() {
        return capacityMax;
    }

    public void setCapacityMax(int capacityMax) {
        if (capacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + capacityMax);
        }
        if (capacityMax < capacityUsed()) {
            // 缩容到已用量以下会让「容量」这个约束当场失效，玩家看到的背包会超格
            throw new IllegalArgumentException("capacityMax 不得小于已用格子数："
                    + capacityMax + " < " + capacityUsed());
        }
        this.capacityMax = capacityMax;
    }

    /**
     * 已占用的格子数 = 持有量 &gt; 0 的<b>道具种类数</b> + <b>没穿在身上的装备件数</b>。
     *
     * <p>装备按件占格而道具按种占格，是「每件都不一样」与「同种可堆叠」两种持有方式的直接结果。
     * <b>穿在身上的不计</b>：旧模型里穿上就是把那件从背包里扣掉，所以"穿着不占格子"是玩家
     * 已经习惯的语义（也是"背包满了卸不下装备"那句提示的前提）。改成占格的话，
     * 一个穿满 4 件的玩家会凭空少 4 格容量 —— 那是收益为负、又没有出处的一次改动。
     */
    public int capacityUsed() {
        int used = 0;
        for (long count : counts.values()) {
            if (count > 0L) {
                used++;
            }
        }
        for (EquipInstance instance : equips.values()) {
            if (!instance.worn()) {
                used++;
            }
        }
        return used;
    }

    /**
     * 持有量。装备返回<b>件数</b>（账本里同 id 的实例数），其余道具返回堆叠数。
     *
     * <p>判据是「账本里有没有这个 id 的实例」而不是「item 表里是不是 EQUIP 类型」：本类不认识配置，
     * 而装备一旦实例化就绝不回落进 {@link #counts}，所以这两种问法在此刻同义，
     * 少一个跨层依赖则少一处会分叉的口径。
     */
    public long countOf(String itemId) {
        requireItemId(itemId);
        long inLedger = instanceCount(itemId);
        return inLedger > 0L ? inLedger : counts.getOrDefault(itemId, 0L);
    }

    public boolean isEmpty() {
        return counts.values().stream().noneMatch(c -> c > 0L) && equips.isEmpty();
    }

    /**
     * 加入道具。
     *
     * @param itemId   道具 id，对应 item 表的行 id
     * @param count    期望加入量，必须为正
     * @param stackMax 该道具的堆叠上限，来自配置。&lt;= 0 表示不限制
     * @return 实际入包量，可能小于 count（受堆叠上限约束）
     */
    public long add(String itemId, long count, long stackMax) {
        requireItemId(itemId);
        if (count <= 0L) {
            throw new IllegalArgumentException("入包数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        long current = counts.getOrDefault(itemId, 0L);
        long room = stackMax <= 0L ? Long.MAX_VALUE - current : Math.max(0L, stackMax - current);
        long actual = Math.min(count, room);
        if (actual > 0L) {
            counts.put(itemId, current + actual);
        }
        return actual;
    }

    /**
     * 移除道具（使用道具、合成、出售都走这里）。
     *
     * @return 实际移除量；持有量不足时返回 0 且<b>不做部分移除</b>
     */
    public long remove(String itemId, long count) {
        requireItemId(itemId);
        if (count <= 0L) {
            throw new IllegalArgumentException("移除数量必须为正，itemId=" + itemId + ", count=" + count);
        }
        long current = counts.getOrDefault(itemId, 0L);
        if (current < count) {
            return 0L;
        }
        long rest = current - count;
        if (rest == 0L) {
            // 归零的条目直接移除而不是留一个 0：capacityUsed 靠「条目数」计算，
            // 留着一堆 0 会让背包看起来是满的
            counts.remove(itemId);
        } else {
            counts.put(itemId, rest);
        }
        return count;
    }

    // ---------- 装备实例账本（B20 §五⑤） ----------

    /** 全部实例，铸造顺序（= 玩家拿到它们的顺序）。 */
    public List<EquipInstance> equipInstances() {
        return List.copyOf(equips.values());
    }

    /** 某一行的全部实例。界面按行分组时用。 */
    public List<EquipInstance> equipInstances(String equipId) {
        requireItemId(equipId);
        List<EquipInstance> out = new ArrayList<>();
        for (EquipInstance instance : equips.values()) {
            if (instance.equipId().equals(equipId)) {
                out.add(instance);
            }
        }
        return out;
    }

    /** 按 uid 取一件；不存在时返回 null（调用方决定是"没这件"还是"数据坏了"）。 */
    public EquipInstance equipInstance(String uid) {
        return uid == null ? null : equips.get(uid);
    }

    /** 下一个可铸造的实例序号。落档而不是每次现算，是为了让 {@link #copy()} 与两套存储看到同一个序列。 */
    public int nextEquipUid() {
        return nextEquipUid;
    }

    /** 装备实例的总数（含穿着的）。 */
    public int equipCount() {
        return equips.size();
    }

    /**
     * 铸造 {@code count} 件装备实例。
     *
     * <p><b>容量不在这里判</b>：与 {@link #add} 同一条分工 —— 容量与溢出转邮件都在发放侧，
     * 本方法只管"给我几件，我铸几件"。
     *
     * @return 新铸出来的 uid 列表，顺序与铸造顺序一致
     */
    public List<String> mintEquips(String equipId, int count) {
        requireItemId(equipId);
        if (count <= 0) {
            throw new IllegalArgumentException("铸造件数必须为正，equipId=" + equipId + ", count=" + count);
        }
        List<String> minted = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String uid = "e" + nextEquipUid++;
            equips.put(uid, new EquipInstance(uid, equipId, 0, false));
            minted.add(uid);
        }
        return minted;
    }

    /**
     * 扣掉 {@code count} 件某种装备（出售、被合成材料吃掉都走这里）。
     *
     * <p><b>不足则整笔不扣</b>，与 {@link #remove} 同一条原子性：装备现在带着强化等级，
     * 扣一半等于拿走某一件的等级而玩家以为自己还留着。
     *
     * <p><b>先扣未穿的、再按强化等级从低到高扣</b>：穿着的那件是玩家此刻的战斗力，
     * 拿它去当材料是最伤人的选法；同状态下先牺牲便宜的。顺序不写进规则的话，
     * 「哪件被吃掉」就成了插入顺序的巧合，而这种巧合在两处（内存对象与 Mongo 副本）不一致时最难查。
     *
     * @return 被扣掉的实例；件数不足时返回空列表且账本不变
     */
    public List<EquipInstance> removeEquips(String equipId, int count) {
        requireItemId(equipId);
        if (count <= 0) {
            throw new IllegalArgumentException("扣除件数必须为正，equipId=" + equipId + ", count=" + count);
        }
        List<EquipInstance> candidates = equipInstances(equipId);
        if (candidates.size() < count) {
            return List.of();
        }
        List<EquipInstance> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(EquipInstance::worn)
                .thenComparingInt(EquipInstance::forgeLevel)
                .thenComparing(EquipInstance::uid));
        List<EquipInstance> taken = List.copyOf(ordered.subList(0, count));
        taken.forEach(instance -> equips.remove(instance.uid()));
        return taken;
    }

    /**
     * 打上／摘掉「正穿在身上」的标志。
     *
     * <p>实例<b>不出账本</b>：强化等级是这件装备的财产，穿卸都不该动它。本标志只影响
     * {@link #capacityUsed()}（穿着的不占格），
     * 「穿在哪个武将身上」的权威始终是 {@code HeroInstance.equips} 里那个 uid。
     *
     * @return 改完的那一件
     */
    public EquipInstance markWorn(String uid, boolean worn) {
        EquipInstance instance = equips.get(uid);
        if (instance == null) {
            throw new IllegalArgumentException("装备实例不存在，无法改穿戴状态：uid=" + uid);
        }
        EquipInstance updated = new EquipInstance(instance.uid(), instance.equipId(),
                instance.forgeLevel(), worn);
        equips.put(uid, updated);
        return updated;
    }

    /**
     * 该行的第一件<b>未穿</b>装备的 uid，没有则 null。
     *
     * <p><b>只服务一条兼容路径</b>：客户端的穿戴请求至今传的是配置行 id（它还看不到"件"这个概念），
     * 服务端要在"这一行的某一件"与"某一件"之间做一次翻译。等界面能列出实例、请求改传 uid 之后，
     * 这个方法与那条翻译一起删掉 —— 留着它的风险是同 id 的两件强化等级不同却被当成可互换。
     */
    public String firstUnwornEquip(String equipId) {
        for (EquipInstance instance : equips.values()) {
            if (!instance.worn() && instance.equipId().equals(equipId)) {
                return instance.uid();
            }
        }
        return null;
    }

    /**
     * 把某一件的强化等级抬一级（B20 §五②：纯消耗、必成，所以这里没有成功率与保底）。
     *
     * <p><b>上限由调用方传进来</b>（{@code equip.json.forgeMax}）：本层不读配置，与堆叠上限同一条分工。
     *
     * <p><b>到上限是抛而不是静默返回</b>：返回旧值的话，调用方"扣了钱、等级没动"这件事
     * 在日志里看起来完全正常，而玩家看见的是铁花了 +7 还是 +7。
     *
     * @return 抬级之后的那一件
     * @throws IllegalArgumentException uid 不存在，或已到 {@code forgeMax}
     */
    public EquipInstance forge(String uid, int forgeMax) {
        EquipInstance instance = equips.get(uid);
        if (instance == null) {
            throw new IllegalArgumentException("装备实例不存在，无法强化：uid=" + uid);
        }
        if (forgeMax < 1) {
            throw new IllegalArgumentException("forgeMax 必须为正，实际=" + forgeMax);
        }
        if (instance.forgeLevel() >= forgeMax) {
            throw new IllegalArgumentException("装备 " + instance.equipId() + "（uid=" + uid
                    + "）已到强化上限 " + forgeMax + " 级，不能再抬");
        }
        EquipInstance forged = new EquipInstance(instance.uid(), instance.equipId(),
                instance.forgeLevel() + 1, instance.worn());
        equips.put(uid, forged);
        return forged;
    }

    // ---------- uid 的形状（迁移判定靠它，见 isInstanceUid） ----------

    /**
     * 是不是一个实例号。形如 {@code e7}（正常铸造）或 {@code u:eq_iron_sword:2}（老档迁移铸造）。
     *
     * <p><b>为什么形状要与配置行 id 天然可分</b>：老存档的武将槽位里存的就是配置行 id
     * （{@code eq_iron_sword}），迁移时要分辨「这是一个 uid」还是「这是一个还没迁移的行 id」。
     * 靠"查一下这个 id 在不在 equip 表里"来分辨是把判断推给了配置 ——
     * 将来真出现一行名叫 {@code e1} 的装备就会悄悄错，而错的是一条存档的装备没了。
     * 形状判断不需要任何外部数据，因此两套存储、迁移前后都得到同一个答案。
     */
    public static boolean isInstanceUid(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.startsWith("u:")) {
            return value.length() > 3;
        }
        if (value.length() < 2 || value.charAt(0) != 'e') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 由老档的「数量」条目迁移出来的 uid：同一个输入永远得到同一个号，所以重复读取不会改变存档含义。 */
    static String legacyEquipUid(String equipId, int ordinal) {
        return "u:" + equipId + ":" + ordinal;
    }

    /**
     * 只读快照（道具数量部分），顺序与插入顺序一致。装备不在这里 —— 它们是 {@link #equipSnapshot()}。
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        counts.forEach((k, v) -> {
            if (v > 0L) {
                copy.put(k, v);
            }
        });
        return Map.copyOf(copy);
    }

    /** 实例账本的只读快照。与 {@link #snapshot()} 一起才构成一份完整的背包。 */
    public List<EquipInstance> equipSnapshot() {
        return List.copyOf(equips.values());
    }

    /** 深拷贝，用于并发修改前的快照与回滚。 */
    public Inventory copy() {
        Inventory copy = new Inventory(capacityMax);
        copy.restore(snapshot(), equipSnapshot(), capacityMax, nextEquipUid, itemId -> false);
        return copy;
    }

    /**
     * 供仓储反序列化写回。业务代码不要用。
     *
     * <p><b>{@code instancedItemIds} 是这条路径上唯一的迁移入口</b>：老文档的 {@code counts}
     * 里躺着 {@code eq_iron_sword: 2} 这样的条目，而实例化之后装备只能待在 {@link #equips} 里。
     * 判定「哪些 id 属于实例化物品」需要读 item 表，game-core 不认识配置（铁律 2），所以由调用方
     * 把一个谓词传进来。
     *
     * <p><b>为什么读的时候必须顺手迁移而不是"读成没有"</b>：§五⑤ 那句「绝不能读成没穿装备」。
     * 不迁移的话，玩家的东西在界面上凭空消失，而他一做任何会落库的操作，这些条目就被当作
     * 「本来就该是数量的道具」重新写回去 —— 实例化改造就此在这份档上永久失败。
     *
     * @param instancedItemIds 该 itemId 是否应按实例持有（装备）。为 null 视作"全不是"，
     *                         那是 {@link #copy()} 这种"已经迁移过的内部用法"的写法
     */
    public void restore(Map<String, Long> restored, List<EquipInstance> restoredEquips,
                        int restoredCapacityMax, int restoredNextEquipUid,
                        java.util.function.Predicate<String> instancedItemIds) {
        if (restoredCapacityMax < 0) {
            throw new IllegalArgumentException("capacityMax 不得为负：" + restoredCapacityMax);
        }
        if (restoredNextEquipUid < 1) {
            throw new IllegalArgumentException("实例序号必须从 1 起，实际=" + restoredNextEquipUid);
        }
        counts.clear();
        equips.clear();
        if (restoredEquips != null) {
            restoredEquips.forEach(instance -> {
                if (instance != null) {
                    equips.put(instance.uid(), instance);
                }
            });
        }
        if (restored != null) {
            restored.forEach((itemId, count) -> {
                if (count == null || count < 0L) {
                    throw new IllegalArgumentException("道具 " + itemId + " 的数量非法：" + count);
                }
                if (count == 0L) {
                    return;
                }
                boolean migrated = instancedItemIds != null && instancedItemIds.test(itemId)
                        // 账本里已经有这种装备 ⇒ 这份档是迁移之后写的，数量条目属于陈旧残留，
                        // 再铸一遍等于白送装备（本类的写路径不会同时留下两处，所以这里只防外来的脏数据）
                        && equipInstances(itemId).isEmpty();
                if (migrated) {
                    for (int ordinal = 1; ordinal <= count; ordinal++) {
                        String uid = legacyEquipUid(itemId, ordinal);
                        equips.put(uid, new EquipInstance(uid, itemId, 0, false));
                    }
                } else {
                    counts.put(itemId, count);
                }
            });
        }
        // 序号只向前推，不覆写：防的是"文档里已经有 e7 这件，而序号却被回拨到 3"那种状态
        // （外来的手写档，或只跑了一半的迁移链）—— 那时 mintEquips 会直接盖掉一件带着强化等级的装备
        this.nextEquipUid = Math.max(restoredNextEquipUid, equips.size() + 1);
        this.capacityMax = restoredCapacityMax;
    }

    private long instanceCount(String equipId) {
        long n = 0L;
        for (EquipInstance instance : equips.values()) {
            if (instance.equipId().equals(equipId)) {
                n++;
            }
        }
        return n;
    }

    private static void requireItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不得为空");
        }
    }

    @Override
    public String toString() {
        return "Inventory(格子=" + capacityUsed() + "/" + capacityMax + ", 道具=" + counts
                + ", 装备=" + equips.size() + " 件)";
    }
}
