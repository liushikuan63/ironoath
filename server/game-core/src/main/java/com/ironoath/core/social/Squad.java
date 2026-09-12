package com.ironoath.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：小队领域模型（B10 §1、验收 1/2/3）。
 * 依赖：无（纯 Java，零框架 —— game-core 读不到 game-config，规则由外层解析后传入）。
 *
 * <p><b>关键设计点 1：小队不被大组织稀释</b>。玩家加入联盟后小队自动转为联盟内分队，
 * 但聊天、互助、集结<b>全部保留</b> —— C00 公理七指出 5 个人的小队和 150 个人的联盟
 * 满足的是完全不同的需求（小队是「我和兄弟们」，联盟是「我属于哪个组织」），
 * 用大组织吃掉小组织，玩家就失去了最紧密的那层关系，而那是留存的命脉。
 * 所以本模型里 {@code allianceId} 只是一个归属标记，<b>加入联盟不会清空任何小队状态</b>。
 *
 * <p><b>人数上限有两个来源，必须同时满足</b>（验收 3：5→8→10）：
 * <ul>
 *   <li>小队等级：Lv1=5、Lv3=10</li>
 *   <li><b>队长</b>的主城等级：Lv2 的 8 人门槛是「队长主城 8 级」而不是小队等级</li>
 * </ul>
 * 生效上限 = 满足「小队等级 >= L 且 队长主城 >= L 的 unlockMainLevel」的最高那一档的 memberCap。
 * 用队长等级而不是全员平均，是因为队长是组织的责任人 ——
 * 让他为自己的进度给全队解锁名额，比让五个人互相等要少得多摩擦。
 */
public final class Squad {

    /**
     * 一档小队等级规则（= squad_config 表的一行）。
     *
     * @param squadLevel      小队等级
     * @param memberCap       该等级的人数上限
     * @param unlockMainLevel 生效所需的<b>队长</b>主城等级
     * @param rallyCapacity   集结人数上限（须等于 global.RALLY_MAX_SIZE_SQUAD）
     * @param helpSpeedBonus  每次互助削减的比例（定点）
     * @param shopUnlock      小队商店是否已解锁
     */
    public record LevelRule(long squadLevel, long memberCap, long unlockMainLevel,
                            long rallyCapacity, long helpSpeedBonusFixed, boolean shopUnlock) {
        public LevelRule {
            if (squadLevel < 1) {
                throw new IllegalArgumentException("squadLevel 必须 >= 1，实际=" + squadLevel);
            }
            if (memberCap < 1) {
                throw new IllegalArgumentException("memberCap 必须 >= 1，实际=" + memberCap);
            }
            if (unlockMainLevel < 1) {
                throw new IllegalArgumentException("unlockMainLevel 必须 >= 1，实际=" + unlockMainLevel);
            }
            if (rallyCapacity < 1) {
                throw new IllegalArgumentException("rallyCapacity 必须 >= 1，实际=" + rallyCapacity);
            }
            if (helpSpeedBonusFixed < 0) {
                throw new IllegalArgumentException("helpSpeedBonus 不得为负，实际=" + helpSpeedBonusFixed);
            }
        }
    }

    /**
     * @param levels        各等级规则，按 squadLevel 升序
     * @param unlockMainLevel 创建小队所需的主城等级（取 Lv1 那行）
     * @param unlockDayOffset 创建小队所需的开服天数（取 Lv1 那行）
     * @param expBase       Lv1→Lv2 所需活跃度
     * @param expGrowth     升级活跃度的几何增长率
     */
    public record Rules(List<LevelRule> levels, long unlockMainLevel, long unlockDayOffset,
                        long expBase, long expGrowthFixed) {
        public Rules {
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException("小队等级规则不得为空：没有规则就算不出人数上限");
            }
            List<LevelRule> copy = new ArrayList<>(levels);
            copy.sort((a, b) -> Long.compare(a.squadLevel(), b.squadLevel()));
            for (int i = 1; i < copy.size(); i++) {
                LevelRule previous = copy.get(i - 1);
                LevelRule current = copy.get(i);
                if (current.memberCap() < previous.memberCap()) {
                    throw new IllegalArgumentException("人数上限必须随等级单调不减：Lv" + previous.squadLevel()
                            + "=" + previous.memberCap() + " 而 Lv" + current.squadLevel()
                            + "=" + current.memberCap() + "。递减意味着升级会把成员挤出去");
                }
            }
            levels = Collections.unmodifiableList(copy);
            if (unlockMainLevel < 1) {
                throw new IllegalArgumentException("unlockMainLevel 必须 >= 1，实际=" + unlockMainLevel);
            }
            if (unlockDayOffset < 0) {
                throw new IllegalArgumentException("unlockDayOffset 不得为负，实际=" + unlockDayOffset);
            }
            if (expBase < 1) {
                throw new IllegalArgumentException("expBase 必须 >= 1，否则建队即满级，实际=" + expBase);
            }
            if (expGrowthFixed < 10000) {
                throw new IllegalArgumentException("expGrowth 必须 >= 定点 1.0，否则越升越容易，实际="
                        + expGrowthFixed);
            }
        }

        public int maxLevel() {
            return (int) levels.get(levels.size() - 1).squadLevel();
        }
    }

    /** 一次成员变动的结果。 */
    public record Membership(String playerId, SquadRole role) {
    }

    private final String id;
    private final String name;
    private String leaderId;
    private final Rules rules;
    /** playerId → 角色。LinkedHashMap 保持加入顺序（成员列表要稳定，客户端不重排）。 */
    private final Map<String, SquadRole> members = new LinkedHashMap<>();
    private int level;
    private long exp;
    private String allianceId;
    /** playerId → 小队币余额。小队币是个人资产（去小队商店换东西），不是公共资金 */
    private final Map<String, Long> squadCoins = new LinkedHashMap<>();
    private long squadCoinPool;
    private long dailyQuestProgress;
    private long disbandedAt;

    private Squad(String id, String name, String leaderId, Rules rules) {
        this.id = id;
        this.name = name;
        this.leaderId = leaderId;
        this.rules = rules;
        this.level = 1;
        this.members.put(leaderId, SquadRole.LEADER);
    }

    /** 创建小队。发起人即队长。 */
    public static Squad create(String id, String name, String leaderId, Rules rules) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("小队 id 不得为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("小队名不得为空");
        }
        if (leaderId == null || leaderId.isBlank()) {
            throw new IllegalArgumentException("队长 id 不得为空");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        return new Squad(id, name, leaderId, rules);
    }

    /**
     * 创建前置校验（主城等级 + 开服天数）。
     *
     * @param mainCityLevel 发起人主城等级
     * @param dayOffset     当前是开服第几天（0 = 开服当天）
     * @return null 表示满足；否则返回差什么，供 detail 字段直接展示（绝不静默失败）
     */
    public static String checkUnlock(long mainCityLevel, long dayOffset, Rules rules) {
        if (mainCityLevel < rules.unlockMainLevel()) {
            return "需要主城 " + rules.unlockMainLevel() + " 级，当前 " + mainCityLevel + " 级";
        }
        if (dayOffset < rules.unlockDayOffset()) {
            return "需要开服第 " + (rules.unlockDayOffset() + 1) + " 天，当前第 " + (dayOffset + 1) + " 天";
        }
        return null;
    }

    /**
     * 当前生效的人数上限（验收 3）。
     *
     * @param leaderMainCityLevel <b>队长</b>的主城等级。第二档 8 人的门槛在它身上，
     *                            不在小队等级上 —— 传错人会让上限永远停在 5
     */
    public int memberCap(int leaderMainCityLevel) {
        int cap = 0;
        for (LevelRule rule : rules.levels()) {
            if (rule.squadLevel() > level) {
                break;
            }
            if (rule.unlockMainLevel() > leaderMainCityLevel) {
                continue;
            }
            cap = (int) rule.memberCap();
        }
        if (cap == 0) {
            // 一档都不满足只可能是队长主城掉到了 Lv1 的门槛以下（降级/重置），
            // 此时给 Lv1 的上限而不是 0 —— 上限为 0 会让一个已存在的小队变成非法状态
            cap = (int) rules.levels().get(0).memberCap();
        }
        return cap;
    }

    /** 加入小队。 */
    public void join(String playerId, int leaderMainCityLevel) {
        requireActive();
        if (members.containsKey(playerId)) {
            throw new IllegalStateException("你已经在小队里");
        }
        if (members.size() >= memberCap(leaderMainCityLevel)) {
            throw new IllegalStateException("小队人数已满（上限 " + memberCap(leaderMainCityLevel) + " 人）");
        }
        members.put(playerId, SquadRole.MEMBER);
    }

    /**
     * 队长离开联盟（验收 2）。
     *
     * <p><b>小队自动解散，队员收到通知</b>。理由：小队的分队身份来自联盟，
     * 而队长是分队与联盟之间唯一的连接点 —— 队长走了，这个分队就没有责任人，
     * 让队员自动选一个新队长等于替他们做了一个组织决定（谁当队长是熟人圈子里最敏感的事）。
     *
     * <p>与之相对，<b>普通队员退盟不影响小队</b>：他只是不再是分队的一员，
     * 小队本身仍然挂在联盟下（见 {@link #memberLeavesAlliance}）。
     *
     * @return 需要通知的成员 id（不含队长自己 —— 他自己做的决定不需要通知自己）
     */
    public List<String> leaderLeavesAlliance(long now) {
        requireActive();
        if (allianceId == null) {
            // 独立小队的队长「退盟」是个不存在的操作：他本来就不在任何联盟里。
            // 静默返回会让调用方以为解散成功了，所以明确抛错
            throw new IllegalStateException("小队是独立状态，队长没有联盟可退");
        }
        List<String> toNotify = new ArrayList<>();
        for (String playerId : members.keySet()) {
            if (!playerId.equals(leaderId)) {
                toNotify.add(playerId);
            }
        }
        disband(now);
        return Collections.unmodifiableList(toNotify);
    }

    /** 普通队员离开联盟：只解除他与分队的关系，小队本身不受影响（关键设计点 1）。 */
    public void memberLeavesAlliance(String playerId) {
        requireActive();
        if (!members.containsKey(playerId)) {
            throw new IllegalStateException("对方不是本小队成员");
        }
        if (playerId.equals(leaderId)) {
            throw new IllegalStateException("队长退盟要走 leaderLeavesAlliance：那会解散整个小队");
        }
        members.remove(playerId);
        squadCoins.remove(playerId);
    }

    /** 加入联盟：小队转为联盟内分队，<b>成员、等级、活跃度、小队币一律保留</b>（验收 1）。 */
    public void attachToAlliance(String newAllianceId) {
        requireActive();
        if (newAllianceId == null || newAllianceId.isBlank()) {
            throw new IllegalArgumentException("allianceId 不得为空");
        }
        this.allianceId = newAllianceId;
    }

    /** 全员退出联盟：小队退回独立状态，同样不清空任何东西。 */
    public void detachFromAlliance() {
        requireActive();
        this.allianceId = null;
    }

    /** 退出小队（队长退出走 {@link #leaderLeavesAlliance} 或 {@link #transferLeadership}）。 */
    public void leave(String playerId) {
        requireActive();
        if (playerId.equals(leaderId)) {
            throw new IllegalStateException("队长不能直接退队：先转让队长或解散小队，"
                    + "否则小队会剩下没有责任人的成员");
        }
        if (members.remove(playerId) == null) {
            throw new IllegalStateException("对方不是本小队成员");
        }
        squadCoins.remove(playerId);
    }

    /** 踢人。权限位由 PermissionMatrix 裁决，这里只做状态变更。 */
    public void kick(String operatorId, String targetId) {
        requireActive();
        if (!operatorId.equals(leaderId)) {
            throw new IllegalStateException("只有队长能踢人");
        }
        if (targetId.equals(leaderId)) {
            throw new IllegalStateException("队长不能踢自己：要退就先转让或解散");
        }
        if (members.remove(targetId) == null) {
            throw new IllegalStateException("对方不是本小队成员");
        }
        squadCoins.remove(targetId);
    }

    /** 转让队长。 */
    public void transferLeadership(String operatorId, String newLeaderId) {
        requireActive();
        if (!operatorId.equals(leaderId)) {
            throw new IllegalStateException("只有队长能转让");
        }
        if (!members.containsKey(newLeaderId)) {
            throw new IllegalStateException("对方不是本小队成员");
        }
        members.put(leaderId, SquadRole.MEMBER);
        members.put(newLeaderId, SquadRole.LEADER);
        leaderId = newLeaderId;
    }

    /** 解散小队。 */
    public void disband(long now) {
        requireActive();
        members.clear();
        squadCoins.clear();
        disbandedAt = now;
    }

    /**
     * 累积活跃度并按需升级。
     *
     * @return 升级后的等级（未升级则等于原等级）
     */
    public int addExp(long amount) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("活跃度必须为正，实际=" + amount);
        }
        exp += amount;
        // 一次投喂可能连升数级（与武将升级同一条处理方式）：
        // 只升一级的话，玩家攒了很久活跃度却只看到一级变化，会以为活跃度被吞了
        while (level < rules.maxLevel() && exp >= expToNext()) {
            exp -= expToNext();
            level++;
        }
        if (level >= rules.maxLevel()) {
            exp = Math.min(exp, expToNext());
        }
        return level;
    }

    /** 升到下一级还需多少活跃度；满级返回 0。 */
    public long expToNext() {
        if (level >= rules.maxLevel()) {
            return 0L;
        }
        long requirement = rules.expBase();
        for (int i = 1; i < level; i++) {
            requirement = com.ironoath.common.num.FixedPoint.round(
                    com.ironoath.common.num.FixedPoint.mul(
                            com.ironoath.common.num.FixedPoint.of(requirement), rules.expGrowthFixed()));
        }
        return requirement;
    }

    /**
     * 完成每日小队任务：全队每人发小队币，任务进度累加。
     *
     * <p>按人发而不是往公共池里加：小队币的唯一出口是小队商店，
     * 而商店是个人消费。公共池会让「谁能花」变成一个需要投票的问题，
     * 而 5~10 人的熟人圈子里最不该出现的就是投票。
     */
    public long completeDailyQuest(long coinPerMember, long questTarget) {
        requireActive();
        dailyQuestProgress += questTarget;
        for (String playerId : members.keySet()) {
            squadCoins.merge(playerId, coinPerMember, Long::sum);
        }
        squadCoinPool += coinPerMember * members.size();
        return squadCoinPool;
    }

    /** 某人的小队币余额。 */
    public long squadCoinOf(String playerId) {
        return squadCoins.getOrDefault(playerId, 0L);
    }

    /**
     * 花小队币（商店兑换）。
     *
     * <p>判定与扣减在同一步：先查余额再扣的话，并发两次兑换会同时通过检查
     * （与 B00 禁止的「先查后改扣资源」同一类问题）。
     */
    public long spendSquadCoin(String playerId, long amount) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("消耗额必须为正，实际=" + amount);
        }
        long balance = squadCoins.getOrDefault(playerId, 0L);
        if (balance < amount) {
            throw new IllegalStateException("小队币不足：需要 " + amount + "，当前 " + balance);
        }
        long left = balance - amount;
        squadCoins.put(playerId, left);
        return left;
    }

    // ---------- 只读访问 ----------

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String leaderId() {
        return leaderId;
    }

    public int level() {
        return level;
    }

    public long exp() {
        return exp;
    }

    public int memberCount() {
        return members.size();
    }

    public SquadRole roleOf(String playerId) {
        return members.get(playerId);
    }

    public boolean isMember(String playerId) {
        return members.containsKey(playerId);
    }

    /** 成员 id，按加入顺序。 */
    public List<String> memberIds() {
        return Collections.unmodifiableList(new ArrayList<>(members.keySet()));
    }

    /** 所属联盟 id；独立小队为 null。 */
    public String allianceId() {
        return allianceId;
    }

    /** 是否为联盟内分队。**true 时小队功能全部保留**（验收 1）。 */
    public boolean isSubSquad() {
        return allianceId != null;
    }

    /** 是否已解散。 */
    public boolean isDisbanded() {
        return disbandedAt > 0L;
    }

    public long disbandedAt() {
        return disbandedAt;
    }

    public long squadCoinPool() {
        return squadCoinPool;
    }

    public long dailyQuestProgress() {
        return dailyQuestProgress;
    }

    public Rules rules() {
        return rules;
    }

    /** 当前等级的集结人数上限。 */
    public int rallyCapacity() {
        for (LevelRule rule : rules.levels()) {
            if (rule.squadLevel() == level) {
                return (int) rule.rallyCapacity();
            }
        }
        return (int) rules.levels().get(0).rallyCapacity();
    }

    /** 小队商店是否已解锁。 */
    public boolean shopUnlocked() {
        for (LevelRule rule : rules.levels()) {
            if (rule.squadLevel() == level) {
                return rule.shopUnlock();
            }
        }
        return false;
    }

    private void requireActive() {
        if (isDisbanded()) {
            throw new IllegalStateException("小队已解散，不能再变更");
        }
    }

    /** 供仓储重建。 */
    public static Squad restore(String id, String name, String leaderId, Rules rules,
                               Map<String, SquadRole> members, int level, long exp,
                               String allianceId, Map<String, Long> squadCoins,
                               long squadCoinPool, long dailyQuestProgress, long disbandedAt) {
        Squad squad = new Squad(id, name, leaderId, rules);
        squad.members.clear();
        squad.members.putAll(members);
        squad.level = level;
        squad.exp = exp;
        squad.allianceId = allianceId;
        squad.squadCoins.putAll(squadCoins);
        squad.squadCoinPool = squadCoinPool;
        squad.dailyQuestProgress = dailyQuestProgress;
        squad.disbandedAt = disbandedAt;
        return squad;
    }
}
