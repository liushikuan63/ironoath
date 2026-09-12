package com.ironoath.web.social;

import java.util.List;
import java.util.Optional;

import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Squad;

/**
 * 职责：B10 社交域存储端口 —— 小队、联盟、入盟申请、帮助请求、社交事件、聊天记录、集结。
 * 依赖：game-core 的 {@link Squad} / {@link Alliance} / {@link Rally}。
 *
 * <p><b>为什么端口在 game-web 而不在 game-core</b>：与 {@code NationStore}、{@code BattleReportStore}
 * 同一条理由 —— core 里没有任何逻辑需要「列出全部联盟」或「扫描到期集结」，那是服务层的诉求。
 * 端口留在使用它的那一层，core 不为此多一个没人用的抽象。
 *
 * <p><b>为什么六个概念放在一个端口里而不是六个 Store</b>：它们的操作全都是「按 playerId 找组织，
 * 再改组织状态」，而 B10 的每个用例都同时触及其中三四个（创建联盟要查申请、写事件、发聊天）。
 * 拆成六个端口会让服务层每次操作都在六个 Bean 之间搬数据，而它们的生命周期完全一致。
 * Mongo 实现内部按集合拆分（见 {@code MongoSocialStore}），端口这一层不拆。
 *
 * <p><b>读出来的是副本，调用方改完必须写回</b>：{@link Squad} / {@link Alliance} / {@link Rally}
 * 都是可变对象，两套实现都不得把库里的引用直接交出去 —— 否则「忘记 {@code save}」在内存实现下
 * 完全看不出来，换到 Mongo 就是一次静默丢档。{@code SocialStoreEquivalenceTest} 在两侧钉住这条：
 * 读一次、就地改、不 save，再读必须看不到那笔改动。这条纪律与 {@code NationStore}、四个版本化仓储
 * 完全一致（见收口清单 #16 与 #54）。
 *
 * <p><b>并发口径</b>：{@link Alliance} 自带 {@code version}（每次状态变化推进），因此
 * {@link #saveAlliance} 的 Mongo 实现按版本做乐观锁；同名 / 同标签 / 同一入盟申请靠唯一索引
 * 原子拒绝。{@link Squad} / {@link Rally} 今天没有版本号，存储侧只能做整档替换 ——
 * 这与内存实现的粗锁不是同一强度，属于在册欠账，不要把它当作已经解决的并发保证。
 */
public interface SocialStore {

    /** 一条聊天记录。 */
    record ChatMessage(String messageId, String channel, String senderId, String senderName,
                       String content, long sentAt) {
    }

    /**
     * 事件的可响应窗口：三小时前的求援已经支援不上了（B10 验收 12：过期事件置灰不可跳转）。
     *
     * <p>放在端口上是因为它有两个使用者（社交服务与求助登记器），而"多久算过期"必须只有一处。
     */
    long EVENT_TTL_MILLIS = 3L * 3600L * 1000L;

    /** 一条社交事件（推送与离线补偿共用同一结构，B10 验收 5 / 12）。 */
    record SocialEvent(String eventId, String type, String title, String body,
                       Long coordX, Long coordY, String relatedId, long occurredAt,
                       long expireAt) {
        /** 是否已过期。过期事件必须置灰且不可跳转（与 B07 侦查情报同一条纪律）。 */
        public boolean expiredAt(long now) {
            return expireAt > 0L && now >= expireAt;
        }
    }

    /**
     * 一条待帮助请求。
     *
     * @param targetKey 加速要落到哪个目标上（建筑的 instanceId 等）。<b>与 targetDesc 分工不同</b>：
     *                  后者是给人看的文案，前者是给代码用的定位符 —— 早先只有 targetDesc，
     *                  于是"帮了忙"这件事根本找不到要加速的那栋楼
     */
    record HelpRequest(String requestId, String fromPlayerId, String fromPlayerName,
                       String kind, String targetKey, String targetDesc,
                       long finishAt, int helpedCount) {
    }

    // ---------- 小队 ----------

    /**
     * 写入或更新一支小队。
     *
     * @return 内存实现恒为 true；Mongo 实现返回是否真的写入（保留签名与既有调用方兼容）。
     */
    boolean saveSquad(Squad squad);

    Optional<Squad> squadOf(String playerId);

    Optional<Squad> squadById(String squadId);

    boolean squadNameTaken(String name);

    /** 成员离开小队后清掉他的反查索引；小队解散时清掉全部成员与名字。 */
    void unbindSquadMember(String squadId, String playerId);

    // ---------- 联盟 ----------

    void saveAlliance(Alliance alliance);

    Optional<Alliance> allianceOf(String playerId);

    /**
     * 全部联盟，按 id 升序（稳定顺序）。
     *
     * <p>给 Bot 的入盟申请挑目标用（收口清单 #94）：它需要一个"世界上有哪些联盟"的读法，
     * 而按 id 排序是为了让同一份库存上每次跑出来的结果可复现 —— 随机顺序会让
     * "为什么这个 Bot 申请了那个联盟"变成不可复现的问题。
     */
    List<Alliance> allAlliances();

    Optional<Alliance> allianceById(String allianceId);

    boolean allianceNameTaken(String name);

    boolean allianceTagTaken(String tag);

    void unbindAllianceMember(String allianceId, String playerId);

    void removeAlliance(Alliance alliance);

    // ---------- 解散保护期（验收 7） ----------

    /** 只延长不缩短：连续解散两次不该让第二次的保护期覆盖掉第一次更长的剩余时间。 */
    void protectFromCreating(String playerId, long until);

    long disbandProtectedUntil(String playerId);

    // ---------- 入盟申请 ----------

    /** @return false 表示这名玩家已经申请过这个联盟（同一条申请只算一次）。 */
    boolean addApplication(String allianceId, String playerId);

    boolean removeApplication(String allianceId, String playerId);

    boolean hasApplication(String allianceId, String playerId);

    /** 某个联盟的待处理申请数（红点数据源之一）。 */
    int pendingApplicationCount(String allianceId);

    // ---------- 聊天 ----------

    /**
     * 追加一条消息。
     *
     * @param historyCap 每个频道保留的条数上限。来源 global.CHAT_LOCAL_HISTORY_MAX ——
     *                   超出丢最旧：聊天记录不是账目，旧消息的价值随时间迅速衰减
     * @throws IllegalStateException 同一频道里出现了重复的 messageId
     *
     * <p><b>为什么撞号要响而不是悄悄收下</b>：翻页游标就是按 id 定位的（{@link #chat}），
     * 同一个 id 出现两次意味着"从这条往前翻"永远落在第一条上 —— 第二条连同它之前的一起被跳过。
     * 那是一句静默丢失的聊天，玩家只会说"我朋友发的消息我这儿没显示"，而日志里什么都没有。
     */
    void appendChat(String channelKey, ChatMessage message, int historyCap);

    /**
     * 取某频道的最近消息，按时间升序。
     *
     * <p><b>给了游标却在这段历史里找不到 ⇒ 空页</b>，而不是退回到"最新一页"：
     * 两种找不到（已被保留窗口淘汰、以及根本是别处的 id）要的都是同一个答案。
     * 退化成最新一页的表现是"往下翻反而看到刚才那一屏"，客户端会把它当新消息追加，
     * 于是同一句话在界面上出现两次。
     *
     * @param beforeMessageId 游标；null 表示从最新往回取
     */
    List<ChatMessage> chat(String channelKey, String beforeMessageId, int limit);

    boolean hasMore(String channelKey, String beforeMessageId, int limit);

    // ---------- 社交事件（验收 5 / 12） ----------

    void pushEvent(String playerId, SocialEvent event);

    List<SocialEvent> unreadEvents(String playerId);

    /**
     * 标记已读。空列表表示全部标记。
     *
     * @return 实际标记掉的条数
     */
    int ackEvents(String playerId, List<String> eventIds);

    // ---------- 集结（B10 §5） ----------

    /** 写入或更新一次集结。Rally 是可变对象，调用方改完必须写回来。 */
    void saveRally(Rally rally);

    Optional<Rally> rallyOf(String rallyId);

    /**
     * 某个组织（小队 / 联盟 / 国家）里进行中的集结，按创建时刻升序。
     *
     * <p>只返回 PREPARING 的：已出发/已取消的集结留在面板上没有意义，
     * 而「点进去发现早就出发了」比「看不到」更让人困惑。
     */
    List<Rally> preparingRalliesOf(String groupId);

    /**
     * 全部<b>已到出发时刻但仍在准备中</b>的集结，按创建时刻升序。
     *
     * <p><b>为什么需要一次跨组织的扫描</b>：出发是请求驱动的（服务端不跑定时器），
     * 触发点是任意一次行军到期扫描 —— 而发起扫描的那个玩家跟这些集结毫无关系。
     * 只按组织查就得先知道「有哪些组织有集结」，那等于再维护一份会漂移的索引。
     */
    List<Rally> dueRallies(long now);

    // ---------- 帮助请求 ----------

    void putHelpRequest(HelpRequest request);

    Optional<HelpRequest> helpRequest(String requestId);

    /**
     * 「谁和我在同一个组织里」：小队队友 + 联盟盟友，去重、不含自己。
     *
     * <p><b>为什么这条查询住在存储而不是某个服务里</b>：它读的就是本端口持有的两张成员表，
     * 而它同时被两条路径需要（求助请求的广播、以及其它通知）；放在服务里会让另一个服务
     * 为了发一条通知去依赖那个服务 —— {@code CityAppService → SocialAppService} 正是这么成环的。
     */
    List<String> peerPlayerIds(String playerId);

    void removeHelpRequest(String requestId);

    /** 全部待帮助请求。红点与「一键帮助」都基于它。 */
    List<HelpRequest> helpRequests();

    /** 帮助次数 +1。 */
    void markHelped(String requestId);

    // ---------- 测试与运维 ----------

    int counts();

    /** 清空全部状态。单测在 {@code @BeforeEach} 里调用。 */
    void clear();
}