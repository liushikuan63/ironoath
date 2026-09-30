package com.ironoath.core.nation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：国家领域模型（B13 §1~§6，验收 1/2/3/5）。
 * 依赖：无（纯 Java，零框架 —— game-core 读不到 game-config，规则由外层解析后传入）。
 *
 * <p><b>国家与联盟的关系是本类的第一约束</b>（B13 §二、禁止项）：
 * <b>联盟整体加入国家，个人跟随联盟，不单独入籍</b>。所以本类的成员表键是
 * {@code allianceId} 而不是 {@code playerId} —— 用 playerId 当键的话，
 * 「个人脱离联盟单独入籍」这条禁止项在数据结构上就是可表达的，
 * 而可表达的禁止项早晚会被表达出来。
 *
 * <p><b>四条冲突规则在数据层强制</b>（开工提示词明写「必须提前定死并在数据层强制，
 * 否则后期全是 Bug」）：
 * <ol>
 *   <li>联盟退出/被开除 ⇒ 全联盟失去国籍，并进入 {@code joinCooldownUntil}（验收 2 的 24h）</li>
 *   <li>成员联盟退盟 ⇒ 该联盟失去国籍（个人跟随联盟，所以不需要逐人处理）</li>
 *   <li>外交关系优先于私人关系：判定只有 {@link #mayAttackNation} 一处，签名里没有任何
 *       私人关系参数（没有小队、没有好友、没有复仇）。"这两个玩家能不能互打"由 game-web 的
 *       统一攻击闸门把双方各翻译成国家再来问它 —— 本类没有联盟粒度的方法，关系就是国家粒度的</li>
 *   <li>优先级 国家 &gt; 联盟 &gt; 小队：本类不与 Squad/Alliance 互相持有引用，
 *       冲突的裁决点在 service 层，而裁决依据全部来自本类的状态</li>
 * </ol>
 *
 * <p><b>国库每一笔支出都必须有日志</b>（§3、验收 5、禁止项）：
 * 谁、何时、支给谁、多少。这条不是审计洁癖 ——
 * 国库是公共资产，而公共资产的纠纷会溢出到现实（盟主卷款、公会撕逼上社交媒体）。
 * 所以 {@link #spend} 在签名上就要求 reason 与 payee，不给「悄悄扣一笔」留入口。
 */
public final class Nation {

    /** 官职。六档来自 B13 §2 的席位表。 */
    public enum Office {
        /** 国王，1 席。宣战、任命全部官职、国库支出、终审提案 */
        KING,
        /** 首相，1 席。内政、任命下级、国库支出（限额） */
        PRIME_MINISTER,
        /** 大将军，2 席。发起国战、调动集结、军事指令 */
        GENERAL,
        /** 内政官，4 席。国策提案、国库管理 */
        MINISTER,
        /** 外交官，4 席。外交关系变更、盟约谈判 */
        DIPLOMAT,
        /** 议员，每盟主 1 席。投票、提案 */
        REPRESENTATIVE;

        /** B13 §2 的席位数。议员按盟主数动态给，所以这里返回 -1 表示「不按固定席位」。 */
        public int seatCount() {
            return switch (this) {
                case KING, PRIME_MINISTER -> 1;
                case GENERAL -> 2;
                case MINISTER, DIPLOMAT -> 4;
                case REPRESENTATIVE -> -1;
            };
        }
    }

    /** 外交关系（§5）。四种关系直接影响国战分组与跨服匹配。 */
    public enum Diplomacy {
        /** 盟约：不可互相攻击（双向，见 {@link #mayAttackEachOther}） */
        ALLIED,
        /** 敌对：可攻击，国战的主要对象 */
        HOSTILE,
        /** 中立：默认状态 */
        NEUTRAL,
        /**
         * 朝贡：B13 §5 原文只有「朝贡」两个字，没说钱往哪流、谁护着谁。
         *
         * <p><b>2026-09-13 裁决 C22：禁攻是双向的</b>。此前这里写的是「单向纳贡，不可被宣战」，
         * 读起来像"朝贡国被打不动、但它随时能打宗主" —— 交钱的一方保留反噬债主的能力，
         * 原文并没有给这个权利，而它也不合常理。现在两侧都打不动，
         * 由 {@link #mayAttackEachOther} 表达（本枚举仍然只描述"这一侧怎么看对方"，
         * 因为单边视角本来就是单向的）。
         */
        TRIBUTARY
    }

    /**
     * @param levels             各国家等级规则，按 nationLevel 升序
     * @param unlockMainLevel    建国所需主城等级（取 Lv1 那行）
     * @param unlockDayOffset    建国所需开服天数（0-based，取 Lv1 那行）
     * @param maxPerKingdom      单 kingdom 的国家数量上限。来源 global.NATION_MAX_PER_KINGDOM
     * @param joinCooldownMillis 联盟退出国家后的入籍冷却（毫秒）。来源 global.NATION_JOIN_COOLDOWN_HOURS
     * @param taxWeeklyPerAlliance 每盟每周上缴的税收。来源 global.NATION_TAX_WEEKLY_PER_ALLIANCE
     * @param treasuryLogRetention 国库日志保留条数。来源 global.NATION_TREASURY_LOG_RETENTION
     * @param officeSeatTotal    固定官职席位总数（议员不计）。来源 global.NATION_OFFICE_SEAT_TOTAL
     * @param officerSpendRatioFixed 非国王身份的本周国库支出上限 = 本周实收入账 × 本比例（定点，10000=1.0）。
     *                               来源 global.NATION_OFFICER_SPEND_WEEKLY_RATIO。国王不受此限
     * @param policyVoteMillis   一次国策**投票窗**的长度（毫秒）。来源 global.NATION_VOTE_DURATION_HOURS
     * @param policyRoundMillis  一整轮国策周期的长度（毫秒），= 投票窗 + 提案段 + 生效段。
     * @param policyMinVoteRatio 通过门槛：赞成票 ÷ <b>实际投票人数</b> 的下限（定点，10000 = 1.0）。
     *                            来源 global.NATION_POLICY_PASS_RATIO
     * @param policyMinVotersPerAlliance 参与下限：实际投票人数至少要有「成员联盟数 × 本值」。
     *                                  来源 global.NATION_POLICY_MIN_VOTERS_PER_ALLIANCE
     */
    public record Rules(List<LevelRule> levels, long unlockMainLevel, long unlockDayOffset,
                        int maxPerKingdom, long joinCooldownMillis, long taxWeeklyPerAlliance,
                        int treasuryLogRetention, int officeSeatTotal, long officerSpendRatioFixed,
                        long policyVoteMillis, long policyRoundMillis,
                        long policyMinVoteRatio, int policyMinVotersPerAlliance) {
        public Rules {
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException("国家等级规则不得为空");
            }
            List<LevelRule> copy = new ArrayList<>(levels);
            copy.sort((a, b) -> Long.compare(a.nationLevel(), b.nationLevel()));
            for (int i = 1; i < copy.size(); i++) {
                if (copy.get(i).memberCap() < copy.get(i - 1).memberCap()) {
                    throw new IllegalArgumentException("人数上限必须随等级单调不减");
                }
                if (copy.get(i).treasuryCap() < copy.get(i - 1).treasuryCap()) {
                    throw new IllegalArgumentException("国库容量必须随等级单调不减："
                            + "联盟数翻倍则税收翻倍，容量不跟着翻倍就会溢出，"
                            + "而溢出意味着收上来的税凭空消失");
                }
            }
            levels = Collections.unmodifiableList(copy);
            if (maxPerKingdom < 2) {
                throw new IllegalArgumentException("maxPerKingdom 必须 >= 2，实际=" + maxPerKingdom
                        + "。只有一个国家就不存在外交，而外交是 B13 §5 的全部内容");
            }
            if (joinCooldownMillis < 0) {
                throw new IllegalArgumentException("joinCooldownMillis 不得为负，实际=" + joinCooldownMillis);
            }
            if (taxWeeklyPerAlliance < 0) {
                throw new IllegalArgumentException("税收不得为负，实际=" + taxWeeklyPerAlliance);
            }
            if (treasuryLogRetention < 1) {
                throw new IllegalArgumentException("国库日志保留条数必须 >= 1，实际=" + treasuryLogRetention
                        + "。禁止项明写「不要让国库支出无日志」，保留 0 条等于没有日志");
            }
            if (officeSeatTotal < 1) {
                throw new IllegalArgumentException("官职席位总数必须 >= 1，实际=" + officeSeatTotal);
            }
            if (officerSpendRatioFixed < 0 || officerSpendRatioFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("官员周支出上限比例必须落在 0~1（定点 0~" + FixedPoint.SCALE
                        + "），实际=" + officerSpendRatioFixed
                        + "。超过 1 意味着官员一周能花掉超过一周的税收，那已经不叫限额");
            }
            if (unlockMainLevel < 1) {
                throw new IllegalArgumentException("unlockMainLevel 必须 >= 1，实际=" + unlockMainLevel);
            }
            if (unlockDayOffset < 0) {
                throw new IllegalArgumentException("unlockDayOffset 不得为负，实际=" + unlockDayOffset);
            }
            if (policyVoteMillis <= 0L) {
                throw new IllegalArgumentException("国策投票窗长度必须为正，实际=" + policyVoteMillis
                        + "（0 意味着投票窗开出来就关，票数恒为 0）");
            }
            if (policyRoundMillis < policyVoteMillis) {
                throw new IllegalArgumentException("国策一轮周期不得短于投票窗，实际 round="
                        + policyRoundMillis + " vote=" + policyVoteMillis
                        + "：周期短于窗口的话，轮次会在窗口还没关时就结算，票数只统计到一部分人");
            }
            if (policyMinVoteRatio < 0L || policyMinVoteRatio > FixedPoint.SCALE) {
                throw new IllegalArgumentException("国策通过门槛必须落在 0~1（定点 0~" + FixedPoint.SCALE
                        + "），实际=" + policyMinVoteRatio + "。超过 1 意味着全票赞成都通不过");
            }
        }

        public int maxLevel() {
            return (int) levels.get(levels.size() - 1).nationLevel();
        }
    }

    /**
     * 一档国家等级（= nation_config 表的一行）。
     *
     * @param memberCap       人数上限（= 成员联盟容量之和的上限）
     * @param officeCount     固定官职席位总数
     * @param treasuryCap     国库容量上限
     * @param policySlotCount 可同时生效的国策槽位数
     * @param warCooldownHours 宣战冷却（小时）
     */
    public record LevelRule(long nationLevel, long memberCap, long officeCount,
                            long treasuryCap, long policySlotCount, long warCooldownHours) {
        public LevelRule {
            if (nationLevel < 1) {
                throw new IllegalArgumentException("nationLevel 必须 >= 1，实际=" + nationLevel);
            }
            if (memberCap < 1) {
                throw new IllegalArgumentException("memberCap 必须 >= 1，实际=" + memberCap);
            }
            if (treasuryCap < 0) {
                throw new IllegalArgumentException("treasuryCap 不得为负，实际=" + treasuryCap);
            }
            if (policySlotCount < 1) {
                throw new IllegalArgumentException("policySlotCount 必须 >= 1，实际=" + policySlotCount);
            }
            if (warCooldownHours < 1) {
                throw new IllegalArgumentException("warCooldownHours 必须 >= 1，实际=" + warCooldownHours
                        + "。冷却为 0 会让国家之间陷入无休止的消耗战");
            }
        }
    }

    /**
     * 一笔国库日志（验收 5：谁 / 何时 / 支给谁 / 多少）。
     *
     * <p><b>列的约定</b>：{@code amount} 是这一笔的<b>规模（正数）</b>，方向不由符号表示，
     * 而由 {@code payee} 的身份决定 —— 入账时它是来源（如 {@code war_loot}），支出时它是支给对象。
     * <b>对账请用 {@code balanceAfter} 的差</b>：那一列是按发生顺序记录的余额，
     * 任何一行与前后行的差额不符，就说明有人在改余额而不走 {@link #spend} / {@link #deposit}。
     */
    public record TreasuryLog(long at, String operatorId, String payee, long amount, String reason,
                              long balanceAfter) {
        public TreasuryLog {
            if (operatorId == null || operatorId.isBlank()) {
                throw new IllegalArgumentException("国库日志必须记录操作者：没有「谁」的日志无法追责");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("国库日志必须记录用途：没有「为什么」的日志等于没有日志");
            }
        }
    }

    // ---------- 国策（B13 §4 / B21 块③，2026-09-30 裁决） ----------

    /** 轮次处在哪一段。与协议 `NationPolicyPhase` 逐项对应。 */
    public enum PolicyPhase {
        /** 可提案，投票窗未开。 */
        PROPOSING,
        /** 投票窗开着，可投票。 */
        VOTING,
        /** 本轮已结算，有国策正在生效。 */
        ACTIVE
    }

    /**
     * 为什么现在不能做这个动作。带类型而不是靠比对文案（与 {@link TechBlock} 同一条理由：
     * 靠 message 区分错误码，改一句提示就会把两个码悄悄混回去）。
     */
    public enum PolicyBlock {
        NONE,
        /** 身份没有提案权（读 role_permission 表，领域层不读配置表，由调用方判定后传入）。 */
        NOT_PROPOSER,
        /** 身份够，但此刻不是提案段（投票窗开着）或不是投票段。 */
        NOT_VOTING,
        /** 这条提案本轮已经投过了（改票本批不做）。 */
        ALREADY_VOTED,
        /** 这条国策本轮已经提过了。 */
        ALREADY_PROPOSED,
        /** 本轮还没有任何提案，投票窗开不起来。 */
        NO_PROPOSAL_YET
    }

    /** 一条提案。{@code id} 由调用方给（领域层不生成 id，与 {@code techId} 同一条分工）。 */
    public record PolicyProposal(String id, String policyId, String proposedBy, long at) {
        public PolicyProposal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("提案 id 不得为空");
            }
            if (policyId == null || policyId.isBlank()) {
                throw new IllegalArgumentException("提案必须指向一条国策（policyId）");
            }
            if (proposedBy == null || proposedBy.isBlank()) {
                throw new IllegalArgumentException("提案必须记录提案人：没有「谁提的」就无法追溯国策的来源");
            }
        }
    }

    /**
     * 一条提案的计票结果。
     *
     * <p><b>名单按 playerId 升序</b>：公示（B13 §4「票数与参与者可查」）会被客户端原样渲染，
     * 而顺序若依赖 HashMap 的桶布局，同一份存档在不同 JVM 上会给出不同的名单 ——
     * 那正是 B05 §1.7 为兵种容器强制 {@code EnumMap} 的同一条理由。
     *
     * @param passed 是否通过门槛（只看票数，不看槽位 —— 槽位竞争是另一层）
     */
    public record ProposalTally(String proposalId, String policyId, String proposedBy, long at,
                                long yes, long no, List<String> supporters, List<String> opponents,
                                boolean passed) {
        public ProposalTally {
            supporters = List.copyOf(supporters);
            opponents = List.copyOf(opponents);
        }

        /** 实际投票人数 = 赞成 + 反对。**弃权不计入**（2026-09-30 裁决 A3 的推论）。 */
        public long actualVoters() {
            return yes + no;
        }
    }

    /**
     * 正在生效的一条国策。
     *
     * @param expiresAt 到期时刻。到期那一刻它从生效列表里消失，槽位空出来。
     */
    public record ActivePolicy(String policyId, long expiresAt) {
    }

    /**
     * 国策轮次的完整状态 —— 应用层与客户端只看这一份。
     *
     * @param myProposals 我本轮提过的提案 id
     * @param myVotes     我本轮投过的票（提案 id → 赞成与否）
     */
    public record PolicyRound(PolicyPhase phase, int slotCount, int memberAllianceCount,
                              List<ProposalTally> proposals, List<ActivePolicy> active,
                              PolicyBlock proposeBlock, PolicyBlock voteBlock,
                              List<String> myProposals, Map<String, Boolean> myVotes,
                              long nextVoteAt, long voteEndsAt) {
        public PolicyRound {
            proposals = List.copyOf(proposals);
            active = List.copyOf(active);
            myProposals = List.copyOf(myProposals);
            myVotes = Map.copyOf(myVotes);
        }
    }

    /** 提案或投票被 {@link PolicyBlock} 挡住。带类型，理由与 {@link TechResearchException} 同款。 */
    public static final class PolicyException extends IllegalStateException {
        private final PolicyBlock block;

        PolicyException(PolicyBlock block, String message) {
            super(message);
            this.block = block;
        }

        public PolicyBlock block() {
            return block;
        }
    }

    /**
     * 国库支出的落点（B13 §3 的三个用途在 2026-09-11 的裁决里收敛成两类）。
     *
     * <p>原文三个用途中，「国家科技」与「国战增益」是<b>被子系统消耗</b>的（钱出去之后没有收款人），
     * 只有「官职俸禄」是发给某个人的。所以落点在<b>类型上</b>就只有两类，而不是一个自由字符串 ——
     * 后者会让「支给谁」写成任意东西（{@code "tech"}、一个昵称、空串），
     * 而这张日志存在的理由恰恰是纠纷发生时能查（§3、验收 5、禁止项）。
     *
     * <p>{@link #text()} 是写进日志那一列的形态：{@code player:P123} / {@code sink:NATIONAL_TECH}。
     * 前缀让「这一笔到底有没有收款人」一眼可辨，也让查询能按前缀分类 ——
     * 这一列同时承担入账的来源（见 {@link TreasuryLog} 的列约定），前缀同样是分类依据。
     */
    public record Payee(Kind kind, String target) {

        /** 两类落点：给某个人 / 给某个消耗性用途。 */
        public enum Kind {
            /** 发给玩家（俸禄） */
            PLAYER,
            /** 由子系统消耗（核销）：钱出库，但没有收款人 */
            SINK
        }

        /** 两个消耗性用途。名字取 B13 §3 的原文，进日志后可直接对照文档。 */
        public enum Sink {
            /** 国家科技 */
            NATIONAL_TECH,
            /** 国战增益 */
            WAR_BOOST
        }

        public Payee {
            if (kind == null) {
                throw new IllegalArgumentException("落点类型不得为 null");
            }
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("落点不得为空：没有「支给谁」的日志无法追责");
            }
        }

        /** 发给某个玩家（俸禄）。 */
        public static Payee toPlayer(String playerId) {
            return new Payee(Kind.PLAYER, playerId);
        }

        /**
         * 由某个消耗性用途核销。
         *
         * <p><b>「没有收款人」也要显式写出来</b>：留空会让日志上分不清「钱被某个子系统吃了」
         * 与「操作者没填支给谁」—— 前者是正常出账，后者是漏洞。
         */
        public static Payee toSink(Sink sink) {
            if (sink == null) {
                throw new IllegalArgumentException("消耗性用途不得为 null");
            }
            return new Payee(Kind.SINK, sink.name());
        }

        /** 写进日志那一列的文本形态。 */
        public String text() {
            return kind == Kind.PLAYER ? "player:" + target : "sink:" + target;
        }
    }

    /** 建国前置校验的结果。null 表示满足。 */
    public record UnlockBlock(String reason) {
    }

    /**
     * 系统产生的那几笔国库变动（周税）的操作者标识。
     *
     * <p>不能填某个玩家 id：周税不是谁操作的，是规则到点结的。写成玩家会让那笔账看起来像
     * 某个人经手了国库，而 {@code operatorId} 恰恰是纠纷时唯一能指认的东西。
     */
    public static final String OPERATOR_SYSTEM = "system";

    private final String id;
    private final String name;
    private final Rules rules;
    private String kingId;
    private long capitalX;
    private long capitalY;
    private int level;
    private long treasury;
    /** 成员联盟 id → 加入时刻。键是联盟而不是玩家：个人不单独入籍 */
    private final Map<String, Long> memberAlliances = new LinkedHashMap<>();
    /** 官职 → 持有者。一个席位一个人，多席位官职用 List */
    private final Map<Office, List<String>> offices = new EnumMap<>(Office.class);
    /** 目标国家 → 外交关系。未登记的默认中立 */
    private final Map<String, Diplomacy> diplomacy = new LinkedHashMap<>();
    /** 联盟 id → 入籍冷却截止时刻（验收 2） */
    private final Map<String, Long> joinCooldownUntil = new LinkedHashMap<>();
    private final List<TreasuryLog> treasuryLogs = new ArrayList<>();
    private final List<String> provinces = new ArrayList<>();
    /**
     * 国家科技账本：{@code nation_tech.json} 的行 id → 等级（B20 块③）。
     *
     * <p><b>不存 0 占位</b>：没研究过的行根本不在这里，读取一律走 {@link #techLevel(String)}（缺失即 0）——
     * 与 {@code Alliance#techLevels} 与 {@code PlayerTech} 同一条读法。11 个 0 塞进文档只会长出
     * "改了表就要重存"这种麻烦，而不会带来任何信息。
     *
     * <p><b>记在国家上而不是玩家上</b>：出的是国库（公共钱），生效范围是全国成员。
     * 两者若各存一份，症状就是"谁的钱花的、谁拿到的加成"对不上。
     */
    private final Map<String, Integer> techLevels = new LinkedHashMap<>();

    // ---------- 国策（B13 §4 / B21 块③，2026-09-30） ----------

    /**
     * 本轮的提案：提案 id → 提案。<b>本轮</b>的意思是「上一次结算之后」。
     *
     * <p><b>为什么提案是「本轮」而不是长期存在</b>：国策是周期性投票选出来的 buff，
     * 到期就没了 —— 一条三个月前的提案不该在今天还能被投（B13 §4 原话是「周期性投票」）。
     * 结算时整份清空。
     */
    private final Map<String, PolicyProposal> policyProposals = new LinkedHashMap<>();
    /**
     * 投票：提案 id → (投票者 id → 赞成与否)。<b>存成两层而不是「每人一票」</b>：
     * 投票权是**每成员一票**（2026-09-30 裁决 A2），而 B13 §二 的
     * {@code NationVoteReq(proposalId, boolean support)} 是<b>每提案一票</b> ——
     * 两个维度都真实存在，压成一个数就再也答不出「谁投了这条」。
     */
    private final Map<String, Map<String, Boolean>> policyVotes = new LinkedHashMap<>();
    /** 正在生效的国策：policyId → 到期时刻。长度不超过 {@link #policySlotCount()}。 */
    private final Map<String, Long> activePolicies = new LinkedHashMap<>();
    /** 下一次开投票窗的时刻。0 = 还没定（建国后的第一轮由「第一条提案」触发）。 */
    private long policyNextVoteAt;
    /** 投票窗开启时刻；0 = 没开。 */
    private long policyVoteOpenedAt;
    /** 投票窗结束时刻；0 = 没开。 */
    private long policyVoteEndsAt;
    private long lastTaxWeekKey;
    /**
     * 最近一次周税**实际入库**的金额（不是应收额）。C16 限额的分母。
     *
     * <p>为什么不用「每盟税额 × 联盟数」算：国库有容量上限，满了之后实收小于应收，
     * 用应收算等于让一个国家花掉它从没收到过的钱。
     */
    private long lastTaxCredited;
    /** spentThisWeek 所属的周键（与周税用的 {@code WeekKey} 数字键同源）。跨周即重新计。 */
    private long spendWeekKey;
    /**
     * 本周已经由<b>非国王</b>身份支取走的金额。全国共用一个池子，不是每人一份。
     *
     * <p><b>刻意不从 {@code treasuryLogs} 反推</b>：日志有 {@code NATION_TREASURY_LOG_RETENTION}
     * 条硬上限，一个花得猛的国会把它滚掉 —— 那时限额就会在最需要它的时候悄悄失效，
     * 而"看起来在挡、其实没挡"比没有这个挡更坏。
     */
    private long spentThisWeek;
    private long disbandedAt;
    /**
     * 乐观锁版本。<b>只有仓储能改它</b>（每次成功落库 +1），业务方法一律不碰。
     *
     * <p>为什么必须有：官职、外交、周税都是"读-改-写"，而 {@code PlayerLock} 是<b>按玩家</b>
     * 加锁的 —— 两个不同联盟的官员同时操作同一个国家时，两把玩家锁互不相干。
     * 没有版本号就是后写的整档覆盖先写的（表现是"我的任命没了"，且全链路不报错）；
     * 内存版原先靠整库监视器挡住了同一进程内的这一类，多实例部署时只剩版本号。
     */
    private long version;

    private Nation(String id, String name, String kingId, long capitalX, long capitalY, Rules rules) {
        this.id = id;
        this.name = name;
        this.kingId = kingId;
        this.capitalX = capitalX;
        this.capitalY = capitalY;
        this.rules = rules;
        this.level = 1;
        for (Office office : Office.values()) {
            offices.put(office, new ArrayList<>());
        }
        offices.get(Office.KING).add(kingId);
    }

    /**
     * 建国。
     *
     * <p><b>国王由建国者直接担任</b>（§五 开放问题 3 的裁决）：
     * 联盟间竞选需要一整套投票、任期与罢免机制，那属于 B14 赛季制的范畴。
     * 建国者当国王也符合直觉 —— 他承担的是建国的成本与责任。
     *
     * @param foundingAllianceId 建国者所在的联盟。<b>必须非空</b>：
     *                           B13 §1 的解锁条件之一就是「当前在某联盟中」，
     *                           而没有联盟的人建出来的国家没有任何成员，是个空壳
     */
    public static Nation found(String id, String name, String kingId, String foundingAllianceId,
                               long capitalX, long capitalY, long now, Rules rules) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("国家 id 不得为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("国家名不得为空");
        }
        if (kingId == null || kingId.isBlank()) {
            throw new IllegalArgumentException("国王 id 不得为空");
        }
        if (foundingAllianceId == null || foundingAllianceId.isBlank()) {
            throw new IllegalArgumentException("建国者必须在某个联盟中（B13 §1 的解锁条件之一）："
                    + "没有联盟的人建出来的国家没有任何成员，是个空壳");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        Nation nation = new Nation(id, name, kingId, capitalX, capitalY, rules);
        nation.memberAlliances.put(foundingAllianceId, now);
        return nation;
    }

    /**
     * 一国存档的完整状态 —— "什么算一个完整的国家"的唯一定义。
     *
     * <p><b>为什么必须有它</b>：以前这份字段表只存在于字段声明里，内存存储直接持有实例所以不需要它；
     * 一旦有第二种存储（Mongo），"落哪几列"就变成各人凭印象抄一份，而少抄一个字段的表现不是报错，
     * 而是"重启之后国家少了东西"。本项目已经为同一个形状写过四条记录（城建档、武将、行军、支付订单），
     * 这次赶在第二种存储出现之前先把它提出来。
     *
     * <p><b>{@code holderAlliance} 是最容易漏的一个</b>：它平时没人读，只被 {@link #removeAlliance}
     * 用来"把属于这个联盟的官职一并收回"。不持久化的后果不是当场出错，而是重启之后某个联盟退国或
     * 被开除时<b>收不回它代表们的官职</b> —— 官职是权力位，一个已经不属于本国的玩家继续握着议员席，
     * 还能被 {@code APPOINT_OFFICE} 那条权限链用上。
     *
     * <p><b>刻意不进快照的三样，每一样都有理由</b>：① {@code rules} 是配置注入物，
     * 进快照等于把一次热更参数冻进存档，之后改 {@code nation_config} 对这个国家不生效也不报错；
     * ② {@code allianceLeaderLookup} 是注入的函数，本质不是数据，重建后由外层再 bind；
     * ③ {@code lastLogAt}（{@link #setClock} 注入的当前时刻）与 {@code lastRemovedWasExpulsion}、
     * {@code lastAppointOperator}（"刚才那次操作"的一次性诊断）—— 让它们跨进程复活只会说谎。
     *
     * @param lastTaxWeekKey 最后一次缴税的周键。周税幂等就靠它，丢了就是重复收税
     * @param lastTaxCredited 那次缴税**实际入库**的金额（C16 限额的分母）。丢了本周就没有额度可算
     * @param spendWeekKey    spentThisWeek 所属周键
     * @param spentThisWeek   本周已由非国王身份支取的累计。<b>丢了等于每周白送一份额度</b>，
     *                        而且不报错 —— 限额会在每次重启后悄悄重置
     * @param techLevels     国家科技账本（行 id → 等级）。丢了它的症状不是报错，而是
     *                       「全国的研究白做了一级又一级」——加成会凭空变小，而国库已经扣过了
     * @param disbandedAt     解散时刻；非 0 表示这个国家已不存在（记录留着供审计）
     */
    public record Snapshot(String id, String name, String kingId, long capitalX, long capitalY,
                           int level, long treasury,
                           Map<String, Long> memberAlliances, Map<Office, List<String>> offices,
                           Map<String, Diplomacy> diplomacy, Map<String, Long> joinCooldownUntil,
                           List<TreasuryLog> treasuryLogs, List<String> provinces,
                           Map<String, String> holderAlliance,
                           Map<String, Integer> techLevels,
                           List<PolicyProposal> policyProposals,
                           Map<String, Map<String, Boolean>> policyVotes,
                           Map<String, Long> activePolicies,
                           long policyNextVoteAt, long policyVoteOpenedAt, long policyVoteEndsAt,
                           long lastTaxWeekKey, long lastTaxCredited,
                           long spendWeekKey, long spentThisWeek,
                           long disbandedAt, long version) {
    }

    public Snapshot snapshot() {
        Map<Office, List<String>> officesCopy = new EnumMap<>(Office.class);
        for (Map.Entry<Office, List<String>> entry : offices.entrySet()) {
            officesCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        Map<String, Map<String, Boolean>> votesCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> entry : policyVotes.entrySet()) {
            votesCopy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return new Snapshot(id, name, kingId, capitalX, capitalY, level, treasury,
                new LinkedHashMap<>(memberAlliances), officesCopy,
                new LinkedHashMap<>(diplomacy), new LinkedHashMap<>(joinCooldownUntil),
                List.copyOf(treasuryLogs), List.copyOf(provinces),
                new LinkedHashMap<>(holderAlliance), new LinkedHashMap<>(techLevels),
                List.copyOf(new ArrayList<>(policyProposals.values())), votesCopy,
                new LinkedHashMap<>(activePolicies),
                policyNextVoteAt, policyVoteOpenedAt, policyVoteEndsAt,
                lastTaxWeekKey, lastTaxCredited,
                spendWeekKey, spentThisWeek, disbandedAt, version);
    }

    /**
     * 由快照重建。
     *
     * <p>走私有构造器而不是 {@link #found}：{@code found} 会把等级钉在 1、把官职清空只留国王、
     * 并按"建国"的语义塞入建国联盟 —— 而重建必须能还原任意状态，包括<b>已解散</b>的国家
     * （解散记录要留着供审计，见 {@link #disband}）。
     *
     * @param rules 当前配置下的国家规则。<b>必须由调用方注入</b>：它不进快照（理由见
     *              {@link Snapshot}），没有它就只能得到一个等级上限与国库容量都没定义的空壳
     */
    public static Nation fromSnapshot(Snapshot s, Rules rules) {
        if (s == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null：国家的所有上限与容量判定都来自它");
        }
        Nation nation = new Nation(s.id(), s.name(), s.kingId(), s.capitalX(), s.capitalY(), rules);
        nation.kingId = s.kingId();
        nation.level = s.level();
        nation.treasury = s.treasury();
        nation.memberAlliances.clear();
        nation.memberAlliances.putAll(s.memberAlliances() == null
                ? Map.of() : new LinkedHashMap<>(s.memberAlliances()));
        // 构造器给每个官职建了一个空列表，这里整份换成快照里的席位表（顺序保留：议员按加入先后）
        for (Office office : Office.values()) {
            List<String> holders = s.offices() == null ? null : s.offices().get(office);
            nation.offices.put(office, holders == null ? new ArrayList<>() : new ArrayList<>(holders));
        }
        nation.diplomacy.clear();
        nation.diplomacy.putAll(s.diplomacy() == null ? Map.of() : new LinkedHashMap<>(s.diplomacy()));
        nation.joinCooldownUntil.clear();
        nation.joinCooldownUntil.putAll(s.joinCooldownUntil() == null
                ? Map.of() : new LinkedHashMap<>(s.joinCooldownUntil()));
        nation.holderAlliance.clear();
        nation.holderAlliance.putAll(s.holderAlliance() == null
                ? Map.of() : new LinkedHashMap<>(s.holderAlliance()));
        // 缺这一位 = B20 块③ 之前的老文档，读成「什么都没研究过」（与上面几张表同一条宽容读法：
        // 这一位读不懂的代价是少一档加成，而不是这个国家打不开）。
        // 但**等级必须为正**：脏数据里的 0 或负数不进账本，否则 levelOf 与「研究过几行」都会被它污染
        nation.techLevels.clear();
        if (s.techLevels() != null) {
            s.techLevels().forEach((techId, level) -> {
                if (techId != null && !techId.isBlank() && level != null && level > 0) {
                    nation.techLevels.put(techId, level);
                }
            });
        }
        nation.treasuryLogs.clear();
        if (s.treasuryLogs() != null) {
            nation.treasuryLogs.addAll(s.treasuryLogs());
        }
        nation.provinces.clear();
        if (s.provinces() != null) {
            nation.provinces.addAll(s.provinces());
        }
        // 国策四个字段是 2026-09-30 加的，老文档读不到它们 ⇒ 一律当「本轮什么都没有」。
        // 宽容读法的代价只是「这个国家的国策重新从提案段开始」，而不是它打不开；
        // 与上面 techLevels 那条同一条读法。注意 policyVoteEndsAt 读到 0 意味着「窗口没开」，
        // 恰好是安全的一侧（不会凭空开一个已经过期的窗口）。
        nation.policyProposals.clear();
        if (s.policyProposals() != null) {
            for (PolicyProposal proposal : s.policyProposals()) {
                if (proposal != null) {
                    nation.policyProposals.put(proposal.id(), proposal);
                }
            }
        }
        nation.policyVotes.clear();
        if (s.policyVotes() != null) {
            s.policyVotes().forEach((proposalId, votes) -> {
                if (proposalId != null && votes != null && !votes.isEmpty()) {
                    nation.policyVotes.put(proposalId, new LinkedHashMap<>(votes));
                }
            });
        }
        nation.activePolicies.clear();
        if (s.activePolicies() != null) {
            s.activePolicies().forEach((policyId, expiresAt) -> {
                if (policyId != null && !policyId.isBlank() && expiresAt != null && expiresAt > 0L) {
                    nation.activePolicies.put(policyId, expiresAt);
                }
            });
        }
        nation.policyNextVoteAt = s.policyNextVoteAt();
        nation.policyVoteOpenedAt = s.policyVoteOpenedAt();
        nation.policyVoteEndsAt = s.policyVoteEndsAt();
        nation.lastTaxWeekKey = s.lastTaxWeekKey();
        nation.lastTaxCredited = s.lastTaxCredited();
        nation.spendWeekKey = s.spendWeekKey();
        nation.spentThisWeek = s.spentThisWeek();
        nation.disbandedAt = s.disbandedAt();
        nation.version = s.version();
        return nation;
    }

    /** 乐观锁版本。调用方读到时记下，写回时原样带回来。 */
    public long version() {
        return version;
    }

    /**
     * 持久化成功后由仓储调用。<b>业务代码不要调它</b>：版本表示的是"库里第几版"，
     * 在库里加一次才算数；在内存里先加，等于让一次没成功的写入也占了版本号。
     */
    public void incrementVersion() {
        version++;
    }

    /** 建国前置校验（主城等级 + 开服天数 + 在联盟中 + 冷却）。 */
    public static String checkUnlock(long mainCityLevel, long dayOffset, boolean inAlliance,
                                     long cooldownUntil, long now, Rules rules) {
        if (mainCityLevel < rules.unlockMainLevel()) {
            return "需要主城 " + rules.unlockMainLevel() + " 级，当前 " + mainCityLevel + " 级";
        }
        if (dayOffset < rules.unlockDayOffset()) {
            return "需要开服第 " + (rules.unlockDayOffset() + 1) + " 天，当前第 " + (dayOffset + 1) + " 天";
        }
        if (!inAlliance) {
            return "需要先加入一个联盟：国家由联盟整体加入，个人不单独入籍";
        }
        if (now < cooldownUntil) {
            return "入籍冷却中，还需 " + ((cooldownUntil - now + 999L) / 1000L) + " 秒";
        }
        return null;
    }

    // ---------- 成员联盟 ----------

    /**
     * 一次入籍请求被拒的原因。web 层按它映射错误码（冷却要显示倒计时、名额满了要引导换一家），
     * 而<b>判定本身只长在 {@link #admitBlockFor} 一处</b>。
     *
     * <p>为什么不让调用方用 {@link #joinCooldownUntil} 与 {@link #maxAllianceCount()} 自己拼判定：
     * 那是把同一条规则抄第二份，两份早晚改得不一样，症状是"提示说能加入、服务端却拒绝"
     * —— 与本类一直在防的「一个数字只能有一个家」是同一条铁律。
     */
    public enum AdmitRejection {
        /** 处于入籍冷却期（上一次退出国或被开除之后，验收 2）。 */
        COOLDOWN,
        /** 该联盟已经是本国成员。 */
        ALREADY_MEMBER,
        /** 本国可容纳的联盟数已满（上限随国家等级变化）。 */
        FULL
    }

    /** 一次入籍被拒的完整答案：原因供映射错误码，话由本类算，两者同源。 */
    public record AdmitBlock(AdmitRejection reason, String message) {}

    /**
     * 现在能不能收下这个联盟。<b>{@link #admitAlliance} 用的就是这一个判定</b>。
     *
     * <p>国家已解散时本方法不表态（那是 {@link #requireActive()} 的职责，它在 admitAlliance 里先跑），
     * 所以「亡国了还能不能入籍」不会在这里被静默回答成"能"。
     *
     * @return empty 表示可以入籍
     * @throws IllegalArgumentException allianceId 为空
     */
    public java.util.Optional<AdmitBlock> admitBlockFor(String allianceId, long now) {
        if (allianceId == null || allianceId.isBlank()) {
            throw new IllegalArgumentException("allianceId 不得为空");
        }
        long cooldownUntil = joinCooldownUntil.getOrDefault(allianceId, 0L);
        if (now < cooldownUntil) {
            return java.util.Optional.of(new AdmitBlock(AdmitRejection.COOLDOWN,
                    "该联盟处于入籍冷却期，还需 " + ((cooldownUntil - now + 999L) / 1000L) + " 秒（B13 验收 2）"));
        }
        if (memberAlliances.containsKey(allianceId)) {
            return java.util.Optional.of(new AdmitBlock(AdmitRejection.ALREADY_MEMBER, "该联盟已经是本国成员"));
        }
        if (memberAlliances.size() >= maxAllianceCount()) {
            return java.util.Optional.of(new AdmitBlock(AdmitRejection.FULL,
                    "国家可容纳的联盟数已满（上限 " + maxAllianceCount() + " 个）"));
        }
        return java.util.Optional.empty();
    }

    /**
     * 一个联盟整体加入国家。
     *
     * @throws IllegalStateException 冷却期内（验收 2）、已达联盟数上限、或已经加入了别的国家
     */
    public void admitAlliance(String allianceId, long now) {
        requireActive();
        admitBlockFor(allianceId, now).ifPresent(block -> {
            throw new IllegalStateException(block.message());
        });
        memberAlliances.put(allianceId, now);
        // 盟主自动获得议员席（§2：议员每盟主 1 席）
        representativesChanged();
    }

    /**
     * 一个联盟退出（或被开除）国家。
     *
     * <p><b>全联盟成员失去国籍 + 24h 入籍冷却</b>（验收 2）。
     * 因为成员表键是联盟，所以「全联盟失去国籍」就是删掉这一个键 ——
     * 这正是把键设计成 allianceId 的好处：不需要遍历几百个玩家逐个处理，
     * 也就不存在「漏掉某个人」的可能。
     *
     * <p><b>最后一个成员联盟走掉时，国家当场算亡</b>（2026-09-13 裁决）。这条不变量刻意长在
     * 本方法里而不是让调用方各判一次 {@code memberAllianceCount() == 0}：漏一次不会报错，
     * 只会留下一个零成员的活国，而「这个国还算不算存在」的判断散在名额计数、外交面板、
     * 入籍目标、按国王找国四处（见 {@code NationAppService} 类注释），
     * 每一处都会为一个并不存在的国家给出肯定答案。
     *
     * @param expelled true 表示被国家开除，false 表示主动退出。两者都触发冷却：
     *                 主动退出若无冷却，就可以「退出国 → 立刻加入敌国」，
     *                 而国战的胜负恰恰取决于双方人数
     * @param actorId  造成这次离开的<b>人</b>（主动退出是那位盟主，开除是下令的国王）。
     *                 只在触发亡国时用到：国库余额核销要留操作者，而自动亡国时没有国王下令这件事，
     *                 拿 {@code kingId} 冒充等于在账本上伪造一笔从未发生过的决定
     */
    public void removeAlliance(String allianceId, boolean expelled, String actorId, long now) {
        requireActive();
        if (memberAlliances.remove(allianceId) == null) {
            throw new IllegalStateException("该联盟不是本国成员");
        }
        joinCooldownUntil.put(allianceId, now + rules.joinCooldownMillis());
        // 该联盟成员持有的官职一律收回：一个不在国里的联盟不该继续行使国家权力
        for (Map.Entry<Office, List<String>> entry : offices.entrySet()) {
            entry.getValue().removeIf(holder -> allianceId.equals(holderAlliance.get(holder)));
        }
        representativesChanged();
        lastRemovedWasExpulsion = expelled;
        if (memberAlliances.isEmpty()) {
            // 冷却上面已经给这一个写过了，这里不再重复写（tearDown 遍历的是剩余成员，此时为空）
            tearDown(actorId, now, "collapse_writeoff",
                    "最后一个成员联盟「" + allianceId + "」离开后国家无人存续，国库余额核销（无人收到这笔钱）");
        }
    }

    /** 官职持有者 → 所属联盟。任命与除名时都要维护，否则退盟收不回官职。 */
    private final Map<String, String> holderAlliance = new LinkedHashMap<>();
    private boolean lastRemovedWasExpulsion;

    /** 上一次 removeAlliance 是否为「被开除」。供日志与埋点区分两种离开方式。 */
    public boolean lastRemovalWasExpulsion() {
        return lastRemovedWasExpulsion;
    }

    /** 议员席位随成员联盟数变化（§2：每盟主 1 席）。 */
    private void representativesChanged() {
        List<String> representatives = offices.get(Office.REPRESENTATIVE);
        representatives.clear();
        for (String allianceId : memberAlliances.keySet()) {
            String leader = allianceLeaderOf(allianceId);
            if (leader != null && !representatives.contains(leader)) {
                representatives.add(leader);
            }
        }
    }

    /** 盟主查询回调。国家不持有联盟对象（分层），所以由外层注入。 */
    private java.util.function.Function<String, String> allianceLeaderLookup = allianceId -> null;

    /** 注入盟主查询。缺它的话议员席位永远为空。 */
    public void bindAllianceLeaderLookup(java.util.function.Function<String, String> lookup) {
        if (lookup == null) {
            throw new IllegalArgumentException("lookup 不得为 null");
        }
        this.allianceLeaderLookup = lookup;
        representativesChanged();
    }

    private String allianceLeaderOf(String allianceId) {
        return allianceLeaderLookup.apply(allianceId);
    }

    /** 国家可容纳的联盟数。人数上限 ÷ 单个联盟的最大容量，至少 2 个。 */
    public int maxAllianceCount() {
        long cap = currentLevelRule().memberCap();
        // 单个联盟的容量上限来自 alliance_config 的最高档（150）。
        // 国家不持有联盟规则（分层），所以这里用「人数上限 ÷ 100」的保守估计：
        // 200 人 ⇒ 2 盟、400 ⇒ 4 盟、800 ⇒ 8 盟，与 B13 §1 的括号说明完全一致
        return (int) Math.max(2L, cap / 100L);
    }

    // ---------- 官职（§2、验收 3、合规红线） ----------

    /**
     * 任命官职。
     *
     * <p><b>Bot 不得担任任何国家官职</b>（§2 合规红线、B11 §七）。
     * 这条判定不在本类里做 —— 本类不知道谁是 Bot（那是 B11 的 BotExtra，
     * 而且 isBot 绝不能渗进游戏逻辑）。调用方必须在任命前调
     * {@code BotTuning.mayHoldOffice("NATION", isLeader, isOffice)}，
     * 而 {@code scripts/check-no-bot-privilege.sh} 会检查那三个方法是否存在。
     *
     * @param operatorId  操作者。能不能任命由 role_permission 的 NATION/APPOINT_OFFICE 裁决，
     *                    本类只做席位与身份的机械校验
     */
    public void appoint(String operatorId, String playerId, String allianceId, Office office) {
        requireActive();
        if (office == null) {
            throw new IllegalArgumentException("官职不得为 null");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("被任命者 id 不得为空");
        }
        if (allianceId == null || !memberAlliances.containsKey(allianceId)) {
            throw new IllegalStateException("被任命者的联盟必须在本国内：国家官职是国家权力的行使，"
                    + "让一个不在国里的人担任等于把权力交给了外国人");
        }
        List<String> holders = offices.get(office);
        if (office == Office.KING) {
            throw new IllegalStateException("国王只能通过转让产生，不能任命");
        }
        if (office == Office.REPRESENTATIVE) {
            throw new IllegalStateException("议员席位随盟主身份自动产生，不能手动任命");
        }
        int seats = office.seatCount();
        if (holders.size() >= seats) {
            throw new IllegalStateException("官职 " + office + " 的席位已满（" + seats + " 席）");
        }
        // 一个人不能同时占两个固定官职：席位是稀缺资源，兼任会让「12 席」变成实际 6 个人
        for (Map.Entry<Office, List<String>> entry : offices.entrySet()) {
            if (entry.getKey() != Office.REPRESENTATIVE && entry.getValue().contains(playerId)) {
                throw new IllegalStateException("该玩家已担任 " + entry.getKey() + "，不能兼任 " + office
                        + "。兼任会让席位总数形同虚设");
            }
        }
        holders.add(playerId);
        holderAlliance.put(playerId, allianceId);
        lastAppointOperator = operatorId;
    }

    private String lastAppointOperator;

    /** 最近一次任命的操作者。供日志与埋点使用。 */
    public String lastAppointOperator() {
        return lastAppointOperator;
    }

    /** 罢免。 */
    public void dismiss(String playerId, Office office) {
        requireActive();
        List<String> holders = offices.get(office);
        if (holders == null || !holders.remove(playerId)) {
            throw new IllegalStateException("该玩家未担任 " + office);
        }
        holderAlliance.remove(playerId);
    }

    /** 转让王位。 */
    public void abdicate(String currentKingId, String newKingId) {
        requireActive();
        if (!currentKingId.equals(kingId)) {
            throw new IllegalStateException("只有国王能转让王位");
        }
        if (newKingId.equals(kingId)) {
            throw new IllegalStateException("不能把王位转让给自己");
        }
        List<String> kings = offices.get(Office.KING);
        kings.clear();
        kings.add(newKingId);
        kingId = newKingId;
    }

    /** 某个官职的持有者。 */
    public List<String> holdersOf(Office office) {
        return Collections.unmodifiableList(offices.getOrDefault(office, List.of()));
    }

    /** 某玩家持有的官职；无官职为 null。 */
    public Office officeOf(String playerId) {
        for (Map.Entry<Office, List<String>> entry : offices.entrySet()) {
            if (entry.getValue().contains(playerId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---------- 国库（§3、验收 5） ----------

    /**
     * 收税：每个成员联盟每周上缴一次。
     *
     * @param weekKey 周键（与 DayKey 同一思路，由外层按服务端时间算）。
     *                同一个 weekKey 重复调用不会重复收税 —— 否则一次重放就能把国库刷满
     * @param now     本次结算的时刻（铁律 5：本类不读时钟，由调用方注入）。它记进日志的 {@code at}
     * @return 本次实际入账的金额（国库满了会被截断，照实返回）
     */
    public long collectTax(long weekKey, long now) {
        requireActive();
        if (weekKey <= lastTaxWeekKey) {
            return 0L;
        }
        lastTaxWeekKey = weekKey;
        long income = rules.taxWeeklyPerAlliance() * memberAlliances.size();
        long room = Math.max(0L, treasuryCap() - treasury);
        long credited = Math.min(income, room);
        treasury += credited;
        // 限额的分母记的是**实收**：国库满的时候 income 与 credited 不相等，
        // 记 income 等于允许官员把没收到的钱花掉
        lastTaxCredited = credited;
        lastLogAt = now;
        // 周税也要有审计行：它是国库最大的一笔常规变动，
        // 没有这一行，日志最后一列的 balanceAfter 就再也对不上国库余额（而这张账本的存在理由就是防贪污）。
        // 满了导致 credited 为 0 时也照记：那一行说的是"本周应收这么多、实收 0，因为国库满了"
        appendLog(new TreasuryLog(now, OPERATOR_SYSTEM, "weekly_tax", credited,
                "国库周税（第 " + weekKey + " 周 × " + memberAlliances.size() + " 个成员联盟）",
                treasury));
        return credited;
    }

    /**
     * 国库支出。<b>每一笔都留日志</b>（验收 5）。
     *
     * <p><b>落点是两类之一，不是自由字符串</b>（2026-09-11 裁决）：发给某个玩家（俸禄），
     * 或由某个消耗性用途核销。类型上就只有这两条路，所以「支给谁」写不出含糊的东西 ——
     * 见 {@link Payee}。
     *
     * <p><b>本方法只改状态、不碰资源</b>：给玩家的那一笔由应用层在扣账之后走发放器发出去
     * （容量、保护量、装不下的转邮件都在发放器里）。聚合负责记账，发放负责到账，两者分工与
     * 「取消训练返还多少」同一条。
     *
     * <p><b>国王不受限额，其余任何身份共用一个全国周额度</b>（2026-09-13 裁决 C16）：
     * 上限 = 本周<b>实收入库</b>的周税 × {@code Rules.officerSpendRatioFixed}。
     * 之所以是「一个池子」而不是「每人一份」：四个官职各自花满的话，实际敞口就是比例 × 4，
     * 而「限额」两个字的意思就没了。
     *
     * <p><b>为什么 {@code weekKey} 由调用方给而不是本类读时钟</b>：与 {@link #collectTax} 同一条理由 ——
     * 领域层不持有时钟，跨周重置才可被测试精确复现。传进来的必须是与周税同一个口径的周键，
     * 否则会出现「税按 A 周结、额度按 B 周算」。
     *
     * @param payee   落点（不得为 null，见 {@link Payee}）
     * @param reason  用途。不得为空
     * @param weekKey 本次支出所属的周键（{@code WeekKey.number(now)}）
     * @return 支出后的余额
     * @throws IllegalStateException 国库不足，或非国王身份超出本周限额
     */
    public long spend(String operatorId, Payee payee, long amount, String reason, long weekKey) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("支出额必须为正，实际=" + amount);
        }
        if (payee == null) {
            throw new IllegalArgumentException("国库支出必须写明支给谁：没有 payee 的日志无法追责");
        }
        // 两道拒绝读的是 spendBlock —— 与科技面板的"这一行点不点得动"同一个判定（判定只有一处）。
        // 顺序也保持原样：先限额后余额，因为非国王身份超限时那句提示才是要紧话
        TechBlock blocked = spendBlock(operatorId, amount, weekKey);
        if (blocked == TechBlock.OFFICER_WEEKLY_LIMIT) {
            long cap = officerWeeklySpendCap(weekKey);
            long already = weekKey == spendWeekKey ? spentThisWeek : 0L;
            throw new OfficerSpendLimitException("本周国库支出超出限额：上限 " + cap
                    + "（本周实收入账 " + (weekKey == lastTaxWeekKey ? lastTaxCredited : 0L)
                    + " × 比例），本周已支取 " + already + "，本次要支取 " + amount
                    + "。国王的支取不受此限（B13 §2）");
        }
        if (blocked == TechBlock.TREASURY_LOW) {
            throw new IllegalStateException("国库资金不足：需要 " + amount + "，当前 " + treasury);
        }
        if (!operatorId.equals(kingId)) {
            if (weekKey != spendWeekKey) {
                // 跨周的第一笔支出先把额度清零，再记上自己（与 collectTax 的周键同一条读法）
                spendWeekKey = weekKey;
                spentThisWeek = 0L;
            }
            spentThisWeek += amount;
        }
        treasury -= amount;
        appendLog(new TreasuryLog(lastLogAt, operatorId, payee.text(), amount, reason, treasury));
        return treasury;
    }

    /**
     * 非国王身份在 {@code weekKey} 这一周还能被允许支取的总额上限（本周累计，不是单笔）。
     *
     * <p>公开它是为了让国库面板与错误文案共用同一个数 —— 两处各算一遍的话，
     * 症状是「面板显示还能花 500，点下去说超限」。
     */
    public long officerWeeklySpendCap(long weekKey) {
        // 只认**本周**的入库额：上周收了多少与本周能花多少无关
        long credit = weekKey == lastTaxWeekKey ? lastTaxCredited : 0L;
        return FixedPoint.truncate(FixedPoint.mul(FixedPoint.of(credit), rules.officerSpendRatioFixed()));
    }

    /**
     * 「非国王身份超出本周国库限额」这个失败单独成一个类型。
     *
     * <p>理由不是分类癖好，而是 web 层要把它的错误码与「国库余额不足」分开（两者的下一步动作不同），
     * 而<b>靠比对异常文案来分</b>意味着改一句提示就会悄悄把两个码混回去 —— 那种 bug 不报错。
     *
     * <p>继承 {@link IllegalStateException} 是为了不打挂所有既有的 {@code catch (IllegalStateException)}。
     */
    public static final class OfficerSpendLimitException extends IllegalStateException {
        OfficerSpendLimitException(String message) {
            super(message);
        }
    }

    // ---------- B20 块③：国家科技 ----------

    /**
     * @param techId         研究了哪一行
     * @param level          研究完的等级
     * @param cost           本次从国库扣掉多少
     * @param treasuryAfter  扣完的国库余额（面板要的就是这个数，不再回读一次）
     */
    public record TechResearch(String techId, int level, long cost, long treasuryAfter) {
    }

    /** 某一行的当前等级。<b>缺失即 0</b>：这条读法是唯一入口，账本里不给 0 占位留位置。 */
    public int techLevel(String techId) {
        Integer level = techLevels.get(techId);
        return level == null ? 0 : level;
    }

    /** 整本国家科技账本（只读视图）。折算成加成由 web 侧那一个读取口负责，本类不碰效果表。 */
    public Map<String, Integer> techLevels() {
        return Collections.unmodifiableMap(techLevels);
    }

    /** 研究一级国家科技被什么挡住。{@link #techBlock} 与 {@link #researchTech} <b>共用</b>这一份判定。 */
    public enum TechBlock {
        /** 国家等级不足（表列 {@code requireNationLevel}）。 */
        NATION_LEVEL_LOW,
        /** 这一行已到表给的上限。 */
        MAX_LEVEL,
        /** 国库余额不够这一笔。 */
        TREASURY_LOW,
        /** 非国王身份超出本周国库限额（C16）。 */
        OFFICER_WEEKLY_LIMIT
    }

    /**
     * 这一行现在能不能研究。<b>返回 null 表示没有拦着</b>（不返回一个 "NONE" 枚举值，
     * 因为调用方要的是"要不要报"而不是"报哪一号"，判空比判枚举更不容易漏）。
     *
     * <p><b>为什么单独把判定抽出来而不是只写在 {@link #researchTech} 里</b>：面板上每一行都要显示
     * "点得动还是点不动、为什么"。判定若只写在变更里，视图就得抄一遍同样的比较 ——
     * 两份判定的分叉不报错，症状是"按钮亮着却按失败"或反过来。
     *
     * <p><b>权限位不在这里判</b>：那是 {@code role_permission} 表与社交规则的事，国家聚合不知道"谁在问"，
     *
     * @param operatorId         谁在研究（决定走不走周限额，与 {@link #spend} 同一条判定）
     * @param weekKey            与周税同源的周键
     * @param currentLevel       这一行的当前等级（{@link #techLevel(String)}）
     * @param tableMaxLevel      表里的等级上限
     * @param requireNationLevel 表里的国家等级前置
     * @param cost               下一级要花多少国库（由调用方按曲线算，领域层不读配置表）
     */
    public TechBlock techBlock(String operatorId, long weekKey, int currentLevel, int tableMaxLevel,
                               int requireNationLevel, long cost) {
        if (currentLevel >= tableMaxLevel) {
            return TechBlock.MAX_LEVEL;
        }
        if (level < requireNationLevel) {
            return TechBlock.NATION_LEVEL_LOW;
        }
        return spendBlock(operatorId, cost, weekKey);
    }

    /**
     * 这一笔国库支出会被哪一位挡住（{@code null} = 挡不住）。
     *
     * <p>公开度上收在私有 + 只被 {@link #spend} 与 {@link #techBlock} 读：两处各自比较一遍的话，
     * 分叉的症状是"面板说花得起、点下去说超限"，而这两句话用的其实是同一个额度。
     */
    private TechBlock spendBlock(String operatorId, long amount, long weekKey) {
        if (operatorId != null && !operatorId.equals(kingId)) {
            long already = weekKey == spendWeekKey ? spentThisWeek : 0L;
            if (already + amount > officerWeeklySpendCap(weekKey)) {
                return TechBlock.OFFICER_WEEKLY_LIMIT;
            }
        }
        return treasury < amount ? TechBlock.TREASURY_LOW : null;
    }

    /**
     * 研究一级国家科技被挡住。<b>带类型而不是靠比对文案</b>：{@code OfficerSpendLimitException}
     * 的类注释已经写过同一条理由 —— 靠 message 区分错误码，改一句提示就会把两个码悄悄混回去。
     */
    public static final class TechResearchException extends IllegalStateException {
        private final TechBlock block;

        TechResearchException(TechBlock block, String message) {
            super(message);
            this.block = block;
        }

        public TechBlock block() {
            return block;
        }
    }

    /**
     * 研究一级国家科技：先过国家等级与上限，再花国库，最后抬等级。
     *
     * <p><b>为什么 {@code synchronized}</b>：应用层的锁是按<b>玩家</b>加的，而国库与这本账是按<b>国家</b>
     * 共享的资产。两个官员同时点研究时各持自己的玩家锁、彼此不互斥，"查余额 → 扣款 → 抬等级"
     * 就会被穿过：轻则同一级收两次钱，重则两笔都按同一个起点等级计价（后一笔看不见前一笔）。
     * 与 {@code Alliance#researchTech} 与周税（收口清单 #25）同一条先例：<b>检查与变更进同一个监视器</b>。
     * 这条保护只在单实例内成立，跨实例要靠仓储的乐观锁（{@code nations.save(nation, version)}）挡下第二个写者。
     *
     * <p><b>钱走 {@link #spend}，不在这里自己扣</b>：那一条路带着 C16 的官员周限额与
     * {@code sink:NATIONAL_TECH} 的核销日志（验收 5 要的就是那句）。在这里另写一遍扣款，
     * 症状是"研究科技绕过了国库周限额"——那是一条能无限支出国库的通道。
     *
     * @param cost               本次花费（由调用方按曲线与基数算好——领域层不读配置表）
     * @param tableMaxLevel      表里的等级上限
     * @param requireNationLevel 表里的国家等级前置
     * @param operatorId         操作者（决定走不走周限额，与 {@link #spend} 同一条判定）
     * @param weekKey            与周税同源的周键
     * @throws IllegalArgumentException 参数本身不合法（空 id、非正花费、非正上限）
     * @throws TechResearchException    被 {@link TechBlock} 四种之一挡住
     */
    public synchronized TechResearch researchTech(String techId, long cost, int tableMaxLevel,
                                                  int requireNationLevel, String operatorId, long weekKey) {
        requireActive();
        if (techId == null || techId.isBlank()) {
            throw new IllegalArgumentException("techId 不得为空：它是国家科技账本的唯一键");
        }
        if (cost <= 0L) {
            throw new IllegalArgumentException("研究花费必须为正，实际=" + cost
                    + "（免费的国家科技不是沉没口，是数值没配出来）");
        }
        if (tableMaxLevel < 1 || requireNationLevel < 1) {
            throw new IllegalArgumentException("表给的上限与前置必须为正：maxLevel=" + tableMaxLevel
                    + " requireNationLevel=" + requireNationLevel);
        }
        int current = techLevel(techId);
        TechBlock blocked = techBlock(operatorId, weekKey, current, tableMaxLevel, requireNationLevel, cost);
        if (blocked == TechBlock.MAX_LEVEL) {
            throw new TechResearchException(blocked, "科技「" + techId + "」已满级 " + tableMaxLevel
                    + " 级（本机制不随国家等级放大上限，与联盟科技那条不同）");
        }
        if (blocked == TechBlock.NATION_LEVEL_LOW) {
            throw new TechResearchException(blocked, "国家等级不足：研究「" + techId + "」要 "
                    + requireNationLevel + " 级国家，当前 " + level + " 级");
        }
        if (blocked != null) {
            // 国库不足或本周超限：细节由 spend 那一侧写进消息（同一条判定的同一句话），这里只做转译
            throw new TechResearchException(blocked, blocked == TechBlock.TREASURY_LOW
                    ? "国库资金不足：研究「" + techId + "」需要 " + cost + "，当前 " + treasury
                    : "本周国库支出已达限额：研究「" + techId + "」要 " + cost + "，本周已支取 "
                            + (weekKey == spendWeekKey ? spentThisWeek : 0L));
        }
        long treasuryAfter;
        try {
            treasuryAfter = spend(operatorId, Payee.toSink(Payee.Sink.NATIONAL_TECH), cost,
                    "研究国家科技 " + techId, weekKey);
        } catch (OfficerSpendLimitException e) {
            throw new TechResearchException(TechBlock.OFFICER_WEEKLY_LIMIT, e.getMessage());
        } catch (IllegalStateException e) {
            // spend 里剩的那一种失败就是"国库余额不足"（其余失败都是参数异常，已在上面挡掉）
            throw new TechResearchException(TechBlock.TREASURY_LOW, e.getMessage());
        }
        // 扣款成功之后才抬等级：反过来（先抬等级再扣款）在扣款失败时会留下一档没收钱的等级
        int target = current + 1;
        techLevels.put(techId, target);
        return new TechResearch(techId, target, cost, treasuryAfter);
    }

    /** 国库入账（战利品、活动奖励等外部来源）。同样留日志。 */
    public long deposit(String operatorId, String from, long amount, String reason, long now) {
        requireActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("入账额必须为正，实际=" + amount);
        }
        long room = Math.max(0L, treasuryCap() - treasury);
        long credited = Math.min(amount, room);
        treasury += credited;
        lastLogAt = now;
        // amount 记的是这一笔的规模（正数），方向由 payee 是"来源"还是"支给谁"决定，
        // 与 spend 那侧同一条约定（原先这里写 -credited，等于同一张账本里两列符号含义相反，
        // 而把日志金额加总就会得出"入账把钱抽走"的结论 —— 这张账本的存在理由恰恰是防贪污）
        appendLog(new TreasuryLog(now, operatorId, from, credited, reason, treasury));
        return credited;
    }

    /** spend 用的时刻。由 {@link #setClock} 注入，避免本类自己读时钟（铁律 5）。 */
    private long lastLogAt;

    /** 注入当前时刻。所有会写日志的操作都应当先调它。 */
    public void setClock(long now) {
        this.lastLogAt = now;
    }

    private void appendLog(TreasuryLog log) {
        treasuryLogs.add(log);
        // 超出保留条数就丢最旧的：日志必须有上限，否则一个活跃大国的日志会涨到几万条，
        // 而查询国库面板时要全量载入。更早的归档到赛季记录（B14）
        while (treasuryLogs.size() > rules.treasuryLogRetention()) {
            treasuryLogs.remove(0);
        }
    }

    /** 国库日志（按时间升序）。 */
    public List<TreasuryLog> treasuryLogs() {
        return Collections.unmodifiableList(new ArrayList<>(treasuryLogs));
    }

    // ---------- 外交（§5、验收 12） ----------

    /** 设置与某个国家的外交关系。 */
    public void setDiplomacy(String targetNationId, Diplomacy relation) {
        requireActive();
        if (targetNationId == null || targetNationId.isBlank()) {
            throw new IllegalArgumentException("targetNationId 不得为空");
        }
        if (targetNationId.equals(id)) {
            throw new IllegalArgumentException("不能与自己建立外交关系");
        }
        if (relation == null) {
            throw new IllegalArgumentException("relation 不得为 null");
        }
        if (relation == Diplomacy.NEUTRAL) {
            diplomacy.remove(targetNationId);
            return;
        }
        diplomacy.put(targetNationId, relation);
    }

    /** 与某个国家的外交关系。未登记为中立。 */
    public Diplomacy diplomacyWith(String targetNationId) {
        return diplomacy.getOrDefault(targetNationId, Diplomacy.NEUTRAL);
    }

    /**
     * 本国能否攻击某个联盟所属的国家（冲突规则 4：外交关系优先于私人关系）。
     *
     * <p><b>本方法不接受任何「私人关系」参数</b> —— 小队队友、好友、师徒都不在签名里。
     * 这是刻意的：把私人关系放进参数，就等于承认它可以参与判定，
     * 而 B13 禁止项明写「不要让小队私人关系凌驾于国家外交关系之上」。
     * 参数表里没有的东西，调用方就没法用它做判断。
     */
    public boolean mayAttackNation(String targetNationId) {
        Diplomacy relation = diplomacyWith(targetNationId);
        return switch (relation) {
            case ALLIED -> false;      // 盟约不可互攻
            case TRIBUTARY -> false;   // C22：朝贡也是双向禁攻，宗主与藩属都打不动对方
            case HOSTILE -> true;
            case NEUTRAL -> true;      // 中立可被宣战（宣战后转为敌对）
        };
    }

    /**
     * 两个国家之间能不能<b>互相</b>攻击。
     *
     * <p><b>2026-09-13 裁决 C21：条约要双方各自宣布才成立。</b>此前一侧单方面宣布盟约就
     * 约束两侧，实际效果是"弱势方给自己挂了一块免战牌" —— 与 C00 公理一
     * 「不给弱者自动补偿」直接冲突：打不动他这件事不是他争取来的，是他声明出来的。
     * 现在 {@code ALLIED} / {@link Diplomacy#TRIBUTARY} 必须<b>两侧都记着同一个关系</b>才算数。
     *
     * <p>这也意味着<b>不需要一个新的 accept 端点</b>：对方国王对外交部说一次同样的话就是接受。
     * 裁决当时预估的"要加一个 accept 动作与端点"是没有看到这个对称性时的估计，
     * 而多一个只服务于这一件事的端点，就是多一条要单独维护的权限与幂等路径。
     *
     * <p><b>为什么它是领域层的一个方法而不是各调用点自己写判定</b>：这条规则写在调用点上，
     * 第二条攻击路径（集结、掠袭、将来的国战）漏抄一次的表现不是报错，
     * 而是"某一侧的单边宣布仍然挡住了他，却挡不住反向的那一支"。
     *
     * <p>单边视角仍然留在 {@link #mayAttackNation} 里：面板要显示"我怎么看他"，
     * 那一句不能被这个成对判定替代。
     */
    public static boolean mayAttackEachOther(Nation from, Nation to) {
        if (from == null || to == null) {
            // 有一侧根本不存在时不由本方法裁决：返回放行会把"查不到"偷偷变成"没有外交约束"
            throw new IllegalArgumentException("mayAttackEachOther 需要两侧都存在");
        }
        return !treatyInForce(from, to);
    }

    /** 条约是否已经成立（两侧记着同一个条约关系）。 */
    public static boolean treatyInForce(Nation a, Nation b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("treatyInForce 需要两侧都存在");
        }
        Diplomacy mine = a.diplomacyWith(b.id());
        return isTreaty(mine) && mine == b.diplomacyWith(a.id());
    }

    /**
     * 这一档关系算不算"条约"（会约束谁能打谁）。唯一的一份定义 ——
     * web 层的文案与闸门以前各写了一遍 {@code ALLIED || TRIBUTARY}，那就是两个家。
     */
    public static boolean isTreaty(Diplomacy relation) {
        return relation == Diplomacy.ALLIED || relation == Diplomacy.TRIBUTARY;
    }

    // ---------- 领土（§6） ----------

    /** 占领一处行省。领土提供全成员产出加成。 */
    public void annexProvince(String provinceId) {
        requireActive();
        if (provinceId == null || provinceId.isBlank()) {
            throw new IllegalArgumentException("provinceId 不得为空");
        }
        if (!provinces.contains(provinceId)) {
            provinces.add(provinceId);
        }
    }

    public List<String> provinces() {
        return Collections.unmodifiableList(new ArrayList<>(provinces));
    }

    /** 迁都。 */
    public void moveCapital(long x, long y) {
        requireActive();
        this.capitalX = x;
        this.capitalY = y;
    }

    // ---------- 解散 ----------

    /** 解散国家。所有成员联盟进入入籍冷却（与退出同一条规则）。 */
    public void disband(String operatorId, long now) {
        requireActive();
        if (!operatorId.equals(kingId)) {
            throw new IllegalStateException("只有国王能解散国家");
        }
        tearDown(operatorId, now, "disband_writeoff", "国家解散，国库余额核销（无人收到这笔钱）");
    }

    /**
     * 亡国的拆解动作 —— 国王主动解散与「最后一个成员联盟离开」的自动算亡<b>共用这一份</b>。
     *
     * <p>两者要的结果完全一致（成员进冷却、官职清空、余额核销、盖上 {@code disbandedAt}），
     * 只有核销日志上的用途与文案不同：那两样是审计要区分的东西，不是两套流程。
     * 分成两个方法各写一遍的代价是将来只改得动一处，症状是「自动亡的国把钱静默吞了」。
     */
    private void tearDown(String operatorId, long now, String writeOffPayee, String writeOffReason) {
        for (String allianceId : new LinkedHashSet<>(memberAlliances.keySet())) {
            joinCooldownUntil.put(allianceId, now + rules.joinCooldownMillis());
        }
        memberAlliances.clear();
        for (List<String> holders : offices.values()) {
            holders.clear();
        }
        holderAlliance.clear();
        // 清零必须留痕（B13 §3 与禁止项「不要让国库支出无日志」）：这笔钱没有收款人，
        // 所以 payee 写成一个用途标识而不是某个人 —— 写空串会被"没有 payee 的日志无法追责"这条
        // 约定判成不合法，而把它写成某个真实 id 更是在伪造一笔转账。
        if (treasury > 0L) {
            appendLog(new TreasuryLog(now, operatorId, writeOffPayee, treasury, writeOffReason, 0L));
        }
        treasury = 0L;
        disbandedAt = now;
    }

    // ---------- 只读访问 ----------

    private LevelRule currentLevelRule() {
        LevelRule matched = rules.levels().get(0);
        for (LevelRule rule : rules.levels()) {
            if (rule.nationLevel() <= level) {
                matched = rule;
            }
        }
        return matched;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String kingId() {
        return kingId;
    }

    public long capitalX() {
        return capitalX;
    }

    public long capitalY() {
        return capitalY;
    }

    public int level() {
        return level;
    }

    public long treasury() {
        return treasury;
    }

    public long treasuryCap() {
        return currentLevelRule().treasuryCap();
    }

    public int memberCap() {
        return (int) currentLevelRule().memberCap();
    }

    public int policySlotCount() {
        return (int) currentLevelRule().policySlotCount();
    }

    public long warCooldownMillis() {
        return currentLevelRule().warCooldownHours() * 3600L * 1000L;
    }

    public Set<String> memberAllianceIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(memberAlliances.keySet()));
    }

    public int memberAllianceCount() {
        return memberAlliances.size();
    }

    public boolean hasAlliance(String allianceId) {
        return memberAlliances.containsKey(allianceId);
    }

    /** 某个联盟的入籍冷却截止时刻；无冷却为 0。 */
    public long joinCooldownUntil(String allianceId) {
        return joinCooldownUntil.getOrDefault(allianceId, 0L);
    }

    public boolean isDisbanded() {
        return disbandedAt > 0L;
    }

    public Rules rules() {
        return rules;
    }

    private void requireActive() {
        if (isDisbanded()) {
            throw new IllegalStateException("国家已解散，不能再变更");
        }
    }

    // ==================================================================
    // 国策（B13 §4 / B21 块③，2026-09-30 裁决）
    //
    // **全部惰性**：轮次由 settlePolicy(now) 推进，而 settlePolicy 由每一次
    // 读取（policyRound）与每一次写（propose / vote）顺手调用。服务端不跑任何定时任务
    // （check-no-scheduled.sh 是门禁），这与周税 settleTax 同一手法。
    //
    // **状态机（三段，草案 §三 已记）**：
    //   窗口关着 + 生效中/空      → PROPOSING（可提案）
    //   到 nextVoteAt 且有提案   → 开窗，VOTING（policyVoteMillis）
    //   到点                      → 结算：过门槛的按四级排序占 policySlotCount 个槽位，
    //                               生效 policyRoundMillis - policyVoteMillis，清空提案与票
    //                               → 下一轮开窗时刻 = 最早到期那一刻
    // ==================================================================

    /**
     * 推进轮次（惰性）。读取与写入都先调它。
     *
     * <p><b>三步的顺序不能换</b>：先丢掉已到期的生效国策，再结算已关的窗口，最后才考虑开窗。
     * 反过来的话会出现「窗口刚开就立刻被结算」—— 因为开窗那一刻 {@code policyVoteEndsAt}
     * 已经是过去式（存档里带了旧的时刻），那会让本轮票数恒为 0。
     */
    public synchronized void settlePolicy(long now) {
        if (isDisbanded()) {
            return;
        }
        // ① 到期的生效国策出列（空出来的槽位不会被自动填上 —— 填槽只发生在结算那一刻）
        activePolicies.entrySet().removeIf(entry -> entry.getValue() <= now);
        // ② 窗口开着且到点 → 结算
        if (policyVoteEndsAt > 0L) {
            if (now >= policyVoteEndsAt) {
                applyVoteOutcome(now);
            }
            return;
        }
        // ③ 窗口没开：到点且本轮有提案才开窗
        if (!policyProposals.isEmpty() && now >= policyNextVoteAt) {
            policyVoteOpenedAt = now;
            policyVoteEndsAt = now + rules.policyVoteMillis();
        }
    }

    /**
     * 结算本轮：判门槛、占槽位、续下一轮。
     *
     * <p><b>四条排序键（协议 {@code slotOrderNote} 的服务端实现）</b>：
     * 赞成率降序 → 赞成票数降序 → 提案时刻升序 → policyId 字典序。
     * 前三级都能从票数与时刻直接推出；最后一级是<b>纯确定性装置</b> ——
     * 同率同数同时刻时不能靠哈希顺序决定，否则同一份存档会复算出不同的槽位归属。
     *
     * <p><b>同一条国策不占两个槽</b>：两条不同的提案指向同一条国策是可能的（去重按「本轮有没有提过这条」，
     * 但提案 id 不同），占两个槽等于同一条 buff 生效两次 —— 那会让公示的票数与实际收益对不上。
     */
    private void applyVoteOutcome(long now) {
        List<ProposalTally> passed = new ArrayList<>();
        for (ProposalTally tally : tallies()) {
            if (tally.passed()) {
                passed.add(tally);
            }
        }
        passed.sort(Nation::slotOrder);

        int slots = policySlotCount();
        long expiresAt = now + activeDurationMillis();
        Map<String, Long> next = new LinkedHashMap<>();
        for (ProposalTally tally : passed) {
            if (next.size() >= slots) {
                break;
            }
            next.putIfAbsent(tally.policyId(), expiresAt);
        }
        policyProposals.clear();
        policyVotes.clear();
        activePolicies.clear();
        activePolicies.putAll(next);
        policyVoteOpenedAt = 0L;
        policyVoteEndsAt = 0L;
        // 下一轮开窗 = 最早到期那一刻。生效段与提案段合起来等于一整轮
        // （轮次长度由 policyRoundMillis 给，与投票窗是同一个参数的派生量）。
        policyNextVoteAt = next.isEmpty() ? now : expiresAt;
    }

    /**
     * 生效段长度 = 一轮周期 − 投票窗。
     *
     * <p><b>为什么用减法而不是直接读一个参数</b>：轮次只有一个时间旋钮（提案段与投票段同长，
     * 草案 §三 已记），生效段是剩下的那段。写成减法之后，轮次总长恒等于 {@code policyRoundMillis}，
     * 不会因为三段各读一个参数而对不上。
     */
    private long activeDurationMillis() {
        long active = rules.policyRoundMillis() - rules.policyVoteMillis();
        return active > 0L ? active : rules.policyVoteMillis();
    }

    /** 槽位竞争的四级排序。返回值 < 0 表示 a 优先。 */
    private static int slotOrder(ProposalTally a, ProposalTally b) {
        // 赞成率：交叉相乘比较，避免定点除法的舍入影响先后
        int byRatio = Long.compare(b.yes() * a.actualVoters(), a.yes() * b.actualVoters());
        if (byRatio != 0) {
            return byRatio;
        }
        int byYes = Long.compare(b.yes(), a.yes());
        if (byYes != 0) {
            return byYes;
        }
        int byAt = Long.compare(a.at(), b.at());
        if (byAt != 0) {
            return byAt;
        }
        return a.policyId().compareTo(b.policyId());
    }

    /**
     * 逐条提案计票，并判是否过门槛。
     *
     * <p><b>门槛 = 赞成 ≥ 实际投票人数 × 门槛比例，且实际投票人数 ≥ 参与下限</b>（裁决 A3）。
     * 两个条件缺一不可：只有比例的话「1 个人投赞成」就是 100% 通过，
     * 等于让一个小号替 800 人定国策。
     */
    public List<ProposalTally> tallies() {
        List<ProposalTally> out = new ArrayList<>();
        int minVoters = memberAllianceCount() * rules.policyMinVotersPerAlliance();
        for (PolicyProposal proposal : policyProposals.values()) {
            Map<String, Boolean> votes = policyVotes.getOrDefault(proposal.id(), Map.of());
            List<String> supporters = new ArrayList<>();
            List<String> opponents = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : votes.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    supporters.add(entry.getKey());
                } else {
                    opponents.add(entry.getKey());
                }
            }
            supporters.sort(null);
            opponents.sort(null);
            long yes = supporters.size();
            long no = opponents.size();
            long voters = yes + no;
            boolean enough = voters >= minVoters;
            boolean enoughYes = enough && FixedPoint.mul(voters, rules.policyMinVoteRatio()) <= yes;
            out.add(new ProposalTally(proposal.id(), proposal.policyId(), proposal.proposedBy(),
                    proposal.at(), yes, no, supporters, opponents, enoughYes));
        }
        return out;
    }

    /** 提案被哪一位挡住（{@link PolicyBlock#NONE} = 挡不住）。不抛异常，面板与写路径共用这一份判定。
     *
     *  <p><b>这里没有「是不是 Bot」这一问，而那是有意的</b>：{@code check-no-bot-privilege.sh}
     *  是一条门禁，它规定「只有 {@code BotRegistry} 可以问这是不是 Bot」——
     *  游戏逻辑里出现 {@code if (isBot)} 哪怕是「拒绝它」也算违规，因为白名单只放行定义处。
     *  所以 Bot 拒投由 {@code game-web} 在调本方法<b>之前</b>挡掉（它持有 {@code BotRegistry}），
     *  对应协议里的 {@code NationPolicyBlockReason.BOT_NOT_ALLOWED}。
     *  领域层多一个 {@code isBot} 参数换来的只是「能在同一处判完」，代价是让内核知道 Bot 存在。
     */
    public PolicyBlock proposeBlock(String operatorId, boolean hasProposePermission, long now) {
        settlePolicy(now);
        if (!hasProposePermission) {
            return PolicyBlock.NOT_PROPOSER;
        }
        if (policyVoteEndsAt > 0L) {
            return PolicyBlock.NOT_VOTING;
        }
        return PolicyBlock.NONE;
    }

    /**
     * 投票被哪一位挡住。
     *
     * <p><b>这一位不检查权限</b>：投票权是**每成员一票**（裁决 A2），与国策提案权那张权限表无关。
     * 写成「也查一下权限」的话，一个普通成员会看到自己不能投票 —— 而 B13 §2 给的是
     * 「议员：投票、提案」，2026-09-30 裁决 A2 把投票权放宽到全体成员，两者要分开。
     *
     * <p><b>{@code ALREADY_VOTED} 不在这里</b>：那是<b>每条提案</b>的判断，而本方法是轮次级的。
     * 轮次级只能回答「此刻能不能投」，某一条投过了没有由 {@code myVotes} 表达 ——
     * 面板据此把那颗键置灰，写路径据此回那一枚码。
     */
    public PolicyBlock voteBlock(String voterId, long now) {
        settlePolicy(now);
        if (policyVoteEndsAt <= 0L) {
            return policyProposals.isEmpty()
                    ? PolicyBlock.NO_PROPOSAL_YET : PolicyBlock.NOT_VOTING;
        }
        return PolicyBlock.NONE;
    }

    /**
     * 提案。
     *
     * @param proposalId           提案 id，<b>由调用方生成</b>（领域层不造 id，与 {@code techId} 同一条分工）
     * @param hasProposePermission 提案权判定结果，调用方读 {@code role_permission} 表得出
     * @throws PolicyException 被 {@link PolicyBlock} 挡住
     */
    public synchronized PolicyProposal propose(String proposalId, String policyId, String operatorId,
                                               boolean hasProposePermission, long now) {
        requireActive();
        PolicyBlock block = proposeBlock(operatorId, hasProposePermission, now);
        if (block != PolicyBlock.NONE) {
            throw new PolicyException(block, "提案被挡住：" + block);
        }
        for (PolicyProposal existing : policyProposals.values()) {
            if (existing.policyId().equals(policyId)) {
                throw new PolicyException(PolicyBlock.ALREADY_PROPOSED,
                        "本轮已经提过这一条国策（提案 " + existing.id() + "）");
            }
        }
        // 本轮的第一条提案把开窗时刻推到「一个提案段之后」，否则国王点一下就把 24 小时窗口
        // 关上了，而 B13 §2 把「国策提案」给了内政官 4 席 —— 不给时间等于这个官职形同虚设。
        if (policyNextVoteAt <= now) {
            policyNextVoteAt = now + activeDurationMillis();
        }
        PolicyProposal proposal = new PolicyProposal(proposalId, policyId, operatorId, now);
        policyProposals.put(proposalId, proposal);
        return proposal;
    }

    /**
     * 投票（{@code NationVoteReq(proposalId, boolean support)} 的落地）。
     *
     * <p><b>同 {@code researchTech}，为什么 synchronized</b>：应用层的锁按玩家加，
     * 而这本账按国家共享。「先查有没有投过 → 再记一票」会被两个玩家穿过，
     * 症状是票数 +2 而名单只有 1 个人 —— 而票数与名单必须自证一致（B13 验收 11）。
     */
    public synchronized void vote(String proposalId, String voterId, boolean support, long now) {
        requireActive();
        PolicyBlock block = voteBlock(voterId, now);
        if (block != PolicyBlock.NONE) {
            throw new PolicyException(block, "投票被挡住：" + block);
        }
        PolicyProposal proposal = policyProposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("提案不存在：proposalId=" + proposalId
                    + "（它可能属于上一轮 —— 轮次结算时整份清空）");
        }
        Map<String, Boolean> votes = policyVotes.computeIfAbsent(proposalId,
                k -> new LinkedHashMap<>());
        if (votes.containsKey(voterId)) {
            throw new PolicyException(PolicyBlock.ALREADY_VOTED,
                    "这一票已经投过了：proposalId=" + proposalId + " voterId=" + voterId
                            + "（改票本批不做，要改就是先撤回再投）");
        }
        votes.put(voterId, support);
    }

    /**
     * 国策轮次的完整状态。**读这个动作本身会推进轮次**（与 {@code GET /nation} 顺手
     * {@code settleTax} 同一手法）—— 玩家看到的必须是当下的数。
     */
    public synchronized PolicyRound policyRound(String viewerId, boolean hasProposePermission,
                                                long now) {
        settlePolicy(now);
        List<String> myProposals = new ArrayList<>();
        Map<String, Boolean> myVotes = new LinkedHashMap<>();
        for (PolicyProposal proposal : policyProposals.values()) {
            if (proposal.proposedBy().equals(viewerId)) {
                myProposals.add(proposal.id());
            }
        }
        for (Map.Entry<String, Map<String, Boolean>> entry : policyVotes.entrySet()) {
            Boolean mine = entry.getValue().get(viewerId);
            if (mine != null) {
                myVotes.put(entry.getKey(), mine);
            }
        }
        List<ActivePolicy> active = new ArrayList<>();
        for (Map.Entry<String, Long> entry : activePolicies.entrySet()) {
            active.add(new ActivePolicy(entry.getKey(), entry.getValue()));
        }
        return new PolicyRound(phaseOf(), policySlotCount(), memberAllianceCount(),
                tallies(), active,
                proposeBlock(viewerId, hasProposePermission, now),
                voteBlock(viewerId, now),
                myProposals, myVotes, policyNextVoteAt, policyVoteEndsAt);
    }

    /**
     * 当前正在生效的国策（按到期时刻升序），供乘区 G 的装配点读。
     *
     * <p><b>这一层刻意不读配置表</b>：它只回答「哪些 policyId 生效、到什么时候」，
     * 幅度与效果由 game-config 那一侧解析 —— 与 {@code NationTechBonuses} 同一条分工。
     */
    public synchronized List<ActivePolicy> activePolicies(long now) {
        settlePolicy(now);
        List<ActivePolicy> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : activePolicies.entrySet()) {
            out.add(new ActivePolicy(entry.getKey(), entry.getValue()));
        }
        out.sort(Comparator.comparingLong(ActivePolicy::expiresAt)
                .thenComparing(ActivePolicy::policyId));
        return out;
    }

    private PolicyPhase phaseOf() {
        if (policyVoteEndsAt > 0L) {
            return PolicyPhase.VOTING;
        }
        return activePolicies.isEmpty() ? PolicyPhase.PROPOSING : PolicyPhase.ACTIVE;
    }
}
