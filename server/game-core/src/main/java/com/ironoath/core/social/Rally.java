package com.ironoath.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 职责：集结（B10 §1 小队集结 / §2 集结进攻，验收 11：倒计时结束时所有参与部队统一出发、兵力合并正确）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>「统一出发」的实现是服务端在 departAt 那一刻把参与者的兵力合成一支部队</b>，
 * 而不是让每个人各自出发。后者会出现「大部队已经打完了我才到」的部队白送一次行军时间，
 * 而验收 11 明写要求统一出发、兵力合并正确。
 *
 * <p><b>兵力合并按 unitId 归并并保持 Σ 守恒</b>：合并后的总数必须精确等于各参与者承诺数之和。
 * 这条断言写在 {@link #depart} 里而不是只写在测试里 ——
 * 兵力凭空少一点是玩家最容易发现也最不能接受的 bug（「我出了 1000 兵，集结回来只有 980」），
 * 而它一旦出现就再也查不清是哪一步丢的。
 *
 * <p><b>用 unitId 而不是兵种类型</b>：与行军、关卡同一口径。按兵种类型归并会把 T5 兵和 T1 兵
 * 混成一堆，而两者的攻防差一个数量级 —— 合并之后战斗内核就无法还原谁出了什么。
 *
 * <p><b>状态单向</b>：PREPARING → DEPARTED / CANCELLED，不可回退。
 * 出发后还能加人的话，就会出现「已经打完的那一波里凭空多出一个人」，
 * 而战报是按出发那一刻的兵力算的，加进来的人既没有战报也没有损失记录。
 */
public final class Rally {

    /** 集结的发起层级。人数上限按层级从 global.RALLY_MAX_SIZE_* 取。 */
    public enum Scope { SQUAD, ALLIANCE, NATION }

    /** 集结状态。 */
    public enum Status {
        /** 准备中：成员可以加入或退出。 */
        PREPARING,
        /** 已出发：兵力已合并，不可更改。 */
        DEPARTED,
        /** 已到达目标（战斗结算由 B07 的行军链路负责，本类只记录状态）。 */
        ARRIVED,
        /** 已取消。 */
        CANCELLED
    }

    /**
     * @param minMembers         出发所需的最少参与人数。来源 global.RALLY_MIN_SIZE
     * @param prepareMinMillis   准备时长下限（毫秒）。来源 global.RALLY_PREPARE_MIN_SECONDS
     * @param prepareMaxMillis   准备时长上限（毫秒）。来源 global.RALLY_PREPARE_MAX_SECONDS
     */
    public record Rules(int minMembers, long prepareMinMillis, long prepareMaxMillis) {
        public Rules {
            if (minMembers < 1) {
                throw new IllegalArgumentException("minMembers 必须 >= 1，实际=" + minMembers);
            }
            if (prepareMinMillis <= 0 || prepareMaxMillis < prepareMinMillis) {
                throw new IllegalArgumentException("准备时长区间非法：min=" + prepareMinMillis
                        + " max=" + prepareMaxMillis);
            }
        }
    }

    /**
     * 一个参与者的承诺。
     *
     * @param playerId 玩家 id
     * @param troops   unitId → 数量。<b>用不可变 Map 存</b>：参与者事后改自己的出兵表
     *                 会让「出发时合并的兵力」与「他以为自己出了多少」对不上
     * @param heroes   随军武将 id，按提交顺序。允许为空（一个武将都不带是合法承诺）。
     *                 <b>空位与重复项在这里就被抹掉</b>：合并名单要按「谁的武将排第几」决定，
     *                 一个 null 或重复项会让后面的位次整体错位，而面板上看不出任何异常
     */
    public record Participant(String playerId, Map<String, Long> troops, List<String> heroes) {
        public Participant {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            if (troops == null || troops.isEmpty()) {
                throw new IllegalArgumentException("集结必须承诺兵力：一个人不叫集结，那只是普通出征");
            }
            Map<String, Long> copy = new TreeMap<>(troops);
            for (Map.Entry<String, Long> entry : copy.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("unitId 不得为空");
                }
                if (entry.getValue() == null || entry.getValue() <= 0L) {
                    throw new IllegalArgumentException("兵力必须为正，unitId=" + entry.getKey()
                            + " 实际=" + entry.getValue());
                }
            }
            troops = Collections.unmodifiableMap(copy);
            List<String> heroCopy = new ArrayList<>();
            if (heroes != null) {
                for (String heroId : heroes) {
                    if (heroId == null || heroId.isBlank() || heroCopy.contains(heroId)) {
                        continue;
                    }
                    heroCopy.add(heroId);
                }
            }
            heroes = List.copyOf(heroCopy);
        }

        /** 不带武将的承诺（B10 §1 早期形态，也是「只出人不出将」的合法选择）。 */
        public Participant(String playerId, Map<String, Long> troops) {
            this(playerId, troops, List.of());
        }

        public long total() {
            long sum = 0L;
            for (long count : troops.values()) {
                sum += count;
            }
            return sum;
        }
    }

    /** 一个随军武将位在合并名单里的落选原因。 */
    public enum HeroState {
        /** 已排进行军名单。 */
        SELECTED,
        /** 编队位已满（前一个成员的武将占掉了最后一个位）。 */
        OVER_CAP,
        /** 别的成员已经带了同一个武将：一个武将不能在两支队列里各算一次加成。 */
        DUPLICATE
    }

    /**
     * 一个随军武将位及其状态。面板要显示的就是这份名单，
     * 因为「我报了将却没上场」必须能自己看出来，而不是靠玩家数位次猜。
     */
    public record HeroSlot(String playerId, String heroId, HeroState state) {

        public HeroSlot {
            if (playerId == null || playerId.isBlank() || heroId == null || heroId.isBlank()) {
                throw new IllegalArgumentException("HeroSlot 的 playerId 与 heroId 都不得为空");
            }
            if (state == null) {
                throw new IllegalArgumentException("HeroSlot.state 不得为 null");
            }
        }

        public boolean selected() {
            return state == HeroState.SELECTED;
        }
    }

    /**
     * 合并行军的随军武将名单：按加入顺序（发起人最先）逐个占位，总量不超过 {@code cap}。
     *
     * <p><b>为什么上限按整支集结算而不是按人算</b>：每个武将提供一整套攻防加成，
     * 如果「每人各带一整套」，一次 N 人集结就把个人编队上限绕过了 N 倍 ——
     * 而 {@code LINEUP_HERO_COUNT} 是战斗平衡的地基，集结不该成为它的后门。
     * 真正的社交代价（谁能上、谁得等）正是集结要玩家协商的部分。
     *
     * @param cap 整支合并行军的武将位上限，由调用方从 {@code LINEUP_HERO_COUNT} 解析后传入
     *            （领域层不查配置，才能脱离容器单测）
     */
    public List<HeroSlot> heroSlots(int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("武将位上限必须 >= 1，实际=" + cap);
        }
        List<HeroSlot> out = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        int used = 0;
        for (Participant participant : participants.values()) {
            for (String heroId : participant.heroes()) {
                final HeroState state;
                if (taken.contains(heroId)) {
                    state = HeroState.DUPLICATE;
                } else if (used >= cap) {
                    state = HeroState.OVER_CAP;
                } else {
                    state = HeroState.SELECTED;
                    used++;
                    taken.add(heroId);
                }
                out.add(new HeroSlot(participant.playerId(), heroId, state));
            }
        }
        return List.copyOf(out);
    }

    /** {@link #heroSlots(int)} 里真正上场的那份，按加入顺序。 */
    public List<String> selectedHeroes(int cap) {
        List<String> out = new ArrayList<>();
        for (HeroSlot slot : heroSlots(cap)) {
            if (slot.selected()) {
                out.add(slot.heroId());
            }
        }
        return List.copyOf(out);
    }

    /** 出发结果：合并后的兵力与守恒校验值。 */
    public record Departure(Map<String, Long> mergedTroops, long totalTroops, int memberCount, long departAt) {
        public Departure {
            mergedTroops = Collections.unmodifiableMap(new TreeMap<>(mergedTroops));
        }
    }

    private final String rallyId;
    private final Scope scope;
    private final String groupId;
    private final String initiatorId;
    private final int maxMembers;
    private final int minMembers;
    private final long createdAt;
    private final long prepareUntil;
    /** 按加入顺序保存（TreeMap 会打乱顺序，而「谁先响应」在集结里是有意义的信息）。 */
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private Status status;
    private Departure departure;
    /**
     * 集结目标（去哪儿打）。
     *
     * <p><b>目标属于集结本身，不是发起请求的附属信息</b>：出发时要靠它建行军，
     * 面板要显示它，成员决定加不加也要看它。放在服务层的旁路 map 里会让
     * 「集结存在但目标丢了」成为一个可表达的状态，而那种状态在出发时才会炸。
     *
     * <p>类型用裸字符串而不是 web 层的枚举：game-core 依赖不到 game-web，
     * 而为了一个字段把枚举下沉到 core 会让 core 承担协议形状。
     * 翻译在 game-web 的装配处做一次，与其它跨层枚举同一条口径。
     * {@link #initiate} 会拒绝空目标：没有目标的集结无法出发，
     * 而成员也无法判断这波该不该加。
     */
    private final long targetX;
    private final long targetY;
    private final String targetType;

    private Rally(String rallyId, Scope scope, String groupId, String initiatorId, int maxMembers, int minMembers,
                  long createdAt, long prepareUntil, Participant initiatorTroops,
                  long targetX, long targetY, String targetType) {
        this.rallyId = rallyId;
        this.scope = scope;
        this.groupId = groupId;
        this.initiatorId = initiatorId;
        this.maxMembers = maxMembers;
        this.minMembers = minMembers;
        this.createdAt = createdAt;
        this.prepareUntil = prepareUntil;
        this.status = Status.PREPARING;
        this.participants.put(initiatorId, initiatorTroops);
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetType = targetType;
    }

    /**
     * 发起一次集结。
     *
     * <p>准备时长会被夹到 [prepareMin, prepareMax] 而不是拒绝：发起人在滑块上很容易越界，
     * 拒绝会让他以为集结功能坏了（与 B08 搜索半径越界只截断不拒绝是同一条纪律）。
     *
     * @param requestedPrepareMillis 发起人期望的准备时长
     */
    /**
     * 发起一次<b>带目标</b>的集结（生产路径用这一个）。
     *
     * @param targetX    目标格横坐标
     * @param targetY    目标格纵坐标
     * @param targetType 目标类型（与行军同一套口径：MONSTER / PLAYER_CITY / RESOURCE / EMPTY）
     */
    public static Rally initiate(String rallyId, Scope scope, String groupId, String initiatorId,
                                 Map<String, Long> initiatorTroops, List<String> initiatorHeroes,
                                 int maxMembers,
                                 long requestedPrepareMillis, long now, Rules rules,
                                 long targetX, long targetY, String targetType) {
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("targetType 不得为空：没有目标的集结无法出发，"
                    + "而成员也无法判断这波该不该加");
        }
        if (rallyId == null || rallyId.isBlank()) {
            throw new IllegalArgumentException("rallyId 不得为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不得为 null");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不得为空：集结必须归属一个组织，否则谁都能加");
        }
        if (initiatorId == null || initiatorId.isBlank()) {
            throw new IllegalArgumentException("initiatorId 不得为空");
        }
        if (maxMembers < rules.minMembers()) {
            throw new IllegalArgumentException("maxMembers(" + maxMembers + ") 不得小于出发下限("
                    + rules.minMembers() + ")：那样这次集结永远无法出发");
        }
        long prepare = Math.min(rules.prepareMaxMillis(), Math.max(rules.prepareMinMillis(), requestedPrepareMillis));
        return new Rally(rallyId, scope, groupId, initiatorId, maxMembers, rules.minMembers(),
                now, now + prepare, new Participant(initiatorId, initiatorTroops, initiatorHeroes),
                targetX, targetY, targetType);
    }

    /** 实际生效的准备时长（毫秒）。已被夹到规则区间，供响应体回给客户端。 */
    public long prepareMillis() {
        return prepareUntil - createdAt;
    }

    /**
     * 加入集结。
     *
     * @param heroes 该成员随军的武将，可为空。归属与编队上限由应用层校验
     *               （领域层读不到玩家存档），这里只负责存进同一份承诺里
     * @throws IllegalStateException 已出发 / 已满 / 重复加入
     */
    public void join(String playerId, Map<String, Long> troops, List<String> heroes) {
        requirePreparing("加入");
        if (participants.containsKey(playerId)) {
            throw new IllegalStateException("你已经加入了这次集结：重复加入会让同一个人的兵被算两遍");
        }
        if (participants.size() >= maxMembers) {
            throw new IllegalStateException("集结人数已满（上限 " + maxMembers + "）");
        }
        participants.put(playerId, new Participant(playerId, troops, heroes));
    }

    /** 退出集结。发起人退出等于取消（没有人能替他指出兵）。 */
    public void quit(String playerId) {
        requirePreparing("退出");
        if (initiatorId.equals(playerId)) {
            status = Status.CANCELLED;
            return;
        }
        if (participants.remove(playerId) == null) {
            throw new IllegalStateException("你不是这次集结的参与者");
        }
    }

    /** 取消集结。只有发起人能取消（权限位由 PermissionMatrix 裁决，这里只做状态迁移）。 */
    public void cancel(String operatorId) {
        requirePreparing("取消");
        if (!initiatorId.equals(operatorId)) {
            throw new IllegalStateException("只有发起人能取消这次集结");
        }
        status = Status.CANCELLED;
    }

    /**
     * 撤销一次已经出发、但合并行军没能建立的集结：DEPARTED → CANCELLED。
     *
     * <p><b>这是「状态单向」的唯一例外</b>，理由不是方便，而是没有事务：
     * 出发（把状态推到 DEPARTED 并锁定成员的兵）与建行军（入库 + 登记到期队列）是两步，
     * 中间任何一步失败都会留下一次「有集结、没有行军」的状态。不回退，
     * 成员的兵就被永久锁在一支永远不会到家、也永远不会再退款的集结里 ——
     * 那比多一条状态迁移严重得多。
     *
     * <p>本方法<b>只改状态，不退还兵力</b>：退款要动每个参与者的军队存档，
     * 那是服务层与仓储的职责，领域层碰不到。
     */
    public void abortDeparted() {
        if (status != Status.DEPARTED) {
            throw new IllegalStateException("只有已出发但尚未到达的集结能撤销出发，当前=" + status);
        }
        status = Status.CANCELLED;
    }

    /**
     * 出发：合并全部参与者的兵力（验收 11）。
     *
     * <p><b>人数不足下限时拒绝出发</b>：一个人出发不叫集结，那只是普通出征，
     * 却白占了一个集结位与一次准备倒计时。
     *
     * @param now 服务端当前时刻。<b>早于 prepareUntil 也允许出发</b> ——
     *            到期扫描可能延迟几毫秒，用「必须精确等于」会让集结永远卡在准备中
     */
    public Departure depart(long now) {
        if (status != Status.PREPARING) {
            throw new IllegalStateException("集结当前状态不允许出发：" + status);
        }
        if (participants.size() < minMembersRequired()) {
            throw new IllegalStateException("集结人数不足：需要至少 " + minMembersRequired()
                    + " 人，当前 " + participants.size() + " 人");
        }
        Map<String, Long> merged = new TreeMap<>();
        long expectedTotal = 0L;
        for (Participant participant : participants.values()) {
            for (Map.Entry<String, Long> entry : participant.troops().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), Long::sum);
                expectedTotal += entry.getValue();
            }
        }
        long mergedTotal = 0L;
        for (long count : merged.values()) {
            mergedTotal += count;
        }
        if (mergedTotal != expectedTotal) {
            // 兵力凭空少一点是玩家最容易发现也最不能接受的 bug，而它一旦出现就查不清是哪一步丢的。
            // 所以在这里直接炸，而不是记一条日志继续跑
            throw new IllegalStateException("集结兵力合并后不守恒：承诺合计 " + expectedTotal
                    + "，合并后 " + mergedTotal + "，rallyId=" + rallyId);
        }
        status = Status.DEPARTED;
        departure = new Departure(merged, mergedTotal, participants.size(), Math.max(now, prepareUntil));
        return departure;
    }

    /** 标记已到达（战斗结算走 B07 的行军链路，本类只记录状态）。 */
    public void arrive() {
        if (status != Status.DEPARTED) {
            throw new IllegalStateException("只有已出发的集结能标记到达，当前=" + status);
        }
        status = Status.ARRIVED;
    }

    /** 出发所需的最少人数。取「规则下限」与「人数上限」的较小值，避免上限低于下限时永远无法出发。 */
    public int minMembersRequired() {
        return Math.min(maxMembers, minMembers);
    }

    /** 是否已到出发时刻。到期扫描用它挑出该出发的集结。 */
    public boolean dueAt(long now) {
        return status == Status.PREPARING && now >= prepareUntil;
    }

    private void requirePreparing(String action) {
        if (status != Status.PREPARING) {
            throw new IllegalStateException("集结当前状态不允许" + action + "：" + status);
        }
    }

    // ---------- 只读访问 ----------

    public String rallyId() {
        return rallyId;
    }

    public Scope scope() {
        return scope;
    }

    public String groupId() {
        return groupId;
    }

    public String initiatorId() {
        return initiatorId;
    }

    public int maxMembers() {
        return maxMembers;
    }

    public Status status() {
        return status;
    }

    public long createdAt() {
        return createdAt;
    }

    public long prepareUntil() {
        return prepareUntil;
    }

    /** 参与人数（含发起人）。 */
    public int joinedCount() {
        return participants.size();
    }

    /** 已承诺的兵力合计。准备期间就要能看到 —— 集结的核心决策是「这波打得过吗」。 */
    public long totalTroops() {
        long sum = 0L;
        for (Participant participant : participants.values()) {
            sum += participant.total();
        }
        return sum;
    }

    /** 参与者 id，按加入顺序。 */
    public List<String> memberIds() {
        return Collections.unmodifiableList(new ArrayList<>(participants.keySet()));
    }

    /**
     * 参与者表（只读副本，按加入顺序）。仓储映射用 —— {@link Participant} 自身不可变，
     * 但这份表会随加入/退出变化，不能把内部引用交出去。
     */
    public Map<String, Participant> participants() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(participants));
    }

    public Participant participant(String playerId) {
        return participants.get(playerId);
    }

    /** 出发结果；未出发为 null。 */
    public Departure departure() {
        return departure;
    }

    public long targetX() {
        return targetX;
    }

    public long targetY() {
        return targetY;
    }

    /** 目标类型（裸字符串，口径与行军一致）；无目标时为 null。 */
    public String targetType() {
        return targetType;
    }

    /**
     * 供仓储重建。
     *
     * <p>participants 复制成保持插入顺序的 LinkedHashMap —— 顺序在集结里是有意义的信息
     * （谁先响应），用调用方给的 HashMap 重建会把它打乱。
     * 发起人必须出现在 participants 里：真正的发起人兵力不在旁路字段上，
     * 静默给一个空值会让重建后的集结在出发时少一支队伍，且没有任何报错。
     */
    public static Rally restore(String rallyId, Scope scope, String groupId, String initiatorId,
                                int maxMembers, int minMembers, long createdAt, long prepareUntil,
                                Map<String, Participant> participants, Status status, Departure departure,
                                long targetX, long targetY, String targetType) {
        Participant initiator = participants.get(initiatorId);
        if (initiator == null) {
            throw new IllegalArgumentException("participants 里缺少发起人：" + initiatorId
                    + "。重建集结不能凭猜测补一份兵力");
        }
        Rally rally = new Rally(rallyId, scope, groupId, initiatorId, maxMembers, minMembers,
                createdAt, prepareUntil, initiator, targetX, targetY, targetType);
        rally.participants.clear();
        rally.participants.putAll(participants);
        rally.status = status;
        rally.departure = departure;
        return rally;
    }

    /**
     * 深拷贝。存储层「读返回副本」用：Participant 自身是不可变记录，
     * 但参与者表是可变的，必须复制。
     */
    public Rally copy() {
        return restore(rallyId, scope, groupId, initiatorId, maxMembers, minMembers,
                createdAt, prepareUntil, participants, status, departure,
                targetX, targetY, targetType);
    }
}
