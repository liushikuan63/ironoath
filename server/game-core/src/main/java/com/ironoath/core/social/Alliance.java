package com.ironoath.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：联盟领域模型（B10 §2、验收 3/7/8/10）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架 —— game-core 读不到 game-config）。
 *
 * <p><b>联盟存在的核心价值是庇护</b>（B10 关键设计点 2）：弱者被打了，联盟有义务响应。
 * 这是 B08 反击工具箱「庇护/投靠」的落地点，也是玩家愿意留在联盟的真正原因。
 * 本模型里体现为两点：成员列表带 lastActiveAt（不活跃的盟提供不了庇护，盟主要能看出来），
 * 以及版本号 diff 同步（验收 10）—— 盟友被攻击要在 3 秒内推到（验收 5），
 * 全量同步 150 人的数据会让那个 3 秒预算全部耗在传输上。
 *
 * <p><b>人数上限与「扩容」分开建模</b>（B10 §2：每级扩容消耗联盟资金，中后期最重要的金币消耗点之一）：
 * {@code level} 决定表里的上限档位，但档位要<b>花钱解锁</b>才生效。
 * 所以有效上限 = min(等级档位, 已付费档位)。
 * 若不做这个区分，联盟一升级人数上限就自动放开，
 * 「扩容」这个中后期最重要的资金消耗点就退化成一个不需要决策的数字变化。
 *
 * <p><b>捐献两条线同步增长</b>（验收 8）：一次捐献同时增加联盟资金（公共资产）与
 * 个人贡献值（私人资产），两者的比例在 global.DONATE_TIER_* 里成对配置。
 * 只给公共的那条会让付费玩家觉得自己在做慈善，只给私人的那条会让联盟攒不出扩容的钱。
 */
public final class Alliance {

    /**
     * 一档联盟等级规则（= alliance_config 表的一行）。
     *
     * <p><b>只登记奇数级</b>（1/3/5/7/9）：偶数级不改变任何上限，
     * 登记它们就得为 Lv2/4/6/8 编造四个文档里没有的数字，
     * 而那四个数字一旦被当成「官方数值」引用就再也改不掉了。
     * 查表规则是「取 allianceLevel <= 当前等级 的最高一行」，所以 Lv2 自然沿用 Lv1。
     */
    public record LevelRule(long allianceLevel, long memberCap, long unlockMainLevel, long unlockDayOffset,
                            long territoryCap, long rallyCapacity, long techCapBonusFixed, long donationDailyCap) {
        public LevelRule {
            if (allianceLevel < 1) {
                throw new IllegalArgumentException("allianceLevel 必须 >= 1，实际=" + allianceLevel);
            }
            if (memberCap < 1) {
                throw new IllegalArgumentException("memberCap 必须 >= 1，实际=" + memberCap);
            }
            if (territoryCap < 1) {
                throw new IllegalArgumentException("territoryCap 必须 >= 1，实际=" + territoryCap);
            }
            if (rallyCapacity < 1) {
                throw new IllegalArgumentException("rallyCapacity 必须 >= 1，实际=" + rallyCapacity);
            }
            if (techCapBonusFixed < 0) {
                throw new IllegalArgumentException("techCapBonus 不得为负，实际=" + techCapBonusFixed);
            }
            if (donationDailyCap < 1) {
                throw new IllegalArgumentException("donationDailyCap 必须 >= 1，否则捐献功能等于不存在，实际="
                        + donationDailyCap);
            }
        }
    }

    /**
     * 一档捐献（免费 / 资源 / 金币）。
     *
     * @param tier              档位序号，0 起
     * @param costResourceType  消耗的资源 id；免费档为 null
     * @param costResourceAmount 消耗的资源量；免费档为 0
     * @param costGold          消耗的金币；非金币档为 0
     * @param fundGained        给联盟的资金
     * @param contributionGained 给个人的贡献值
     * @param expGained         给联盟的经验
     */
    public record DonateTier(int tier, String costResourceType, long costResourceAmount, long costGold,
                             long fundGained, long contributionGained, long expGained) {
        public DonateTier {
            if (tier < 0) {
                throw new IllegalArgumentException("tier 不得为负，实际=" + tier);
            }
            if (fundGained < 0 || contributionGained < 0 || expGained < 0) {
                throw new IllegalArgumentException("捐献产出不得为负：fund=" + fundGained
                        + " contribution=" + contributionGained + " exp=" + expGained);
            }
            boolean hasResourceCost = costResourceType != null && costResourceAmount > 0;
            boolean hasGoldCost = costGold > 0;
            if (tier > 0 && !hasResourceCost && !hasGoldCost) {
                throw new IllegalArgumentException("档位 " + tier + " 是付费档却没有消耗："
                        + "免费档只有 tier=0 一档，多一档免费就等于把资金白送");
            }
            if (tier == 0 && (hasResourceCost || hasGoldCost)) {
                throw new IllegalArgumentException("免费档（tier=0）不能有消耗，实际资源=" + costResourceAmount
                        + " 金币=" + costGold);
            }
            if (hasResourceCost && hasGoldCost) {
                throw new IllegalArgumentException("一档捐献只能消耗一种东西：资源与金币同时消耗会让玩家"
                        + "无法比较两档的性价比");
            }
        }
    }

    /**
     * @param levels               各等级规则，按 allianceLevel 升序
     * @param unlockMainLevel      创建所需主城等级（取 Lv1 那行）
     * @param unlockDayOffset      创建所需开服天数（取 Lv1 那行）
     * @param createCostGold       创建消耗的金币。来源 global.ALLIANCE_CREATE_COST_GOLD
     * @param disbandProtectMillis 解散保护期（毫秒）。来源 global.ALLIANCE_DISBAND_PROTECT_SECONDS
     * @param expandCostBase       首次扩容消耗。来源 global.ALLIANCE_EXPAND_COST_BASE
     * @param expandCostGrowthFixed 扩容消耗的几何增长率（定点）。来源 global.ALLIANCE_EXPAND_COST_GROWTH
     * @param donateTiers          捐献档位，按 tier 升序
     * @param techCostGrowthFixed  联盟科技每级消耗的增长率（定点）。来源 global.ALLIANCE_TECH_COST_GROWTH
     */
    public record Rules(List<LevelRule> levels, long unlockMainLevel, long unlockDayOffset,
                        long createCostGold, long disbandProtectMillis,
                        long expandCostBase, long expandCostGrowthFixed,
                        List<DonateTier> donateTiers, long techCostGrowthFixed) {
        public Rules {
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException("联盟等级规则不得为空：没有规则就算不出人数上限");
            }
            List<LevelRule> levelCopy = new ArrayList<>(levels);
            levelCopy.sort((a, b) -> Long.compare(a.allianceLevel(), b.allianceLevel()));
            for (int i = 1; i < levelCopy.size(); i++) {
                LevelRule previous = levelCopy.get(i - 1);
                LevelRule current = levelCopy.get(i);
                if (current.memberCap() < previous.memberCap()) {
                    throw new IllegalArgumentException("人数上限必须随等级单调不减：Lv" + previous.allianceLevel()
                            + "=" + previous.memberCap() + " 而 Lv" + current.allianceLevel()
                            + "=" + current.memberCap());
                }
                if (current.territoryCap() < previous.territoryCap()) {
                    throw new IllegalArgumentException("领地上限必须随等级单调不减");
                }
            }
            levels = Collections.unmodifiableList(levelCopy);

            if (donateTiers == null || donateTiers.isEmpty()) {
                throw new IllegalArgumentException("捐献档位不得为空：捐献是联盟资金的唯一来源，"
                        + "没有档位联盟就永远攒不出扩容的钱");
            }
            List<DonateTier> tierCopy = new ArrayList<>(donateTiers);
            tierCopy.sort((a, b) -> Integer.compare(a.tier(), b.tier()));
            for (int i = 0; i < tierCopy.size(); i++) {
                if (tierCopy.get(i).tier() != i) {
                    throw new IllegalArgumentException("捐献档位必须从 0 起连续编号，实际缺了 " + i);
                }
            }
            donateTiers = Collections.unmodifiableList(tierCopy);

            if (createCostGold < 0) {
                throw new IllegalArgumentException("createCostGold 不得为负，实际=" + createCostGold);
            }
            if (disbandProtectMillis < 0) {
                throw new IllegalArgumentException("disbandProtectMillis 不得为负，实际=" + disbandProtectMillis);
            }
            if (expandCostBase < 0) {
                throw new IllegalArgumentException("expandCostBase 不得为负，实际=" + expandCostBase);
            }
            if (expandCostGrowthFixed < FixedPoint.SCALE) {
                throw new IllegalArgumentException("expandCostGrowth 必须 >= 定点 1.0，否则越扩越便宜，实际="
                        + expandCostGrowthFixed);
            }
            if (techCostGrowthFixed < FixedPoint.SCALE) {
                throw new IllegalArgumentException("techCostGrowth 必须 >= 定点 1.0，实际=" + techCostGrowthFixed);
            }
            if (unlockMainLevel < 1) {
                throw new IllegalArgumentException("unlockMainLevel 必须 >= 1，实际=" + unlockMainLevel);
            }
            if (unlockDayOffset < 0) {
                throw new IllegalArgumentException("unlockDayOffset 不得为负，实际=" + unlockDayOffset);
            }
        }

        public int maxLevel() {
            return (int) levels.get(levels.size() - 1).allianceLevel();
        }

        /** 有几个「人数上限档位」需要付费解锁（= 等级规则的行数 - 1，Lv1 是创建时自带的）。 */
        public int capTierCount() {
            return levels.size();
        }

        /**
         * 第 index 次扩容的消耗（index 从 0 起）。
         *
         * <p>几何增长：base × growth^index。用定点乘法逐级累乘而不是 Math.pow ——
         * 后者返回 double，而资金结算禁止浮点（B00）。
         */
        public long expandCost(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("扩容序号不得为负，实际=" + index);
            }
            long cost = expandCostBase;
            for (int i = 0; i < index; i++) {
                cost = FixedPoint.round(FixedPoint.mul(FixedPoint.of(cost), expandCostGrowthFixed));
            }
            return cost;
        }

        /** 联盟科技第 level 级的消耗 = base × growth^(level-1)。 */
        public long techCost(long base, int level) {
            if (level < 1) {
                throw new IllegalArgumentException("科技等级必须 >= 1，实际=" + level);
            }
            long cost = base;
            for (int i = 1; i < level; i++) {
                cost = FixedPoint.round(FixedPoint.mul(FixedPoint.of(cost), techCostGrowthFixed));
            }
            return cost;
        }

        /** 解散保护期的截止时刻（验收 7）。 */
        public long disbandProtectUntil(long disbandedAt) {
            return disbandedAt + disbandProtectMillis;
        }
    }

    /** 一次捐献的结果（验收 8：资金与贡献值同步增加）。 */
    public record Donation(long fundGained, long contributionGained, long fund, long contribution,
                           int donateToday, int dailyCap) {
    }

    /** 一次扩容的结果。 */
    public record Expansion(long cost, long fund, int level, int memberCap) {
    }

    /**
     * 一次研究的结果。
     *
     * @param fundCost    本次这几级的<b>总</b>消耗（逐级按 growth 递增后求和，不是一个等级的价）
     * @param levelCap    当前联盟等级下的上限。<b>必须下发给客户端</b>：这个数要查
     *                    {@code alliance_config} 与 {@code alliance_tech} 两张表再乘才算得出
     * @param effectFixed 研究到 {@code level} 级后的累计效果值（定点）。按每级线性累加，
     *                    与表里 {@code effectValue} 是「单级幅度」的口径一致
     */
    public record Research(String techId, int level, int levelCap, long fundCost, long fundAfter,
                           long effectFixed) {
    }

    private final String id;
    private final String name;
    private final String tag;
    private final Rules rules;
    private String leaderId;
    /** playerId → 职位。LinkedHashMap 保持加入顺序，成员列表因此稳定（客户端不重排）。 */
    private final Map<String, AllianceRole> members = new LinkedHashMap<>();
    /** playerId → 累计贡献值。 */
    private final Map<String, Long> contributions = new LinkedHashMap<>();
    /**
     * 「谁在今天捐过哪一档」的账本。
     *
     * <p>key = {@code playerId + ":" + dayKey + ":" + tier}。2026-09-13 裁决（收口清单 §三·补 A1）
     * 把口径定成<b>每档每日一次</b>（B10 §2 原文「每日 3 档（免费 / 资源 / 金币）」），
     * 所以 tier <b>必须在键里</b>：只按「人 + 日」计数会让一个人把免费档刷三遍拿满贡献值，
     * 而那贡献值还是联盟商店的货币 —— 零成本刷商店货币是经济口子，不只是数值难看。
     *
     * <p><b>值恒为 1，全部信息都在键上</b>。留 Map 而不是换成 Set，是为了复用已经落地并通过
     * 等价测试的文档形状（{@code List<DonationEntry>}）；改那一层不带来任何玩家可见的收益。
     */
    private final Map<String, Integer> donatedToday = new LinkedHashMap<>();
    /**
     * techId → 已研究等级（B10 §2 联盟科技）。
     *
     * <p><b>没研究过的科技不写 0 占位</b>：表里有 6 行（v2 起，见 §三·补 B10/B11），全表补零会让每个联盟都挂一堆无意义记录，
     * 换成持久化存储后那是要占空间的。读取一律走 {@link #techLevel(String)}，那里按缺失=0 处理。
     */
    private final Map<String, Integer> techLevels = new LinkedHashMap<>();
    private int level;
    /** 当前等级内已累计的经验。addExp 原本把余量丢掉了，而面板要显示「还差多少升级」 */
    private long exp;
    private long fund;
    private int territoryCount;
    /** 已付费解锁的人数上限档位序号（0 = 只有 Lv1 自带的 30 人）。 */
    private int paidCapTier;
    private long version;
    private long disbandedAt;

    private Alliance(String id, String name, String tag, String leaderId, Rules rules) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.leaderId = leaderId;
        this.rules = rules;
        this.level = 1;
        this.paidCapTier = 0;
        this.version = 1L;
        this.members.put(leaderId, AllianceRole.LEADER);
        this.contributions.put(leaderId, 0L);
    }

    /**
     * 创建联盟。
     *
     * @param goldBalance 创建者的金币余额。<b>扣费判定在这里做而不是在调用方</b>：
     *                    分两处做的话，「先查后改」的窗口里两次并发创建会同时通过检查
     */
    public static Alliance create(String id, String name, String tag, String leaderId,
                                 long goldBalance, Rules rules) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("联盟 id 不得为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("联盟名不得为空");
        }
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("联盟标签不得为空：标签是地图上与昵称后唯一能一眼认出的标识");
        }
        if (leaderId == null || leaderId.isBlank()) {
            throw new IllegalArgumentException("盟主 id 不得为空");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (goldBalance < rules.createCostGold()) {
            throw new IllegalStateException("金币不足：创建联盟需要 " + rules.createCostGold()
                    + "，当前 " + goldBalance);
        }
        return new Alliance(id, name, tag, leaderId, rules);
    }

    /** 创建联盟要扣的金币。供调用方执行扣费（本类不碰钱包）。 */
    public static long createCost(Rules rules) {
        return rules.createCostGold();
    }

    /**
     * 创建前置校验（主城等级 + 开服天数）。
     *
     * <p>B10 §2 还有第三个前置「曾加入过小队（或付费跳过）」—— 那条判定需要读玩家的历史记录，
     * 属服务层的职责，所以不在这里。本方法只校验两个纯数值门槛。
     *
     * @return null 表示满足；否则返回差什么，供 detail 字段直接展示
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

    /** 加入联盟（申请通过后调用）。 */
    public void join(String playerId) {
        requireActive();
        if (members.containsKey(playerId)) {
            throw new IllegalStateException("你已经在联盟里");
        }
        if (members.size() >= effectiveMemberCap()) {
            throw new IllegalStateException("联盟人数已满（上限 " + effectiveMemberCap() + " 人）");
        }
        members.put(playerId, AllianceRole.MEMBER);
        contributions.put(playerId, 0L);
        bumpVersion();
    }

    /** 退出联盟。盟主退出必须先转让或解散。 */
    public void leave(String playerId) {
        requireActive();
        if (playerId.equals(leaderId)) {
            throw new IllegalStateException("盟主不能直接退盟：先转让或解散，"
                    + "否则联盟会剩下没有责任人的成员");
        }
        if (members.remove(playerId) == null) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        contributions.remove(playerId);
        dropDonateLedger(playerId);
        bumpVersion();
    }

    /** 踢人。权限位由 PermissionMatrix 裁决，这里只做状态变更。 */
    public void kick(String operatorId, String targetId) {
        requireActive();
        if (!members.containsKey(operatorId)) {
            throw new IllegalStateException("操作者不是本联盟成员");
        }
        if (targetId.equals(leaderId)) {
            throw new IllegalStateException("不能踢盟主：要换人请走转让");
        }
        if (members.remove(targetId) == null) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        contributions.remove(targetId);
        dropDonateLedger(targetId);
        bumpVersion();
    }

    /** 转让盟主。 */
    public void transferLeadership(String operatorId, String newLeaderId) {
        requireActive();
        if (!operatorId.equals(leaderId)) {
            throw new IllegalStateException("只有盟主能转让");
        }
        if (newLeaderId.equals(leaderId)) {
            throw new IllegalStateException("不能把盟主转让给自己");
        }
        if (!members.containsKey(newLeaderId)) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        members.put(leaderId, AllianceRole.OFFICER);
        members.put(newLeaderId, AllianceRole.LEADER);
        leaderId = newLeaderId;
        bumpVersion();
    }

    /** 任命职位。不能任命高于自己的职位（否则副盟主能造出一个新盟主）。 */
    public void setRole(String operatorId, String targetId, AllianceRole newRole) {
        requireActive();
        AllianceRole operatorRole = members.get(operatorId);
        if (operatorRole == null) {
            throw new IllegalStateException("操作者不是本联盟成员");
        }
        if (!members.containsKey(targetId)) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        if (newRole == null) {
            throw new IllegalArgumentException("职位不得为 null");
        }
        if (newRole.rank() >= operatorRole.rank() && operatorRole != AllianceRole.LEADER) {
            throw new IllegalStateException("不能任命不低于自己的职位：当前 " + operatorRole
                    + "，目标 " + newRole);
        }
        if (targetId.equals(leaderId) && newRole != AllianceRole.LEADER) {
            throw new IllegalStateException("降级盟主必须走转让，否则联盟会没有盟主");
        }
        members.put(targetId, newRole);
        bumpVersion();
    }

    /**
     * 捐献（验收 8）。
     *
     * <p><b>余额校验不在这里</b>：本类不碰钱包（game-core 不知道资源系统长什么样）。
     * 调用方必须先扣款再调本方法 —— 顺序反了的话，扣款失败会留下一笔白给的资金。
     *
     * @param dayKey 日切键（来自 game-common 的 DayKey），用于每日档数上限
     */
    // synchronized 的理由与 researchTech 同一条：档位上限的「读-判断-写」与资金/贡献值的变更
    // 都是联盟级共享状态，而应用层拿的是玩家锁 —— 两个成员同时捐同一档时彼此不互斥，
    // dailyCap 会被双双通过，捐献次数上限形同虚设。
    public synchronized Donation donate(String playerId, int tier, String dayKey) {
        requireActive();
        if (!members.containsKey(playerId)) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        if (dayKey == null || dayKey.isBlank()) {
            throw new IllegalArgumentException("dayKey 不得为空：没有它就无法判断「今日」的边界");
        }
        Alliance.DonateTier definition = donateTier(tier);
        int dailyCap = donationDailyCap();
        String tierKey = playerId + ":" + dayKey + ":" + tier;
        // 「这一档今天捐过」先判，且排在总量上限之前：它更具体，玩家照着就能换一档；
        // 反过来先报"次数用完"会让一个还能捐的人去做一件本来不必要的事（明天再来）
        if (donatedToday.containsKey(tierKey)) {
            throw new IllegalStateException("档位 " + tier + " 今日已捐过：每档每天只能捐一次（共 "
                    + dailyCap + " 档）");
        }
        int used = donatedCount(playerId, dayKey);
        if (used >= dailyCap) {
            throw new IllegalStateException("今日捐献档位已用完（上限 " + dailyCap + " 档）");
        }
        donatedToday.put(tierKey, 1);

        fund += definition.fundGained();
        long contribution = contributions.getOrDefault(playerId, 0L) + definition.contributionGained();
        contributions.put(playerId, contribution);
        bumpVersion();
        return new Donation(definition.fundGained(), definition.contributionGained(),
                fund, contribution, used + 1, dailyCap);
    }

    /**
     * 花掉成员自己的贡献值（B10 §联盟商店：「贡献值：可兑换联盟商店道具」，验收 8 要求兑换正确扣减）。
     *
     * <p><b>它同时是两样东西，而这两样会互相拉扯</b>：贡献值既是联盟商店的货币，
     * 又是「谁为联盟做了多少」的展示账。所以花掉它 = 排名下降。
     * 这不是漏洞，是「可兑换」三个字的必然结果 —— 但如果哪天有人把联盟排行榜改成
     * 「按当前贡献值排序」，那么「捐了就上榜、换了道具就掉榜」会让兑换变成一种惩罚。
     * 真要那张榜，应当另记一份「历史累计贡献」（只增），而不是禁止兑换。
     *
     * <p><b>余额校验不在这里做第二次</b>：本类只管账本平衡，「够不够」由应用层转成业务错误码
     * （{@code ALLIANCE_CONTRIBUTION_LACK}）。抛 IllegalStateException 的口径与
     * {@code Squad#spendSquadCoin} 一致，两条社交货币的失败形状不该不同。
     *
     * <p>顺序纪律与 {@link #donate} 相反：兑换必须<b>先扣贡献值、再发道具</b>，
     * 道具发放失败时由调用方退回 —— 先发货后扣款的话，扣款失败就白送了一件。
     *
     * @return 扣减后的余额
     */
    public long spendContribution(String playerId, long amount) {
        requireActive();
        if (!members.containsKey(playerId)) {
            throw new IllegalStateException("对方不是本联盟成员");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("扣减额必须为正数，实际=" + amount);
        }
        long held = contributions.getOrDefault(playerId, 0L);
        if (held < amount) {
            throw new IllegalStateException("贡献值不足：需要 " + amount + "，当前 " + held);
        }
        long left = held - amount;
        contributions.put(playerId, left);
        bumpVersion();
        return left;
    }

    /** 取某一档捐献的定义。 */
    public static Alliance.DonateTier donateTierOf(Rules rules, int tier) {
        for (Alliance.DonateTier definition : rules.donateTiers()) {
            if (definition.tier() == tier) {
                return definition;
            }
        }
        throw new IllegalArgumentException("未知的捐献档位：" + tier
                + "（配置里只有 " + rules.donateTiers().size() + " 档）");
    }

    private Alliance.DonateTier donateTier(int tier) {
        return donateTierOf(rules, tier);
    }

    /**
     * 扩容：花联盟资金解锁下一个人数上限档位（B10 §2 的中后期资金消耗点）。
     *
     * <p><b>扩容与等级是两件事</b>：等级由捐献累积的经验推进，档位要单独付费解锁。
     * 不做这个区分的话，联盟一升级人数上限就自动放开，扩容就退化成一个不需要决策的数字变化。
     *
     * @return 扩容结果；已经是最高档位时抛错
     */
    // synchronized 的理由同 donate/researchTech：paidCapTier 的「读-判断-扣-写」是联盟级共享状态，
    // 两个官员并发扩容会各自按同一档位扣一次钱却只抬一档
    public synchronized Expansion expand() {
        requireActive();
        int nextTier = paidCapTier + 1;
        if (nextTier >= rules.levels().size()) {
            throw new IllegalStateException("联盟人数上限已达最高档位");
        }
        if (level < rules.levels().get(nextTier).allianceLevel()) {
            throw new IllegalStateException("联盟等级不足：解锁 "
                    + rules.levels().get(nextTier).memberCap() + " 人需要 Lv"
                    + rules.levels().get(nextTier).allianceLevel() + "，当前 Lv" + level);
        }
        long cost = rules.expandCost(nextTier - 1);
        if (fund < cost) {
            throw new IllegalStateException("联盟资金不足：扩容需要 " + cost + "，当前 " + fund);
        }
        fund -= cost;
        paidCapTier = nextTier;
        bumpVersion();
        return new Expansion(cost, fund, level, effectiveMemberCap());
    }

    /** 消耗联盟资金（科技研究、领地建造共用）。返回扣后余额。 */
    public synchronized long spendFund(long amount, String reason) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("消耗额必须为正，实际=" + amount + "（" + reason + "）");
        }
        if (fund < amount) {
            throw new IllegalStateException("联盟资金不足：" + reason + " 需要 " + amount + "，当前 " + fund);
        }
        fund -= amount;
        bumpVersion();
        return fund;
    }

    /**
     * 研究联盟科技（B10 §2「用联盟资金研究，全盟生效，等级上限随联盟等级」）。
     *
     * <p><b>为什么 {@code synchronized}</b>：应用层的锁是按<b>玩家</b>加的（{@code PlayerLock}），
     * 而联盟资金是按<b>联盟</b>共享的资产。两个官员同时点研究时各自持为自己的玩家锁、彼此不互斥，
     * 「检查余额 → 扣款 → 抬等级」就会在两笔请求之间被穿过：轻则把公账扣成负数，
     * 重则两笔都按同一个起点等级计价（后一笔看不到前一笔的等级），等于少收一级增长后的差价。
     * 国库周税（收口清单 #25）踩过同一类问题，处理方式也一样：<b>把检查与变更放进同一个监视器</b>。
     *
     * <p><b>这条保护只在单实例内成立</b>。换成 Mongo 之后这里拿到的是读出来的副本，
     * 监视器不再共享，必须改成带条件的原子更新（{@code fund >= cost} 才扣，等级同一条更新里原子 +levels）
     * —— 已记在 #16 那笔存储债里。
     *
     * @param baseCost             该科技 1 级的基础消耗（{@code alliance_tech.costBaseDonation}）
     * @param tableMaxLevel        表里的等级上限（实际上限还要按联盟等级放大，见 {@link #techLevelCap}）
     * @param effectPerLevelFixed  单级效果幅度（定点），只用来回算累计值给调用方下发
     * @param levels               本次研究几级。<b>超出上限时整笔拒绝而不是裁剪</b>：
     *                             默默按"能研究几级"收钱，玩家看到的扣款会比他以为的多
     */
    public synchronized Research researchTech(String techId, long baseCost, int tableMaxLevel,
                                             long effectPerLevelFixed, int levels) {
        requireActive();
        if (techId == null || techId.isBlank()) {
            throw new IllegalArgumentException("techId 不得为空：它是科技账本的唯一键");
        }
        if (levels < 1) {
            throw new IllegalArgumentException("一次研究的等级数必须 >= 1，实际=" + levels);
        }
        int current = techLevels.getOrDefault(techId, 0);
        int cap = techLevelCap(tableMaxLevel);
        if (current >= cap) {
            throw new IllegalStateException("科技「" + techId + "」已到当前联盟等级允许的上限 " + cap
                    + " 级（联盟再升级可继续）");
        }
        if (current + levels > cap) {
            throw new IllegalStateException("科技「" + techId + "」当前 " + current + " 级，"
                    + "联盟等级允许的上限是 " + cap + " 级，本次最多只能研究 "
                    + (cap - current) + " 级");
        }
        long cost = researchCost(baseCost, techId, levels);
        if (fund < cost) {
            throw new IllegalStateException("联盟资金不足：研究「" + techId + "」" + levels
                    + " 级需要 " + cost + "，当前 " + fund);
        }
        int target = current + levels;
        fund -= cost;
        techLevels.put(techId, target);
        bumpVersion();
        return new Research(techId, target, cap, cost, fund, effectPerLevelFixed * target);
    }

    /**
     * 从当前等级再研究 {@code levels} 级的<b>总</b>消耗（逐级按 growth 递增后求和）。
     *
     * <p>公开它是为了让调用方能在动手前算出准确价格并给出「差多少钱」的提示，
     * 而<b>不必把这段循环抄一遍</b> —— 一份价格算两处实现，迟早会出现预检说够、扣款说不够，
     * 而那时玩家看到的是一次莫名其妙的失败。
     */
    public long researchCost(long baseCost, String techId, int levels) {
        int current = techLevels.getOrDefault(techId, 0);
        if (levels < 1) {
            throw new IllegalArgumentException("一次研究的等级数必须 >= 1，实际=" + levels);
        }
        long cost = 0L;
        for (int next = current + 1; next <= current + levels; next++) {
            cost += rules.techCost(baseCost, next);
        }
        return cost;
    }

    /** 某项联盟科技的当前等级（没研究过就是 0，账本里不存 0 占位）。 */
    public int techLevel(String techId) {
        return techLevels.getOrDefault(techId, 0);
    }

    /** 已研究的科技（只读副本）。未研究的不在其中，读取请用 {@link #techLevel(String)}。 */
    public Map<String, Integer> techLevels() {
        return Map.copyOf(techLevels);
    }

    /** 增加联盟资金（活动奖励、领地收入等外部来源）。 */
    public long addFund(long amount) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("增加额必须为正，实际=" + amount);
        }
        fund += amount;
        bumpVersion();
        return fund;
    }

    /** 建造一处领地（堡垒/旗帜）。 */
    public void buildTerritory() {
        requireActive();
        if (territoryCount >= territoryCap()) {
            throw new IllegalStateException("领地数量已达上限（" + territoryCap() + "）");
        }
        territoryCount++;
        bumpVersion();
    }

    /** 累积联盟经验并升级。等级上限受表约束。 */
    public int addExp(long amount) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("经验必须为正，实际=" + amount);
        }
        // 联盟等级不按经验曲线推进，而是「每凑满一次扩容所需的资金量就升一级」——
        // 那样会让等级与资金耦合，扩容就失去独立决策的意义。
        // 这里用捐献经验直接推进等级，扩容另外收费，两条线互不干扰。
        long needed = expPerLevel();
        long total = exp + amount;
        while (level < rules.maxLevel() && total >= needed) {
            total -= needed;
            level++;
            needed = expPerLevel();
        }
        // 满级后不再累积：继续涨会让面板显示一个永远到不了的进度条
        exp = (level >= rules.maxLevel()) ? 0L : total;
        bumpVersion();
        return level;
    }

    /** 当前等级升下一级所需的联盟经验。 */
    public long expPerLevel() {
        // 与扩容消耗同一量级但独立计数：一次扩容约 2 万资金，
        // 经验取它的 1/10 让「升级」比「扩容」频繁，玩家才有持续的进度感
        return Math.max(1L, rules.expandCost(Math.max(0, paidCapTier)) / 10L);
    }

    /** 解散联盟（验收 7 的保护期由调用方按 {@link Rules#disbandProtectUntil} 计算）。 */
    public void disband(String operatorId, long now) {
        requireActive();
        if (!operatorId.equals(leaderId)) {
            throw new IllegalStateException("只有盟主能解散联盟");
        }
        members.clear();
        contributions.clear();
        donatedToday.clear();
        disbandedAt = now;
        bumpVersion();
    }

    /**
     * 日切：清空「今日已捐档数」。由日切流程调用，key 里已经带了 dayKey 所以直接整体清。
     *
     * <p><b>只有真的清掉了东西才推进版本</b>：没有捐献记录时清空是空操作，
     * 推进版本会让客户端 diff 平白刷新一次；而有记录时不推进则更糟 ——
     * 旧版本号会让"结算前那份副本"照样写进来，把刚清掉的计数又装回去。
     */
    public void rollDay() {
        if (donatedToday.isEmpty()) {
            return;
        }
        donatedToday.clear();
        bumpVersion();
    }

    // ---------- 只读访问 ----------

    /**
     * 当前生效的人数上限（验收 3：30→50→80→120→150）。
     *
     * <p>取「等级档位」与「已付费档位」的较小值，理由见类注释。
     */
    public int effectiveMemberCap() {
        int tier = Math.min(capTierOf(level), paidCapTier);
        return (int) rules.levels().get(tier).memberCap();
    }

    /** 等级对应的档位序号（取 allianceLevel <= level 的最高一行）。 */
    private int capTierOf(int currentLevel) {
        int tier = 0;
        for (int i = 0; i < rules.levels().size(); i++) {
            if (rules.levels().get(i).allianceLevel() <= currentLevel) {
                tier = i;
            }
        }
        return tier;
    }

    private LevelRule currentLevelRule() {
        return rules.levels().get(capTierOf(level));
    }

    /** 领地上限。 */
    public int territoryCap() {
        return (int) currentLevelRule().territoryCap();
    }

    /** 集结人数上限。 */
    public int rallyCapacity() {
        return (int) currentLevelRule().rallyCapacity();
    }

    /**
     * 联盟科技的等级上限（B10 §2：等级上限随联盟等级）。
     *
     * @param tableMaxLevel alliance_tech 表里该科技的 maxLevel
     * @return 当前联盟等级下的实际上限 = tableMaxLevel × (1 + techCapBonus)
     */
    public int techLevelCap(int tableMaxLevel) {
        long bonus = currentLevelRule().techCapBonusFixed();
        long scaled = FixedPoint.round(FixedPoint.mul(FixedPoint.of(tableMaxLevel),
                FixedPoint.SCALE + bonus));
        return (int) Math.max(tableMaxLevel, scaled);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String tag() {
        return tag;
    }

    public String leaderId() {
        return leaderId;
    }

    public int level() {
        return level;
    }

    /** 当前等级内已累计的经验；满级为 0。 */
    public long exp() {
        return exp;
    }

    public long fund() {
        return fund;
    }

    public int territoryCount() {
        return territoryCount;
    }

    public int paidCapTier() {
        return paidCapTier;
    }

    public int memberCount() {
        return members.size();
    }

    public AllianceRole roleOf(String playerId) {
        return members.get(playerId);
    }

    public boolean isMember(String playerId) {
        return members.containsKey(playerId);
    }

    public List<String> memberIds() {
        return Collections.unmodifiableList(new ArrayList<>(members.keySet()));
    }

    /** 成员角色表（只读副本，按加入顺序）。仓储映射用。 */
    public Map<String, AllianceRole> members() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    /** 全部贡献值（只读副本）。仓储映射用；注意它可能包含已离盟成员的历史贡献。 */
    public Map<String, Long> contributions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(contributions));
    }

    public long contributionOf(String playerId) {
        return contributions.getOrDefault(playerId, 0L);
    }

    /** 今天已捐掉的<b>档数</b>（不是次数上限意义上的"次数"）。用于视图的 X/N 展示。 */
    public int donatedToday(String playerId, String dayKey) {
        return donatedCount(playerId, dayKey);
    }

    /**
     * 今天已经捐过哪几档（升序）。2026-09-13 裁决「每档每日一次」之后才有意义：
     * 计数版只说"还剩几次"，客户端因此不知道该灰掉哪个按钮，只能摆出来等玩家点了收报错
     * （收口清单 §三 那条 B10 缺口的原文）。
     */
    public List<Integer> donatedTiers(String playerId, String dayKey) {
        String prefix = playerId + ":" + dayKey + ":";
        return donatedToday.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> Integer.parseInt(key.substring(prefix.length())))
                .sorted()
                .toList();
    }

    /** 今日可捐档数上限（当前联盟等级那行 alliance_config 的 donationDailyCap）。 */
    public int donationDailyCap() {
        return (int) currentLevelRule().donationDailyCap();
    }

    private int donatedCount(String playerId, String dayKey) {
        String prefix = playerId + ":" + dayKey + ":";
        int count = 0;
        for (String key : donatedToday.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清掉某个人的当日捐献账（退盟 / 被踢）。
     *
     * <p><b>这一方法是修出来的，不是顺手抽的</b>：原先 leave 与 kick 里写的是
     * {@code donatedToday.remove(playerId)}，而键是 {@code playerId:dayKey:tier} ——
     * <b>那个 remove 永远删不到任何东西</b>。后果是把旧盟的"今天已捐"记录跟人进新盟
     * （在新盟里那几档显示已捐过），并且这张表在日切前只增不减。
     * 前缀清理的形状与 {@code PopupThrottle.forget} 一致。
     */
    private void dropDonateLedger(String playerId) {
        String prefix = playerId + ":";
        donatedToday.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 全部"当日已捐"记录（只读副本）。
     *
     * <p><b>key 是 {@code playerId + ":" + dayKey + ":" + tier} 复合键</b>，
     * 不是单个 playerId —— 仓储映射必须原样保存与恢复它。丢掉这份账本的表现是：
     * 同一天可以无限次捐献，每次都能拿到贡献值（每日上限变成了摆设）。
     */
    public Map<String, Integer> donatedTodayByKey() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(donatedToday));
    }

    /**
     * 联盟数据版本号（验收 10：只下发 diff，不全量同步）。
     *
     * <p>每次状态变更 +1。客户端带着手里的版本来同步，版本相同就返回 unchanged=true
     * 与三个空列表 —— 与 B07 地图 chunk 的版本号是同一套思路。
     */
    public long version() {
        return version;
    }

    public boolean isDisbanded() {
        return disbandedAt > 0L;
    }

    public long disbandedAt() {
        return disbandedAt;
    }

    public Rules rules() {
        return rules;
    }

    private void bumpVersion() {
        version++;
    }

    private void requireActive() {
        if (isDisbanded()) {
            throw new IllegalStateException("联盟已解散，不能再变更");
        }
    }

    /**
     * 供仓储重建。
     *
     * <p><b>donatedToday 与 techLevels 必须一起恢复</b>：前者是"今天还能捐几次"的账本，
     * 后者是联盟科技的等级。早先这个方法的签名里没有它们，于是"用 restore 重建一份存档"
     * 会静默丢掉当日捐献次数与全部已研究科技 —— 内存实现里没人调用所以看不出来，
     * 一旦拿它写 Mongo 映射就是每天白送捐献额度、科技等级归零。Mongo 实现已经落地，
     * 并由 {@code SocialStoreEquivalenceTest#allianceRoundTripsEveryField} 钉住逐字段往返一致。
     */
    public static Alliance restore(String id, String name, String tag, String leaderId, Rules rules,
                                   Map<String, AllianceRole> members, Map<String, Long> contributions,
                                   Map<String, Integer> donatedToday, Map<String, Integer> techLevels,
                                   int level, long exp, long fund, int territoryCount, int paidCapTier,
                                   long version, long disbandedAt) {
        Alliance alliance = new Alliance(id, name, tag, leaderId, rules);
        alliance.members.clear();
        alliance.members.putAll(members);
        alliance.contributions.clear();
        alliance.contributions.putAll(contributions);
        alliance.donatedToday.clear();
        alliance.donatedToday.putAll(donatedToday);
        alliance.techLevels.clear();
        alliance.techLevels.putAll(techLevels);
        alliance.level = level;
        alliance.exp = exp;
        alliance.fund = fund;
        alliance.territoryCount = territoryCount;
        alliance.paidCapTier = paidCapTier;
        alliance.version = version;
        alliance.disbandedAt = disbandedAt;
        return alliance;
    }

    /**
     * 深拷贝。存储层「读返回副本」用：Mongo 版每次读都重新拼一个对象，
     * 内存版必须给出同一个语义，否则"改了没 save"在 dev 下看不出来、上线才丢档。
     * 规则对象不可变可共享；成员、贡献、当日捐献、科技四张表必须复制。
     */
    public Alliance copy() {
        return restore(id, name, tag, leaderId, rules, members, contributions,
                donatedToday, techLevels, level, exp, fund, territoryCount, paidCapTier,
                version, disbandedAt);
    }
}
