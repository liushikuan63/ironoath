package com.ironoath.web.store.mongo;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

/**
 * 职责：确保 Mongo 侧的索引存在（幂等，已存在不会重建）。
 * 依赖：Spring Data MongoDB。
 *
 * <p>索引必须在启动时确保存在，原因有二：
 * <ol>
 *   <li>{@code deviceId} 唯一索引是「同设备只建一个号」的<b>唯一</b>保证。
 *       索引缺失时 {@code insertIfAbsent} 会静默退化成「永远插入成功」，
 *       一个设备能建出无数个号 —— 这是刷资源的直接入口。</li>
 *   <li>{@code expireAt} 的 TTL 索引负责回收幂等键。没有它，request_id 集合会无限膨胀。</li>
 * </ol>
 *
 * <p><b>为什么单独一个类而不是写在 {@code MongoStoreConfig} 的 bean 里</b>：
 * 契约测试（{@code MongoPlayerStoreContractTest}）必须跑到<b>同一份</b>索引定义 ——
 * "insertIfAbsent 只有一个赢家"这条断言成立的前提就是这个唯一索引存在。
 * 测试若自己抄一遍 {@code ensureIndex}，那它验的是抄件，不是生产会建的那个。
 * 靠 {@code spring.data.mongodb.auto-index-creation} 或注解隐式创建都太脆弱
 * （默认为 false，且注解在 record 上的传播行为容易踩坑），所以显式创建并把结果打进日志。
 */
public final class MongoIndexes {

    private static final Logger LOG = LoggerFactory.getLogger(MongoIndexes.class);

    private static final String INDEX_DEVICE_ID = "uk_player_device_id";
    private static final String INDEX_REQUEST_ID_TTL = "ttl_request_id_expire_at";

    private MongoIndexes() {
    }

    /** @return 人类可读的就绪说明，便于调用方打进启动日志 */
    public static String ensure(MongoTemplate mongo) {
        IndexOperations playerIndexes = mongo.indexOps(PlayerDocument.COLLECTION);
        String deviceIdIndex = playerIndexes.ensureIndex(new Index()
                .on("deviceId", Sort.Direction.ASC)
                .unique()
                .named(INDEX_DEVICE_ID));
        IndexOperations requestIdIndexes = mongo.indexOps(RequestIdDocument.COLLECTION);
        String ttlIndex = requestIdIndexes.ensureIndex(new Index()
                .on("expireAt", Sort.Direction.ASC)
                .expire(0L, TimeUnit.SECONDS)
                .named(INDEX_REQUEST_ID_TTL));
        // 抽卡日志：没有这个复合索引，"查某人最近 90 天的全部抽取记录"会退化成整集合扫，
        // 而这条查询是监管来查时必须当场答得出来的（拿不出来就等于没有日志）
        IndexOperations gachaLogIndexes = mongo.indexOps(GachaLogBatchDocument.COLLECTION);
        String drawnAtIndex = gachaLogIndexes.ensureIndex(new Index()
                .on("playerId", Sort.Direction.ASC)
                .on("lastDrawnAt", Sort.Direction.DESC)
                .named(GachaLogBatchDocument.INDEX_PLAYER_DRAWN_AT));
        // 行军：列表是「按玩家过滤 + 按出发时刻排序」，viewport 是「按起点或终点的 chunk 键查」。
        // 三条都缺的话，每次客户端拉地图都要整集合扫一遍行军（在途数量随玩家数线性增长）
        IndexOperations marchIndexes = mongo.indexOps(MarchDocument.COLLECTION);
        String byPlayer = marchIndexes.ensureIndex(new Index()
                .on("playerId", Sort.Direction.ASC)
                .on("state.startAt", Sort.Direction.ASC)
                .named("idx_player_id_state_start_at"));
        String byFromChunk = marchIndexes.ensureIndex(new Index()
                .on("fromChunkKey", Sort.Direction.ASC)
                .named("idx_from_chunk_key"));
        String byToChunk = marchIndexes.ensureIndex(new Index()
                .on("toChunkKey", Sort.Direction.ASC)
                .named("idx_to_chunk_key"));
        // 支付订单：补单队列、负债总额、超时作废三条查询的过滤条件都是
        // state.status（外加作废那条的 createdAt 范围），一个复合索引同时覆盖三者。
        // 缺它的话每一次下单都要整表扫支付表 —— 而支付表是唯一一张"只会变长、永不被清理"的表。
        // 曾经写着"刻意不建 {playerId}：订单存储里没有任何按玩家查的入口"。**这句已被本轮推翻**：
        // 未成年月度限额新增了 paidCentsSince(playerId, 月初)，每次下单都会跑一次 ——
        // 没有这条索引，等于每次点"充值"都要扫一遍订单表（而订单表只增不删）
        IndexOperations payOrderIndexes = mongo.indexOps(PayOrderDocument.COLLECTION);
        String byStatusAndCreatedAt = payOrderIndexes.ensureIndex(new Index()
                .on("state.status", Sort.Direction.ASC)
                .on("state.createdAt", Sort.Direction.ASC)
                .named("idx_state_status_state_created_at"));
        String byPlayerAndPaidAt = payOrderIndexes.ensureIndex(new Index()
                .on("playerId", Sort.Direction.ASC)
                .on("state.paidAt", Sort.Direction.ASC)
                .named("idx_player_id_state_paid_at"));
        // 战报是全游戏长得最快的一张表（一场战斗给攻守双方各一份），而"我的战报"列表
        // 是每次进页面都会发的查询：没有 {ownerId, createdAt} 就是整集合扫。
        // 第二条给惰性清理用：purgeExpired 每次列表与详情入口都会触发，扫的是到期时刻这一段。
        // 列表还有一个 reportId 同刻定序的次序键没进索引 —— 于是 Mongo 会在"同一玩家"这一小撮
        // 文档里做一次内存排序（受 BATTLE_REPORT_TTL_SECONDS 约束，条数有上限），
        // 为它再加一个索引键不值：那样每次写入要多维护一个索引，而战报是纯追加表
        IndexOperations reportIndexes = mongo.indexOps(BattleReportDocument.COLLECTION);
        String byOwner = reportIndexes.ensureIndex(new Index()
                .on("ownerId", Sort.Direction.ASC)
                .on(BattleReportDocument.FIELD_CREATED_AT, Sort.Direction.DESC)
                .named("idx_owner_id_report_created_at"));
        String byExpiry = reportIndexes.ensureIndex(new Index()
                .on(BattleReportDocument.FIELD_EXPIRES_AT, Sort.Direction.ASC)
                .named("idx_report_expires_at"));
        // 国家表只有个位数行，索引在这里不是为了快，是为了**拦住一种静默的错**：
        // name 唯一索引挡住"两个国家共用一个国名"（没有它，findByName 只会回最后写入的那个，
        // 前一个国家从名字上消失而没有任何地方报错）；memberAllianceIds 是多键索引，
        // 供"某联盟属于哪国"查询 —— 每一次国家请求都要先走它
        IndexOperations nationIndexes = mongo.indexOps(NationDocument.COLLECTION);
        String uniqueName = nationIndexes.ensureIndex(new Index()
                .on("name", Sort.Direction.ASC)
                .unique()
                .named("uk_nation_name"));
        String byMemberAlliance = nationIndexes.ensureIndex(new Index()
                .on("memberAllianceIds", Sort.Direction.ASC)
                .named("idx_member_alliance_ids"));
        // 赛季账本：归档与申诉还原都是"给我这一季的全部记录（按玩家稳定顺序）"，
        // seasonIds() 的 distinct 也走这个索引的前缀。
        // 刻意不建 {playerId}：跨季查荣耀走的是逐季按 _id 点查（季数是归档保留数，个位数），
        // 为一个点查再养一个索引不划算
        IndexOperations ledgerIndexes = mongo.indexOps(SeasonLedgerDocument.COLLECTION);
        String bySeasonAndPlayer = ledgerIndexes.ensureIndex(new Index()
                .on("seasonId", Sort.Direction.ASC)
                .on("playerId", Sort.Direction.ASC)
                .named("idx_season_id_player_id"));
        // 赛季榜：读整榜是「本季本榜、按分数降序」（结算与申诉），rankOf 是「比我分数高的人数」，
        // 复合索引的前两段正好是这两条查询的过滤条件 —— 一条索引同时服务两者。
        // 快照那份是整份内嵌的只读文档、按 _id 点查，不需要额外索引。
        IndexOperations seasonBoardIndexes = mongo.indexOps(SeasonBoardDocument.COLLECTION);
        String bySeasonBoardScore = seasonBoardIndexes.ensureIndex(new Index()
                .on("seasonId", Sort.Direction.ASC)
                .on("board", Sort.Direction.ASC)
                .on("score", Sort.Direction.DESC)
                .named("idx_season_board_score"));
        // 每日快照（B23 裁决②）：按 _id 点查那一路不需要索引，这条服务的是另外两条 ——
        // 「这一季这张榜都拍过哪些天」（dailyDays，按 dayKey 升序）与归档清理（按 seasonId 前缀删）。
        // 两者都是 index 的前缀，一条索引同时覆盖。
        IndexOperations seasonDailyIndexes = mongo.indexOps(SeasonDailyBoardDocument.COLLECTION);
        String bySeasonDailyDay = seasonDailyIndexes.ensureIndex(new Index()
                .on("seasonId", Sort.Direction.ASC)
                .on("board", Sort.Direction.ASC)
                .on("dayKey", Sort.Direction.ASC)
                .named("idx_season_daily_day"));
        // 世界：world_city 的 coordKey **必须唯一** —— 一个玩家一份文档的 design 之所以安全，
        // 全靠这条索引挡住"两家人落在同一格"（内存版靠 ConcurrentHashMap 的 putIfAbsent 达到同效）。
        // 缺了它，mongo 模式下会出现两城叠一格、viewport 只下发其一，而写入侧一次都没报错。
        // world_cell 按 chunk 查（每次组装视野都要问一遍）；scout_report 两条：我的情报列表 + 过期清理。
        // chunk 版本与迷雾都以自身键为 _id，不需要额外索引。
        IndexOperations worldCityIndexes = mongo.indexOps(WorldCityDocument.COLLECTION);
        String uniqueCoord = worldCityIndexes.ensureIndex(new Index()
                .on("coordKey", Sort.Direction.ASC)
                .unique()
                .named(WorldCityDocument.INDEX_COORD_KEY));
        String byCellChunk = mongo.indexOps(WorldCellDocument.COLLECTION).ensureIndex(new Index()
                .on("chunkKey", Sort.Direction.ASC)
                .named("idx_world_cell_chunk_key"));
        IndexOperations scoutIndexes = mongo.indexOps(ScoutReportDocument.COLLECTION);
        String myReports = scoutIndexes.ensureIndex(new Index()
                .on("scoutPlayerId", Sort.Direction.ASC)
                .on(ScoutReportDocument.FIELD_CREATED_AT, Sort.Direction.DESC)
                .named("idx_scout_player_id_created_at"));
        String reportExpiry = scoutIndexes.ensureIndex(new Index()
                .on(ScoutReportDocument.FIELD_EXPIRES_AT, Sort.Direction.ASC)
                .named("idx_report_expires_at_scout"));
        // 埋点是全服写入量最大的一张表：{playerId, serverTs} 给排查看板那条"某人最近 N 条"，
        // {serverTs} 给按保留期清理（没有它，每次清理都是整集合扫，而这张表按 30 天算会有几千万条）。
        // 崩溃表以 traceId 为 _id（幂等键本身），只需要清理走 serverTs 的索引。
        IndexOperations trackIndexes = mongo.indexOps(TrackEventDocument.COLLECTION);
        String byTrackPlayer = trackIndexes.ensureIndex(new Index()
                .on("playerId", Sort.Direction.ASC)
                .on("serverTs", Sort.Direction.DESC)
                .named("idx_player_id_server_ts"));
        String trackExpiry = trackIndexes.ensureIndex(new Index()
                .on("serverTs", Sort.Direction.ASC)
                .named("idx_track_event_server_ts"));
        String crashExpiry = mongo.indexOps(TrackCrashDocument.COLLECTION).ensureIndex(new Index()
                .on("serverTs", Sort.Direction.ASC)
                .named("idx_track_crash_server_ts"));
        // 邮件：{playerId, mail.createdAt} 服务"每次进面板都发"的那条我的收件箱（B12 §2），
        // 排序键在子文档里，所以索引也建在子文档路径上（与文档级冗余列无关）；
        // {expireAt} 服务惰性清理 —— 没有它，每次有人打开邮箱都要整集合扫一遍
        IndexOperations mailIndexes = mongo.indexOps(MailDocument.COLLECTION);
        String byMailPlayer = mailIndexes.ensureIndex(new Index()
                .on(MailDocument.FIELD_PLAYER_ID, Sort.Direction.ASC)
                .on(MailDocument.FIELD_CREATED_AT, Sort.Direction.DESC)
                .named("idx_mail_player_id_created_at"));
        String mailExpiry = mailIndexes.ensureIndex(new Index()
                .on(MailDocument.FIELD_EXPIRE_AT, Sort.Direction.ASC)
                .named("idx_mail_expire_at"));
        // 补偿台账：唯一的读路径是运维面板上那条「还没处理的、最旧的 N 笔」，
        // 它每次都带 resolvedAt 为空这个条件并按 createdAt 排序 —— 没有索引就是整集合扫 + 内存排序。
        // 只建这一条：已处理的那些今天没有任何查询入口（台账按 _id 点查走主键），
        // 多一个索引只是让每次记账多维护一份。
        String compensationPending = mongo.indexOps(RewardCompensationDocument.COLLECTION)
                .ensureIndex(new Index()
                        .on(RewardCompensationDocument.FIELD_RESOLVED_AT, Sort.Direction.ASC)
                        .on(RewardCompensationDocument.FIELD_CREATED_AT, Sort.Direction.ASC)
                        .named("idx_reward_compensation_resolved_at_created_at"));
        // 活动进度：一个玩家一份文档，_id 就是 playerId，所以点查不需要额外索引；
        // 但没有索引时"这个服的进度分布"这类运营查询会整集合扫，
        // 而 windowStart 是那些查询唯一会过滤的字段（哪一轮、转没转过去）
        IndexOperations activityIndexes = mongo.indexOps(ActivityProgressDocument.COLLECTION);
        String byActivityWindow = activityIndexes.ensureIndex(new Index()
                .on("entries.windowStart", Sort.Direction.ASC)
                .named("idx_activity_window_start"));
        // 社交：三条唯一索引是"名字/标签全局唯一"在并发下唯一还成立的保证
        // （小队名、联盟名、联盟标签）。members.playerId 服务"查我在哪个组织"；
        // rally 两条服务"本组织进行中"与"到期扫描"。聊天/玩家事件/帮助请求都按 _id 点查，
        // 入盟申请另按 allianceId 数一次红点，除此之外不需要额外索引。
        IndexOperations squadIndexes = mongo.indexOps(SquadDocument.COLLECTION);
        String uniqueSquadName = squadIndexes.ensureIndex(new Index()
                .on("name", Sort.Direction.ASC)
                .unique()
                .named("uk_social_squad_name"));
        String bySquadMember = squadIndexes.ensureIndex(new Index()
                .on("members.playerId", Sort.Direction.ASC)
                .named("idx_social_squad_member"));
        IndexOperations allianceIndexes = mongo.indexOps(AllianceDocument.COLLECTION);
        String uniqueAllianceName = allianceIndexes.ensureIndex(new Index()
                .on("name", Sort.Direction.ASC)
                .unique()
                .named("uk_social_alliance_name"));
        String uniqueAllianceTag = allianceIndexes.ensureIndex(new Index()
                .on("tag", Sort.Direction.ASC)
                .unique()
                .named("uk_social_alliance_tag"));
        String byAllianceMember = allianceIndexes.ensureIndex(new Index()
                .on("members.playerId", Sort.Direction.ASC)
                .named("idx_social_alliance_member"));
        IndexOperations rallyIndexes = mongo.indexOps(RallyDocument.COLLECTION);
        String byRallyGroup = rallyIndexes.ensureIndex(new Index()
                .on("groupId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)
                .named("idx_social_rally_group_status"));
        String byRallyDue = rallyIndexes.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("prepareUntil", Sort.Direction.ASC)
                .named("idx_social_rally_status_prepare"));
        String byApplicationAlliance = mongo.indexOps(SocialApplicationDocument.COLLECTION)
                .ensureIndex(new Index()
                        .on("allianceId", Sort.Direction.ASC)
                        .named("idx_social_application_alliance"));        // 行军到期扫描：每条 dueBefore 都是 "dueAt <= now 按 dueAt 升序取前 N 条"，
        // 给 dueAt 一条索引即可；_id 是 marchId，点查/删除走主键。
        String byMarchDueAt = mongo.indexOps(MarchDueDocument.COLLECTION).ensureIndex(new Index()
                .on("dueAt", Sort.Direction.ASC)
                .named("idx_march_due_at"));        String summary = PlayerDocument.COLLECTION + ".{deviceId} → " + deviceIdIndex
                + "；" + RequestIdDocument.COLLECTION + ".{expireAt} → " + ttlIndex
                + "；" + GachaLogBatchDocument.COLLECTION + ".{playerId,lastDrawnAt} → " + drawnAtIndex
                + "；" + MarchDocument.COLLECTION + " → " + byPlayer + " / " + byFromChunk
                + " / " + byToChunk
                + "；" + PayOrderDocument.COLLECTION + ".{state.status,state.createdAt} → "
                + byStatusAndCreatedAt
                + "；" + BattleReportDocument.COLLECTION + " → " + byOwner + " / " + byExpiry
                + "；" + NationDocument.COLLECTION + " → " + uniqueName + " / " + byMemberAlliance
                + "；" + SeasonLedgerDocument.COLLECTION + ".{seasonId,playerId} → "
                + bySeasonAndPlayer
                + "；" + SeasonBoardDocument.COLLECTION + ".{seasonId,board,score} → "
                + bySeasonBoardScore
                + "；" + SeasonDailyBoardDocument.COLLECTION + ".{seasonId,board,dayKey} → "
                + bySeasonDailyDay
                + "；" + WorldCityDocument.COLLECTION + " → " + uniqueCoord
                + "；" + WorldCellDocument.COLLECTION + " → " + byCellChunk
                + "；" + ScoutReportDocument.COLLECTION + " → " + myReports + " / " + reportExpiry
                + "；" + TrackEventDocument.COLLECTION + " → " + byTrackPlayer + " / " + trackExpiry
                + "；" + TrackCrashDocument.COLLECTION + " → " + crashExpiry
                + "；" + MailDocument.COLLECTION + " → " + byMailPlayer + " / " + mailExpiry
                + "；" + RewardCompensationDocument.COLLECTION + " → " + compensationPending
                + "；" + ActivityProgressDocument.COLLECTION + " → " + byActivityWindow
                + "；" + SquadDocument.COLLECTION + " → " + uniqueSquadName + " / " + bySquadMember
                + "；" + AllianceDocument.COLLECTION + " → " + uniqueAllianceName + " / "
                + uniqueAllianceTag + " / " + byAllianceMember
                + "；" + RallyDocument.COLLECTION + " → " + byRallyGroup + " / " + byRallyDue
                + "；" + SocialApplicationDocument.COLLECTION + " → " + byApplicationAlliance
                + "；" + MarchDueDocument.COLLECTION + ".{dueAt} → " + byMarchDueAt;
        LOG.info("Mongo 索引就绪：{}", summary);
        return summary;
    }
}
