package com.ironoath.web.nation;

import java.util.Optional;

import com.ironoath.core.nation.WarScoreBoard;

/**
 * 职责：国战积分板的存储端口（B13 §一 §7 的承载）。
 * 依赖：game-core 的 {@link WarScoreBoard}。
 *
 * <p><b>为什么端口在 game-web 而不在 game-core</b>：与 {@code NationStore}、
 * {@code BattleReportStore}、{@code TrackEventStore} 同一条理由 —— game-core 里没有任何逻辑需要
 * 「取出当前这一场仗」或「把所有战事列出来」，那是服务层与运维的诉求。把端口下沉到 core
 * 只会让 core 多一个没人用的抽象（而 {@code WarScoreBoard} 的类注释本来就写着
 * 「由 game-web 在国战开始时载入、结束时落盘一次」—— 承载的责任从一开始就落在这一层）。
 *
 * <p><b>{@link WarScoreBoard} 是可变对象</b>（击杀、关卡、疲劳都改它自己），所以约定与 {@code Nation} 一致：
 * 调用方改完必须 {@link #save} 写回，而<b>两套实现读出来都是副本</b>
 * （{@code InMemoryNationStore} 2026-09-10 那次教训：原先直接持有同一个引用，
 * 于是「忘记写回」在 dev 下看不出来、换 Mongo 就丢改动）。差异由 {@code WarStoreEquivalenceTest} 守着。
 *
 * <p><b>为什么这一份不带乐观锁版本，而 {@code NationStore.save} 带</b>：写形状不一样。
 * 国家那一族是<b>多个官员各改各的字段</b>（任命、外交、周税），且 {@code PlayerLock} 按玩家加锁，
 * 拦不住两个不同联盟同时改同一个国家 —— 只有版本号能把「后写的整档覆盖先写的」变成一次响。
 * 国战板的既定形状是内核类注释那一条：<b>开战载入、结束落盘一次</b>，中间不落盘
 * （禁止项明写「不要让国战积分在数据库层聚合」，200 QPS 的行军事件若在库里做 SUM，MongoDB 先崩）。
 * 所以本切片没有「两条读-改-写并发抢同一档」的形状，版本没有对手可挡。
 *
 * <p><b>这一条不适用于下一切片，届时必须回来改这里</b>：一旦击杀累计改成「中途定期 flush」
 * 或多实例各自累加再合并，端口就必须加版本（与 {@code CityRepository} 那四个版本化仓储同一条契约），
 * 或改成存储层的原子累加口。<b>现在留的口子是</b>：{@code save} 是整档替换，两个实例各持一份板子
 * 先后落盘，后写的会整块盖掉先写的积分与疲劳，而全链路不报错。这条窗口记在收口清单，不藏在注释里。
 */
public interface WarStore {

    /**
     * 存档主键的推导口径：<b>两套实现必须用这一个函数</b>，各写一份就会在某处漂掉
     * （内存版按 A 键、Mongo 按 B 键时，等价测试照样全绿，因为它两边都读自己写的键）。
     *
     * <p>为什么用 {@code startedAt} 而不是再造一个 warId：{@code WarScoreBoard} 里没有 id 字段，
     * 而 {@code startedAt} 是 {@code final} 的 —— 主键因此<b>永不受写操作影响</b>，
     * 一次 {@code save} 不可能把这一档改到另一个键上去（那等于把历史挪了个位置）。
     * 同毫秒开两场在现实中不成立（同一时刻只立一场），真撞上时 {@link #insertIfAbsent} 返回 false，
     * 那正是「不该有两场」的正确反应。
     */
    static String documentIdOf(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就推不出主键");
        }
        return "war_" + board.startedAt();
    }

    /**
     * 原子地建档：<b>只在没有人写过这一场时</b>插入（{@code _id} 唯一约束）。
     *
     * @return false 表示这一场已经建过 —— 与 {@code NationStore#insertIfAbsent} 同一条：
     *         写成「先查后插」会插出两份同开场的战事，两份各自算各自的积分与疲劳，
     *         而玩家会看到两个进度条
     */
    boolean insertIfAbsent(WarScoreBoard board);

    /**
     * 原子地开一场新仗：<b>只在当前没有任何未结束的战事时</b>插入，否则返回 false。
     *
     * <p><b>为什么这条能力在存储端口上，而不是 service 里 {@code findLatest()} → 判空 → {@code insert}</b>：
     * 宣战是<b>两个国王各自</b>都能做的动作，而 {@code PlayerLock} 是按玩家加锁的 —— 两把锁互不相干。
     * 「查有没有仗」与「插一场」是两步，同一秒内各查各的都得到「没有」，于是插出<b>两场平行账</b>：
     * 两份各自累积击杀、各自的 {@code findLatest()} 给出不同的一场，而全链路不报错。
     * 与 {@code NationStore#settleWeeklyTax} 是同一条判断：幂等键挡得住重放，挡不住并发。
     *
     * <p><b>临界区的边界要说清，别把它当成通用不变量</b>：这一条挡住的是<b>同进程内</b>的并发 ——
     * 内存版是对象监视器，Mongo 版是这个存储 bean 上的实例锁再加 {@code _id} 撞键兜底。
     * <b>跨进程不提供</b>这一保证，因为「全服一个进程」是 {@code PlayerLock} 早就成立的前提；
     * 真要横向扩展，先要给整个 web 层换分布式锁，而不是在这里加 sleep 重试。
     *
     * @param board 要开的这一场（{@code phase} 必须是未结束的那两段之一）
     * @return true 表示仗开起来了；false 表示已经有一场未结束的仗 ——
     *         <b>调用方要响亮拒绝（{@code WAR_ALREADY_ACTIVE}），不许改成"再试一次"</b>：
     *         重试只会把一个"两个人抢着宣战"变成"其中一个人的请求超时"，那是同一件事更糟的表现
     */
    boolean insertIfNoneActive(WarScoreBoard board);

    /**
     * 整档落盘（内核类注释那一条「结束时落盘一次」）。
     *
     * <p><b>不许静默插入</b>：建档只走 {@link #insertIfAbsent}。理由与 {@code NationStore#save} 同一条 ——
     * 静默插入会让「读不到就新建一份」这种错误写法的并发后果变成两份档，而不是一个异常。
     *
     * @throws IllegalStateException 这一场还没建过（调用方走错了方法）
     */
    void save(WarScoreBoard board);

    /**
     * 当前这一场（{@code startedAt} 最大的一份），只读端点与击杀累计都用它。
     *
     * <p><b>切片 2a 之后「最近一场」与「唯一一场」是同一件事</b>：{@link #insertIfNoneActive}
     * 保证同时只有一场未结束的仗，所以取 {@code startedAt} 最大的那一份就是取那一场活的 ——
     * 不需要在这里再判一次 phase。
     *
     * <p>⚠️ <b>这句话的成立前提是「所有建档都走 {@code insertIfNoneActive}」</b>。
     * {@link #insertIfAbsent} 仍然公开着（等价测试与"按赛季预建多场"这类将来用法），
     * 一旦有人用它开出第二场，这里就必须收紧成「{@code phase != SETTLED} 的那一场」，
     * 否则结算完的历史仗会被当成当前仗读给玩家、而新仗永远看不见。
     * 收紧点在这一个方法里（两套实现各一处），不在调用方，所以调用方不需要跟着改。
     *
     * @return 重建出来的板子（规则现取，见 {@link WarRulesAssembler}）；一份都没有时为 empty
     */
    Optional<WarScoreBoard> findLatest();

    /**
     * 惰性推进这一场仗的时间：<b>最新那一场如果已经打完了规定时长，就在这一句里结算并落盘</b>。
     *
     * <p><b>为什么结算挂在「读」上而不是定时任务</b>：服务端禁常驻定时器（{@code check-no-scheduled.sh}
     * 是门禁，时间推进一律惰性驱动），所以「到点结算」必须找一个必然发生的动作当载体。
     * 与国策轮次（{@code NationAppService#nationPolicy} 读视图顺带 {@code settlePolicy}）、
     * 国库周税（{@code NationStore#settleWeeklyTax}）是同一手法，不是这里图省事。
     *
     * <p><b>为什么这个口长在存储端口上，而不是服务层写 {@code findLatest() → settle() → save()}</b>：
     * 那是一次<b>没有保护的读-改-写</b>，而它抢的档与 {@link #insertIfNoneActive}、{@link #recordKills}
     * 是同一块板子。两个玩家同时打开面板会各推一次，第二次落在内核那条
     * {@link WarScoreBoard#settle(long)} 的护栏上直接抛 {@code IllegalStateException} ——
     * 挂在读端点上就是 500；更糟的是「结算写回」与「同一秒有人刚记进来的击杀」互相整档覆盖。
     * 因此<b>判到期、结算、落盘三件事必须在同一个临界区里</b>，且用那两条已经 in use 的同一把锁
     * （内存版是本对象的监视器，Mongo 版是 {@code activeLock}）。
     *
     * <p><b>返回板子而不是返回 boolean</b>：调用方紧接着就要拿这一块板画视图，让它再
     * {@link #findLatest()} 一次等于把窗口重新打开 —— 那一读完全可能读到别人刚开的<b>新</b>一场，
     * 于是玩家在同一秒看到「刚结算完」的状态变迁与「新仗 0 分」的面板。
     *
     * <p><b>{@code durationMillis} 取的是当前配置而不是建档那一刻</b>（规则不进快照，见
     * {@link WarRulesAssembler}）：热调 {@code WAR_DURATION_HOURS} 会立刻影响已开着的仗。
     * 这与本类其余读口的口径一致（数值一律现取），记在这里是因为「缩短时长能不能提前结束一场
     * 正在打的仗」是一个有人会以为是锁死的语义问题。
     *
     * @param now 服务端时刻。<b>由调用方传入，本方法不读时钟</b> —— 与 {@link #recordKills} 同一条纪律，
     *            这样「跨过那一刻」这个边界能被测试精确摆位，不必 sleep
     * @return 最新那一场（可能就是刚刚被这一句结算完的那份）；一份都没有时为 empty
     */
    Optional<Settlement> settleIfExpired(long now);

    /**
     * 一次惰性推进的结果。
     *
     * <p><b>{@code settledNow} 说的是"这一句把仗结掉了"，不是"这一场现在是结算态"</b> ——
     * 前者只会出现一次，后者每次读都为真。发奖那一跳必须挂在前者上：
     * 挂在后者上等于每一读都重新发一遍赛季分，而 {@code SeasonBoardStore.accumulate} 是<b>累加</b>语义，
     * 它会照单全收。这也是 {@code #753} 那条"结算返回的 `Result` 至今无消费者"里被接走的第一半。
     *
     * <p><b>{@code result} 与 {@code settledNow} 是同一件事的两面，所以构造时互验</b>：
     * 内核 {@link WarScoreBoard#settle(long)} <b>只有一次机会</b>给出终局（第二次调它直接抛，
     * 而积分定格后也无法再重算），那份 {@link WarScoreBoard.Result} 里带着 {@code winnerId} —— 3b-2 的
     * {@code WAR_SEASON_POINT_WINNER} 只能从它拿。<b>如果这里只带板子不带结果</b>，发奖那一侧就只有两条路：
     * 要么再调一次 {@code settle}（撞内核护栏、挂在读端点上就是 500），要么自己在 Java 侧比一遍积分
     * —— 那是把"平分不给胜者"这条判定搬到第二个家，而搬过去的那一份早晚和内核漂开（内核那条是按
     * 参战方登记顺序遍历的）。所以这一格把它原样带出来，代价是记录多一个字段。
     *
     * @param board      推进之后（或本来就好好的）那一场
     * @param settledNow 这一次读是否正好是把它结算掉的那一次
     * @param result     {@link WarScoreBoard#settle(long)} 的返回值；<b>仅当 {@code settledNow} 为真时非空</b>
     */
    record Settlement(WarScoreBoard board, boolean settledNow, WarScoreBoard.Result result) {

        public Settlement {
            if (settledNow && result == null) {
                throw new IllegalArgumentException(
                        "settledNow=true 却不带结算结果：这一场的胜者已经没有第二次机会被算出来了，"
                                + "发奖那一侧只能把 WINNER 那一档静默跳过");
            }
            if (!settledNow && result != null) {
                throw new IllegalArgumentException(
                        "settledNow=false 却带了结算结果：那会造出「没结掉这一场但手里有终局」这种"
                                + "谁也无法解释的状态，而发奖判断读的正是 settledNow");
            }
        }

        /** 没结掉那一次的便捷构造（两套实现的"未到点"分支共用，免得各写一份 null 的形状）。 */
        public static Settlement notSettled(WarScoreBoard board) {
            return new Settlement(board, false, null);
        }
    }

    /**
     * 这一对两国之间<b>最近的那一场</b>，<b>不论打完没打完</b> —— 宣战冷却的唯一数据源。
     *
     * <p><b>为什么从历史里推，而不是在 {@code Nation} 上加一个 {@code lastWarDeclaredAt} 字段</b>：
     * 前者只多一个查询口，后者要动聚合、动 {@code Nation.Snapshot}、动别人在写的
     * {@code NationStoreEquivalenceTest}。而这份集合里<b>只有个位数文档</b>（一场仗一档，
     * 全服同时只有一场活的），扫一遍比给国家档再加一列便宜得多。
     * 更实的一条：那个字段是<b>第二份真相</b> —— 仗的档里已经写着谁和谁、什么时候开的，
     * 国家档里再记一次「上次宣战」，两处一旦漂移（写成功一处、失败一处）就没有仲裁者。
     *
     * <p><b>判定按「这一对」而不是「发起国打过谁」，是对称的</b>：只挡发起国的话，
     * 被打的一方可以立刻反宣，而反宣成功又让对面重新进入冷却 ——
     * 同一对两国能在冷却期内靠乒乓互宣把击杀刷满，那正是这一格要防的形状。
     * 对称不影响任何一国打<b>第三国</b>：判据按那一对取档，换目标就换一个档去查。
     *
     * @param nationA 这一对里的一个（顺序无关）
     * @param nationB 另一个
     * @return 两方都在的档里 {@code startedAt} 最大的那一份；这一对从来没打过时为 empty
     */
    Optional<WarScoreBoard> findLatestBetween(String nationA, String nationB);

    /**
     * 击杀归属的结果（四个值各对应一种"要不要记账"的判断，不是一个笼统的 boolean）：
     * 战斗每天都在发生，把四种情况压成一个布尔，日志与排查就只能靠猜。
     */
    enum KillResult {
        /** 参战国的人：国家击杀分、全服进度、个人账三样都加了 */
        APPLIED,
        /** 仗在打，但这个人的国家没参战：加全服进度与个人账，<b>不给任何国家加分</b>（B13 §7 的原话） */
        SERVER_ONLY,
        /** 没有任何一场未结束的仗：什么都没记 */
        NO_ACTIVE_WAR,
        /** 零击杀：一次写入都不该发生（未破墙、平局、纯拦截失败都有可能是 0） */
        SKIPPED
    }

    /**
     * 原子地把一场战斗的击杀记进当前那一场仗：<b>读、判、改、写回全在存储层的同一个临界区里</b>。
     *
     * <p><b>为什么这条必须长在存储层而不是服务层</b>：这是本档最热的一条写路径 —— 每一场战斗结算都会来一次，
     * 而 {@code PlayerLock} 是按玩家分的，两个同时打完仗的玩家各拿各的锁。
     * 服务层写「读板子 → 加 → 落盘」的话，两次并发结算会有一次的击杀静默消失
     * （内存版是后写覆盖前写，Mongo 版是整档替换互相盖），而症状只是"全服进度条好像少涨了一点"。
     * 与 {@code NationStore#settleWeeklyTax} 同一条判断。
     *
     * <p><b>这一格把 {@code save} 那段"没有多写者读-改-写的形状"作废了</b>：本方法就是那个形状，
     * 而它挡并发靠的是<b>实例临界区 + 全服单实例</b>这条前提（与 {@link #insertIfNoneActive} 同一条），
     * 不是靠乐观锁版本。跨进程部署时这两处都要改成带版本的 CAS 或原子累加口。
     *
     * @param killerNationId 击杀者所属国家 id；查不到（没国籍或联盟退国）传 null，按无主处理
     * @param killerPlayerId 击杀者玩家 id（赛季分的键），null 表示无主击杀
     * @param units          消灭的单位数；<=0 时不动任何东西
     * @return 归属结果。调用方只拿它打一行日志 —— <b>不许拿它做业务分支</b>：
     *         国战记账是旁路，它成不成就都不该改变这场战斗的结果
     */
    KillResult recordKills(String killerNationId, String killerPlayerId, long units);

    /**
     * 归属判断的<b>唯一一份</b>实现，内存与 Mongo 两套存储共用（放在端口而不是任何一份实现里：
     * Mongo 版不该依赖内存实现那个包）。
     *
     * <p>参战国走 {@link WarScoreBoard#recordKill(String, String, long)}（国家击杀分 + 全服进度 + 个人账），
     * 没参战的人走 {@link WarScoreBoard#recordServerKill(String, long)}
     * （只加全服进度与个人账 —— B13 §7 明写"不打国战的人的贡献也算"）。
     * 这条分支如果两份实现各写一遍，早晚出现"内存版算了、Mongo 版没算"那种只在换存储那天才看见的事故。
     *
     * <p><b>它只改传入的那块板子，不落盘</b> —— 写回是调用方（存储层临界区内）的责任。
     */
    static KillResult applyKills(WarScoreBoard board, String killerNationId,
                                 String killerPlayerId, long units) {
        if (killerNationId != null && board.registeredNations().contains(killerNationId)) {
            board.recordKill(killerNationId, killerPlayerId, units);
            return KillResult.APPLIED;
        }
        board.recordServerKill(killerPlayerId, units);
        return KillResult.SERVER_ONLY;
    }

    /**
     * 「这一场该结算了」的<b>唯一一份</b>判据，两套实现共用（与 {@link #applyKills} 同一条理由：
     * 放在任何一份实现里，另一份早晚漂掉）。
     *
     * <p>两个条件各挡一种误判，缺一个都不是"少一条保险"而是少一条正确性：
     * <ul>
     *   <li>{@code phase != SETTLED} —— 已是历史的档必须直接放过。不写这一条，第二次读会撞内核
     *       {@link WarScoreBoard#settle(long)} 那条「重复结算会让积分被算两遍」的护栏并抛到端点上。
     *       内核那条护栏<b>保留且不许绕过</b>：它挡的是"有人不经存储层自己 settle"，
     *       而这里先判是为了让<b>正常路径</b>不去敲它。</li>
     *   <li>{@code now >= startedAt + durationMillis} —— 未到期不许结算。写宽（比如用 {@code >} 配
     *       分钟取整）会让最后不足一分钟的那段占领分被提前定格，而占领分正是防偷家的全部机制。</li>
     * </ul>
     *
     * <p>用 {@code >=} 而不是 {@code >}：时长是配置里的整数小时，"刚好到点"那一次读必须算到期，
     * 否则玩家看到的面板会停在 {@code remainingSec=0} 而 {@code phase=SIEGE} —— 一个自相矛盾的读数。
     */
    static boolean dueToSettle(WarScoreBoard board, long now) {
        return board.phase() != WarScoreBoard.Phase.SETTLED
                && now >= board.startedAt() + board.rules().durationMillis();
    }

    /**
     * 一次疲劳累积的结果（与 {@link KillResult} 同一条理由：四种情况别压成一个 boolean）。
     */
    enum FatigueResult {
        /** 参战方的人：这一笔疲劳加上了。 */
        APPLIED,
        /**
         * 仗在打，但这个人所属的国家没参战：**不吃国战的疲劳闸门**。
         *
         * <p>那道闸的设计意图是「逼迫参战方调度轮班」（B13 §7），把不参战的人也闸住
         * 等于让一场别人的仗影响全服的日常打野 —— 那不是设计者写过的东西。
         */
        NOT_PARTICIPANT,
        /** 没有任何一场未结束的仗：什么都没记。 */
        NO_ACTIVE_WAR,
        /** 两项增量都是 0：一次写入都不该发生（未破墙、零伤兵都可能是 0）。 */
        SKIPPED
    }

    /**
     * 原子地把一笔疲劳记进当前那一场仗（与 {@link #recordKills} 同一把锁、同一条纪律）。
     *
     * <p><b>为什么也在存储层</b>：理由与 {@link #recordKills} 一个字都不用改 ——
     * 这是本档最热的第二条写路径（<b>每次行军</b>都会来一次），而「读板子 → 加 → 落盘」
     * 在服务层同样是没有保护的读-改-写。
     *
     * <p><b>落在哪个口径</b>：只有参战方才吃这道闸（见 {@link FatigueResult#NOT_PARTICIPANT}），
     * 而「一仗打完之后疲劳怎么办」由内核的 clamp 与持久化决定（结算不重置疲劳：
     * 它记的是「这一场里你出过多少力」，与积分同寿）。
     *
     * @param fatigueNationId 行为者所属国家 id；查不到（没国籍或联盟退国）传 null，按非参战方处理
     * @param playerId        行为者（疲劳按人记）
     * @param marches         这一次要加的行军次数（发起一次行军 = 1）
     * @param wounded         这一次要加的伤兵数
     * @return 四种结果之一；调用方只拿它打一行日志 —— 与 {@link #recordKills} 同一条：
     *         国战记账是旁路，成不成都不得改变那一枪本身的结果
     */
    FatigueResult addFatigue(String fatigueNationId, String playerId, long marches, long wounded);

    /**
     * 疲劳归属的<b>唯一一份</b>实现（两套存储共用，与 {@link #applyKills} 同一条理由）。
     *
     * <p><b>它只改传入的那块板子，不落盘</b> —— 写回是调用方（存储层临界区内）的责任。
     */
    static FatigueResult applyFatigue(WarScoreBoard board, String fatigueNationId, String playerId,
                                     long marches, long wounded) {
        if (marches <= 0L && wounded <= 0L) {
            return FatigueResult.SKIPPED;
        }
        if (fatigueNationId == null || !board.registeredNations().contains(fatigueNationId)) {
            return FatigueResult.NOT_PARTICIPANT;
        }
        board.addFatigue(playerId, marches, wounded);
        return FatigueResult.APPLIED;
    }

    /**
     * 领取全服目标奖励的结果（四个值各对应一种"能不能领"的判断，与 {@link KillResult} 同一条理由：
     * 把四种情况压成一个 boolean，日志与排查就只能靠猜）。
     */
    enum GoalClaimResult {
        /** 一块板子都没有：没有仗就没有在积累的全服击杀，目标自然没达成。 */
        NO_WAR,
        /** 有板子但目标还没达成。 */
        NOT_REACHED,
        /** 目标达成了，但这个人这一场已经领过（验收 10：每人每场只领一次）。 */
        ALREADY_CLAIMED,
        /** 这一次真的领到了 —— <b>调用方随后负责发钱</b>，且必须把它写回（名单在板子上）。 */
        CLAIMED
    }

    /**
     * 在<b>存储层临界区</b>里领一次全服目标奖励：判定与写入必须在同一步完成。
     *
     * <p><b>为什么这条长在存储层</b>：与 {@link #recordKills} 同一条 —— 服务层写
     * {@code findLatest → claimServerGoal → save} 是一次<b>没有保护的读-改-写</b>：
     * 两个玩家同时点领取，各自读到名单里没有自己、各自 save，后写的那份把前一份盖掉
     * （而内核那条 Set 护栏只挡得住同一个对象上的并发，挡不住两份副本先后落盘）。
     * 于是"每人只领一次"在并发下退化成"谁后写谁作数"，而发钱是照 save 成功那一次发的。
     *
     * <p><b>只标名单、不发钱</b>：发钱要动玩家存档（{@code RewardService}），那不在战事档的临界区里。
     * 顺序是<b>先标后发</b>：中间崩了是"玩家少领一次"（可补），反过来则是"同一份奖励发两次"（要回收）。
     *
     * @param playerId 领取人
     * @return 四种结果之一；只有 {@link GoalClaimResult#CLAIMED} 时调用方才该发钱
     */
    GoalClaimResult claimServerGoal(String playerId);

    /**
     * 两种存储共用的判定与标记（放在端口而不是任何一份实现里，与 {@link #applyKills} 同一条理由）。
     *
     * <p><b>它只改传入的那块板子，不落盘</b> —— 写回是调用方（存储层临界区内）的责任。
     */
    static GoalClaimResult applyGoalClaim(WarScoreBoard board, String playerId) {
        if (board == null) {
            return GoalClaimResult.NO_WAR;
        }
        if (!board.serverGoalReached()) {
            return GoalClaimResult.NOT_REACHED;
        }
        return board.claimServerGoal(playerId) ? GoalClaimResult.CLAIMED : GoalClaimResult.ALREADY_CLAIMED;
    }

    /**
     * 测试辅助：清空。
     */
    void clear();
}
