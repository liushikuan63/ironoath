package com.ironoath.web.ops;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 职责：埋点与崩溃上报的存储端口（B16 §3 服务端落库、§6 崩溃上报，验收 9）。
 * 依赖：无。
 *
 * <p><b>为什么端口在 game-web 而不在 game-core</b>：与 {@code BattleReportStore} 同一个理由 ——
 * 存的是协议 DTO 形状的观测数据，game-core 里没有任何逻辑需要读它。
 * 唯一在 core 的是攒批策略（{@code TrackBatcher}），那是纯策略，不碰存储。
 *
 * <p><b>落库必须批量</b>：{@link #saveBatch} 而不是 {@code save} 一条。
 * 一次战斗会产生十几个事件，逐条 insert 就是十几次 MongoDB 往返；
 * 而 B16 禁止项写死了「不要在主线程做同步 IO」—— 十次往返攒成一次，
 * 是把这条禁止项从「靠异步线程池兜住」变成「根本就不产生那么多 IO」。
 *
 * <p><b>埋点丢数据是可接受的，账目丢数据不是</b>：所以本端口不提供事务语义，
 * 实现方可以为了吞吐牺牲持久性（例如先写内存再批量刷盘）。
 * 但这条宽容<b>不适用于崩溃上报</b> —— 崩溃是低频事件，
 * 而它恰恰是线上唯一能解释「玩家为什么再也进不来」的证据，实现方必须同步落库。
 */
public interface TrackEventStore {

    /**
     * 批量写入埋点事件。
     *
     * @return 实际写入条数。小于入参条数表示实现方丢弃了一部分（例如超出保留窗口），
     *         调用方据此填 {@code TrackBatchResp.failed}
     */
    int saveBatch(List<TrackRecord> records);

    /** 某玩家最近的事件，按服务端落库时刻倒序。排查看板用，limit 由调用方给（禁止全量返回）。 */
    List<TrackRecord> recentOf(String playerId, int limit);

    /** 已落库的事件总数。埋点健康度指标，也是单测断言「批量确实写进去了」的抓手。 */
    int eventCount();

    /**
     * 删掉 {@code serverTs} 早于 {@code cutoffMillis} 的事件，返回删除条数。
     *
     * <p><b>保留口径不是我发明的数字</b>：{@code global.TRACK_STORE_MAX_EVENTS} 的 {@code why}
     * 原文就写着「生产用 MongoDB，保留期按时间算而不是按条数算，且不得短于
     * {@code DASHBOARD_RETENTION_DAYS} 的最大值」。条数上限只是 dev 进程的堆兜底
     * （没有它一次长压测会把堆吃光，症状还会被读成"服务端内存泄漏"），
     * 而 D30 留存算不算得出来，取决于这里有没有被调用。
     *
     * <p>与 {@code purgeExpired}（战报）、{@code expireUnpaid}（订单）同一形状：
     * 由写路径惰性驱动，服务端不起定时器（B00 陷阱 2）。
     */
    int purgeOlderThan(long cutoffMillis);

    /**
     * 写入一条崩溃上报。<b>必须同步落库</b>，理由见类注释。
     *
     * @return 是否为新写入。同一 traceId 重复上报视为幂等（返回 false），
     *         因为客户端在崩溃后重启时可能补报同一条
     */
    boolean saveCrash(CrashRecord crash);

    /** 按 traceId 取崩溃记录（验收 9：后台能收到完整堆栈 + traceId）。 */
    Optional<CrashRecord> findCrash(String traceId);

    /**
     * 删掉早于 {@code cutoffMillis} 的崩溃记录，返回删除条数。
     *
     * <p>崩溃记录<b>永远不按条数上限丢弃</b>（内存版没有给它设 cap，这里同理）：
     * 大面积崩溃时最需要证据，而按条数淘汰恰恰会在崩溃最多的时候最先冲掉最早的现场。
     * 时间清理是安全的，因为它与"这次事故有多严重"无关。
     */
    int purgeCrashesOlderThan(long cutoffMillis);

    /**
     * 当前存储里的崩溃条数（内存版此前只有单测在读，提到端口是为了让 {@code /ops/crash/recent}
     * 能把 total 与 listed 分开回）。
     *
     * <p><b>崩溃记录没有条数上限</b>（见 {@link #purgeCrashesOlderThan}），所以这个数只随保留期清理下降；
     * 它突然变大有两种解释 —— 真出事了，或者有人把这个不要求身份的端点当靶子刷。
     */
    int crashCount();

    /**
     * 窗口内按<b>客户端版本</b>分组的崩溃条数（B16 §六 第一条：崩溃率必须按版本分组）。
     *
     * <p><b>为什么不复用 {@link #recentCrashes} 让调用方自己数</b>：那个方法带 limit，
     * 而"翻了第一页"不是"看到的全部" —— 拿被截断的样本算分组计数，
     * 症状恰恰是这条要防的那件事：某个版本明明在批量崩，看板上却看不出来。
     * 聚合必须在存储层做（Mongo 侧是一次 {@code $group}，不是把全表拉进堆）。
     *
     * @param sinceMillis 窗口起点（含），按 {@code serverTs} 判
     * @return 版本号 → 条数；<b>没带版本的崩溃归在空串这一键</b>，不静默丢弃
     */
    Map<String, Long> crashCountByVersion(long sinceMillis);

    /**
     * 窗口内某个事件按<b>参数值</b>分组的条数。崩溃率的分母（{@code startup} 的
     * {@code clientVersion}）走这一条，将来 §三 漏斗的任意一环按任意参数分组都走这一条 ——
     * 参数的取值集合是客户端字典决定的，服务端不认识它们，所以这里不枚举、只分组。
     *
     * <p>缺失与空串归同一组（空串键）：客户端把空值落成空串而不是 null（见
     * {@code TrackEvents.ts#trackParam}），分成两组会让"没带参数"看起来像两个不同的桶。
     *
     * @param sinceMillis 窗口起点（含），按 {@code serverTs} 判
     * @param eventName   事件名，精确匹配
     * @param paramKey    参数键
     */
    Map<String, Long> countEventsByParam(long sinceMillis, String eventName, String paramKey);

    /**
     * 某个事件最近的若干条，按 {@code serverTs} 倒序，最多 {@code limit} 条。
     *
     * <p><b>与 {@link #recentOf} 的分工</b>：那条按玩家查（"这个人刚才做了什么"），这条按事件名查
     * （"这轮启动自检到底成没成"）。后者要成立是因为客户端自检行只活在开发者工具的 Console 里，
     * 而 IDE 不把它落到任何文件 —— 不能读回来的"跑通"就只是一个人眼结论。
     *
     * @param limit 由调用方给（禁止全量返回：这张表按 30 天算是全服最大的一张）
     */
    List<TrackRecord> recentByName(String eventName, int limit);

    /**
     * 某个事件的总条数（与 {@link #recentByName} 的 limit 分开回）。
     *
     * <p>没有它，"listed=20、total 未知"就会被读成"一共就 20 条" —— 与 {@code pay/debt}
     * 把 total 与 listed 分开回是同一条理由。
     */
    int countByName(String eventName);

    /**
     * 最近的崩溃明细，按 {@code serverTs} 倒序，最多 {@code limit} 条。
     *
     * <p>返回的记录含完整堆栈；<b>是端点层决定不带出去</b>（见 {@code CrashListItem} 的说明），
     * 因为一条只读列表端点不该成为全服最大的响应。
     */
    List<CrashRecord> recentCrashes(int limit);

    /**
     * 分组键归一化：null 与空白一律成空串。
     *
     * <p><b>为什么在端口上做静态方法而不是各实现一份</b>：这是「没带版本号的崩溃算哪一组」
     * 这一条看板口径的唯一实现。两份实现的后果不是错，而是<b>同一份数据在 dev 与生产上分组不同</b>
     * —— 那正是最难查的那类差异：没人会去比对一个空串键和一个 null 键。
     */
    static String groupKey(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** 测试辅助：清空。 */
    void clear();

    /**
     * 一条已落库的埋点事件。
     *
     * @param name      事件名
     * @param playerId  玩家 id。<b>可空</b>：启动、登录前的事件还没有玩家身份，
     *                  而那批事件恰恰是「进都没进就走了」这一段漏斗的全部证据。
     *                  它是一等公民而不是 params 里的一个约定 key ——
     *                  留存与漏斗查询全部以玩家为键，靠字符串约定的话，
     *                  某个调用点把 key 写错时查询会静默返回空而不是报错
     * @param clientTs  客户端毫秒时间戳，仅用于同一批内部排序（铁律 5：时间以服务端为准）
     * @param serverTs  服务端落库时刻。<b>所有留存与时长口径都按它算</b>：客户端时钟能被玩家改
     * @param traceId   所属请求的 traceId，用于把「玩家说卡住了」还原成一条完整链路
     * @param params    事件参数
     */
    record TrackRecord(String name, String playerId, long clientTs, long serverTs, String traceId,
                       Map<String, String> params) {
        public TrackRecord {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * 一条崩溃记录。
     *
     * @param traceId       全链路 id（验收 9 要求它必须能对上服务端日志）
     * @param message       错误摘要
     * @param stack         完整堆栈（验收 9 要求「完整」）
     * @param clientVersion 崩溃时的客户端版本 —— 灰度期间按版本分组才看得出 5% 批次里崩溃率翻倍
     * @param sceneName     崩溃时所在场景，null 表示崩在场景切换之间
     * @param clientTs      客户端时刻
     * @param serverTs      服务端收到时刻。与 clientTs 之差就是该玩家的时钟偏移量
     */
    record CrashRecord(String traceId, String message, String stack, String clientVersion,
                       String sceneName, long clientTs, long serverTs) {
    }
}
