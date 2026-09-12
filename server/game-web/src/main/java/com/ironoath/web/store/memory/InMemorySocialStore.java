package com.ironoath.web.store.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Squad;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B10 社交域的内存存储 —— 小队、联盟、入盟申请、帮助请求、社交事件、聊天记录。
 * 依赖：game-core 的 social 领域对象。
 *
 * <p><b>与其它 InMemory*Store 同一个身份：本地开发与单测用，重启即丢</b>（见 SocialBeansConfig 的 WARN）。
 * 生产实现是 {@code MongoSocialStore}（2026-09-12 落地），两套实现<b>读出来都是副本</b>：
 * 调用方改完必须 save，忘记 save 在 dev 与生产上是同一个结果。
 *
 * <p><b>为什么把六个概念放进一个类而不是六个 Store</b>：它们的操作全都是「按 playerId 找组织，
 * 再改组织状态」，而 B10 的每个用例都同时触及其中三四个（创建联盟要查申请、写事件、发聊天）。
 * 拆成六个 Store 会让服务层每次操作都要在六个 Bean 之间搬数据，
 * 而它们的生命周期完全一致（都随玩家存档一起持久化）。
 * Mongo 实现（{@code MongoSocialStore}）按实际查询轴拆成 squad / alliance / rally / chat /
 * player / application / help_request 七个集合 —— 拆分依据是查询与并发边界，不是猜。
 *
 * <p><b>全部方法 synchronized</b>：B10 的写操作跨玩家（踢人、审核、帮助都会改别人的状态），
 * 玩家级锁挡不住这种跨玩家竞争。内存实现下用一把粗锁最简单也最不容易出错；
 * 与 Mongo 实现同一套 CAS：三个 {@code save} 都要求传"读到时的那一版"，
 * 对不上就抛乐观锁冲突（{@code requireExpectedVersion}），两套实现的判定与文案逐字相同。
 */
public final class InMemorySocialStore implements SocialStore {

    private final Map<String, Squad> squadsById = new LinkedHashMap<>();
    private final Map<String, String> squadIdByPlayer = new HashMap<>();
    private final Map<String, String> squadIdByName = new HashMap<>();

    private final Map<String, Alliance> alliancesById = new LinkedHashMap<>();
    private final Map<String, String> allianceIdByPlayer = new HashMap<>();

    /**
     * 集结（rallyId → Rally）。
     *
     * <p>集结刻意<b>不建「按组织查进行中集结」的索引</b>：一次集结的生命周期只有 10~30 分钟
     * （global.RALLY_PREPARE_MIN/MAX_SECONDS），而查询方（小队/联盟面板）本来就持有 groupId，
     * 逐条过滤在这个量级上比维护一个会漂移的索引便宜 —— 双索引最常见的 bug 是
     * 「集结已经取消了，索引里还挂着」，于是面板上永远有一个点不进去的集结。
     */
    private final Map<String, com.ironoath.core.social.Rally> rallies = new LinkedHashMap<>();
    private final Map<String, String> allianceIdByName = new HashMap<>();
    private final Map<String, String> allianceIdByTag = new HashMap<>();
    /** playerId → 解散保护期截止时刻（验收 7）。落在人身上而不是联盟上：联盟已经没了 */
    private final Map<String, Long> disbandProtectedUntil = new HashMap<>();
    /** "allianceId:playerId" → playerId。待审核的入盟申请 */
    private final Map<String, String> pendingApplications = new LinkedHashMap<>();

    /** channelKey → 消息队列。channelKey 形如 "WORLD" / "ALLIANCE:a1" / "SQUAD:s1" / "PRIVATE:a:b" */
    private final Map<String, Deque<ChatMessage>> chats = new HashMap<>();

    /** playerId → 未读事件。已读的由 ackEvents 移除，所以这里只存未读 */
    private final Map<String, List<SocialEvent>> unreadEvents = new HashMap<>();

    /** requestId → 帮助请求。完成后移除，所以这张表的大小被「当前正在进行中的升级/治疗」封顶 */
    private final Map<String, HelpRequest> helpRequests = new LinkedHashMap<>();

    // ---------- 小队 ----------

    @Override
    public synchronized long saveSquad(Squad squad, long expectedVersion) {
        // 名字唯一性在写入点再查一次：服务层的"先查后写"挡得住顺序调用，挡不住两个调用线程;
        // Mongo 侧同名会被唯一索引拒绝，两侧必须给出同一个结果，否则 dev 全绿、生产第一次撞名就炸
        String squadNameOwner = squadIdByName.get(squad.name());
        if (squadNameOwner != null && !squadNameOwner.equals(squad.id())) {
            throw new IllegalStateException("小队名已被其它小队占用：" + squad.name()
                    + "。名字是玩家查找小队的唯一入口，两支同名小队等于其中一支从名字上消失");
        }
        Squad stored = squadsById.get(squad.id());
        requireExpectedVersion("小队", squad.id(), stored == null ? null : stored.version(), expectedVersion);
        squadsById.put(squad.id(), squad.copy());
        squadIdByName.put(squad.name(), squad.id());
        for (String playerId : squad.memberIds()) {
            squadIdByPlayer.put(playerId, squad.id());
        }
        return squad.version();
    }

    @Override
    public synchronized Optional<Squad> squadOf(String playerId) {
        String squadId = squadIdByPlayer.get(playerId);
        return squadId == null ? Optional.empty() : Optional.ofNullable(squadsById.get(squadId)).map(Squad::copy);
    }

    @Override
    public synchronized Optional<Squad> squadById(String squadId) {
        return Optional.ofNullable(squadsById.get(squadId)).map(Squad::copy);
    }

    @Override
    public synchronized boolean squadNameTaken(String name) {
        return squadIdByName.containsKey(name);
    }

    /** 成员离开小队后清掉他的反查索引；小队解散时清掉全部成员与名字。 */
    @Override
    public synchronized void unbindSquadMember(String squadId, String playerId) {
        // 判据是「这个索引指向的是不是要解绑的那个小队」，而不是「playerId 等不等于索引值」——
        // 后者拿玩家 id 去比小队 id，永远为 false，于是索引从来清不掉：
        // 表现是被踢出小队的人打开面板仍然在队里，而他再点任何小队操作都会作用到旧小队上。
        // 用 squadId 比还能避免误删：玩家可能已经加入了别的小队，那时不该动他的新索引
        if (squadId.equals(squadIdByPlayer.get(playerId))) {
            squadIdByPlayer.remove(playerId);
        }
        Squad squad = squadsById.get(squadId);
        if (squad != null && squad.isDisbanded()) {
            squadsById.remove(squadId);
            squadIdByName.remove(squad.name(), squadId);
            for (String member : squad.memberIds()) {
                squadIdByPlayer.remove(member, squadId);
            }
        }
    }

    // ---------- 联盟 ----------

    @Override
    public synchronized long saveAlliance(Alliance alliance, long expectedVersion) {
        String allianceNameOwner = allianceIdByName.get(alliance.name());
        if (allianceNameOwner != null && !allianceNameOwner.equals(alliance.id())) {
            throw new IllegalStateException("联盟名已被其它联盟占用：" + alliance.name()
                    + "。联盟名是玩家查找联盟的唯一入口，两个联盟共用一名等于其中一个从名字上消失");
        }
        String allianceTagOwner = allianceIdByTag.get(alliance.tag());
        if (allianceTagOwner != null && !allianceTagOwner.equals(alliance.id())) {
            throw new IllegalStateException("联盟标签已被其它联盟占用：" + alliance.tag()
                    + "。标签是外交与战报里指代联盟的短标识，重复会让指代出现歧义");
        }
        Alliance stored = alliancesById.get(alliance.id());
        requireExpectedVersion("联盟", alliance.id(), stored == null ? null : stored.version(), expectedVersion);
        alliancesById.put(alliance.id(), alliance.copy());
        allianceIdByName.put(alliance.name(), alliance.id());
        allianceIdByTag.put(alliance.tag(), alliance.id());
        for (String playerId : alliance.memberIds()) {
            allianceIdByPlayer.put(playerId, alliance.id());
        }
        return alliance.version();
    }

    @Override
    public synchronized Optional<Alliance> allianceOf(String playerId) {
        String allianceId = allianceIdByPlayer.get(playerId);
        return allianceId == null ? Optional.empty() : Optional.ofNullable(alliancesById.get(allianceId)).map(Alliance::copy);
    }

    /**
     * 全部联盟，按 id 升序（稳定顺序）。
     *
     * <p>给 Bot 的入盟申请挑目标用（收口清单 #94）：它需要一个"世界上有哪些联盟"的读法，
     * 而按 id 排序是为了让同一份库存上每次跑出来的结果可复现 —— 随机顺序会让
     * "为什么这个 Bot 申请了那个联盟"变成不可复现的问题。
     */
    @Override
    public synchronized List<Alliance> allAlliances() {
        List<Alliance> out = new java.util.ArrayList<>(alliancesById.size());
        for (Alliance alliance : alliancesById.values()) {
            out.add(alliance.copy());
        }
        out.sort(java.util.Comparator.comparing(Alliance::id));
        return out;
    }

    @Override
    public synchronized Optional<Alliance> allianceById(String allianceId) {
        return Optional.ofNullable(alliancesById.get(allianceId)).map(Alliance::copy);
    }

    @Override
    public synchronized boolean allianceNameTaken(String name) {
        return allianceIdByName.containsKey(name);
    }

    @Override
    public synchronized boolean allianceTagTaken(String tag) {
        return allianceIdByTag.containsKey(tag);
    }

    @Override
    public synchronized void unbindAllianceMember(String allianceId, String playerId) {
        allianceIdByPlayer.remove(playerId, allianceId);
    }

    @Override
    public synchronized void removeAlliance(Alliance alliance) {
        alliancesById.remove(alliance.id());
        allianceIdByName.remove(alliance.name(), alliance.id());
        allianceIdByTag.remove(alliance.tag(), alliance.id());
        for (String playerId : alliance.memberIds()) {
            allianceIdByPlayer.remove(playerId, alliance.id());
        }
    }

    // ---------- 解散保护期（验收 7） ----------

    @Override
    public synchronized void protectFromCreating(String playerId, long until) {
        // 只延长不缩短：连续解散两次不该让第二次的保护期覆盖掉第一次更长的剩余时间
        disbandProtectedUntil.merge(playerId, until, Math::max);
    }

    @Override
    public synchronized long disbandProtectedUntil(String playerId) {
        return disbandProtectedUntil.getOrDefault(playerId, 0L);
    }

    // ---------- 入盟申请 ----------

    @Override
    public synchronized boolean addApplication(String allianceId, String playerId) {
        return pendingApplications.putIfAbsent(key(allianceId, playerId), playerId) == null;
    }

    @Override
    public synchronized boolean removeApplication(String allianceId, String playerId) {
        return pendingApplications.remove(key(allianceId, playerId)) != null;
    }

    @Override
    public synchronized boolean hasApplication(String allianceId, String playerId) {
        return pendingApplications.containsKey(key(allianceId, playerId));
    }

    /** 某个玩家的待处理申请数（红点数据源之一）。 */
    @Override
    public synchronized int pendingApplicationCount(String allianceId) {
        int count = 0;
        for (String applicationKey : pendingApplications.keySet()) {
            if (applicationKey.startsWith(allianceId + ":")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 乐观锁校验。两套实现必须给出<b>逐字相同</b>的判定与文案，否则等价测试只能测"都抛异常"，
     * 测不出"同一个冲突在生产上被说成另一回事"。
     *
     * @param storedVersion 库里当前版本；{@code null} 表示没有这条记录
     * @param expectedVersion 调用方读到的版本；{@code <= 0} 表示"我认为它还不存在"（建档）
     */
    private static void requireExpectedVersion(String what, String id, Long storedVersion,
                                               long expectedVersion) {
        if (expectedVersion <= 0L) {
            if (storedVersion != null) {
                throw new IllegalStateException(what + "已存在，不能用 expectedVersion<=0 建档：id=" + id
                        + "，存储版本=" + storedVersion);
            }
            return;
        }
        if (storedVersion == null) {
            throw new IllegalStateException(what + "不存在，无法按版本更新：id=" + id
                    + "，期望版本=" + expectedVersion);
        }
        if (storedVersion.longValue() != expectedVersion) {
            throw new IllegalStateException("乐观锁冲突：" + what + " id=" + id
                    + "，存储版本=" + storedVersion + "，提交版本=" + expectedVersion
                    + "。请重读后重试：这类改动跨玩家，PlayerLock 是按玩家的，拦不住这里");
        }
    }
    private static String key(String allianceId, String playerId) {
        return allianceId + ":" + playerId;
    }

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
    @Override
    public synchronized void appendChat(String channelKey, ChatMessage message, int historyCap) {
        Deque<ChatMessage> queue = chats.computeIfAbsent(channelKey, k -> new ArrayDeque<>());
        for (ChatMessage seen : queue) {
            if (seen.messageId().equals(message.messageId())) {
                throw new IllegalStateException("聊天消息 id 在同一个频道里重复了：channel="
                        + channelKey + " messageId=" + message.messageId()
                        + " —— id 必须由生成方保证唯一（见 SocialAppService.chatSend），撞了会静默丢消息");
            }
        }
        queue.addLast(message);
        while (queue.size() > historyCap) {
            queue.pollFirst();
        }
    }

    /**
     * 游标定位：返回"这条之前"的截止下标。
     *
     * <p><b>给了游标却在这段历史里找不到 ⇒ 截止到下标 0（没有更早的了）</b>，而不是退回到
     * "最新一页"。两种找不到（已被保留窗口淘汰、以及根本是别处的 id）要的都是同一个答案：
     * 空页 + {@code hasMore=false}。退化成最新一页的表现是"往下翻反而看到刚才那一屏"，
     * 客户端会把它当新消息追加，于是同一句话在界面上出现两次。
     */
    private static int cursorEnd(List<ChatMessage> all, String beforeMessageId) {
        if (beforeMessageId == null) {
            return all.size();
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).messageId().equals(beforeMessageId)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 取某频道的最近消息，按时间升序。
     *
     * @param beforeMessageId 游标；null 表示从最新往回取
     */
    @Override
    public synchronized List<ChatMessage> chat(String channelKey, String beforeMessageId, int limit) {
        Deque<ChatMessage> queue = chats.get(channelKey);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> all = new ArrayList<>(queue);
        int end = cursorEnd(all, beforeMessageId);
        int start = Math.max(0, end - limit);
        // 升序返回：客户端从上往下画，不需要再反转
        return Collections.unmodifiableList(new ArrayList<>(all.subList(start, end)));
    }

    @Override
    public synchronized boolean hasMore(String channelKey, String beforeMessageId, int limit) {
        Deque<ChatMessage> queue = chats.get(channelKey);
        if (queue == null) {
            return false;
        }
        // 与 chat 共用同一个游标解析：两处各算一遍的话，迟早一处说"没有更多了"而另一处还发一页
        int end = cursorEnd(new ArrayList<>(queue), beforeMessageId);
        return end > limit;
    }

    // ---------- 社交事件（验收 5 / 12） ----------

    @Override
    public synchronized void pushEvent(String playerId, SocialEvent event) {
        unreadEvents.computeIfAbsent(playerId, k -> new ArrayList<>()).add(event);
    }

    @Override
    public synchronized List<SocialEvent> unreadEvents(String playerId) {
        List<SocialEvent> events = unreadEvents.get(playerId);
        return events == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * 标记已读。空列表表示全部标记。
     *
     * @return 实际标记掉的条数
     */
    // ================= 集结（B10 §5） =================

    /** 写入或更新一次集结。Rally 是可变对象，调用方改完必须写回来。 */
    @Override
    public synchronized long saveRally(com.ironoath.core.social.Rally rally, long expectedVersion) {
        com.ironoath.core.social.Rally stored = rallies.get(rally.rallyId());
        requireExpectedVersion("集结", rally.rallyId(),
                stored == null ? null : stored.version(), expectedVersion);
        rallies.put(rally.rallyId(), rally.copy());
        return rally.version();
    }

    @Override
    public synchronized Optional<com.ironoath.core.social.Rally> rallyOf(String rallyId) {
        return Optional.ofNullable(rallies.get(rallyId)).map(com.ironoath.core.social.Rally::copy);
    }

    /**
     * 某个组织（小队 / 联盟 / 国家）里进行中的集结，按创建时刻升序。
     *
     * <p>只返回 PREPARING 的：已出发/已取消的集结留在面板上没有意义，
     * 而「点进去发现早就出发了」比「看不到」更让人困惑。
     */
    @Override
    public synchronized List<com.ironoath.core.social.Rally> preparingRalliesOf(String groupId) {
        List<com.ironoath.core.social.Rally> out = new ArrayList<>();
        for (com.ironoath.core.social.Rally rally : rallies.values()) {
            if (rally.groupId().equals(groupId)
                    && rally.status() == com.ironoath.core.social.Rally.Status.PREPARING) {
                out.add(rally.copy());
            }
        }
        out.sort(java.util.Comparator.comparingLong(com.ironoath.core.social.Rally::createdAt));
        return out;
    }

    /**
     * 全部<b>已到出发时刻但仍在准备中</b>的集结，按创建时刻升序。
     *
     * <p><b>为什么需要一次跨组织的扫描</b>：出发是请求驱动的（服务端不跑定时器），
     * 触发点是任意一次行军到期扫描 —— 而发起扫描的那个玩家跟这些集结毫无关系。
     * 只按组织查就得先知道「有哪些组织有集结」，那等于在本类里再维护一份索引，
     * 而那份索引一旦漏更新，症状就是「有些集结永远出不了发，且没有任何日志」。
     *
     * <p>返回的都是<b>副本</b>（与 Mongo 实现同一条语义），所以调用方改完必须写回来 ——
     * 不写回时改动只会留在本地那份上，不会再出现"dev 下看起来生效"的假象。
     */
    @Override
    public synchronized List<com.ironoath.core.social.Rally> dueRallies(long now) {
        List<com.ironoath.core.social.Rally> out = new ArrayList<>();
        for (com.ironoath.core.social.Rally rally : rallies.values()) {
            if (rally.dueAt(now)) {
                out.add(rally.copy());
            }
        }
        out.sort(java.util.Comparator.comparingLong(com.ironoath.core.social.Rally::createdAt));
        return out;
    }

    @Override
    public synchronized int ackEvents(String playerId, List<String> eventIds) {
        List<SocialEvent> events = unreadEvents.get(playerId);
        if (events == null || events.isEmpty()) {
            return 0;
        }
        boolean all = eventIds == null || eventIds.isEmpty();
        int before = events.size();
        events.removeIf(event -> all || eventIds.contains(event.eventId()));
        return before - events.size();
    }

    // ---------- 帮助请求 ----------

    @Override
    public synchronized void putHelpRequest(HelpRequest request) {
        helpRequests.put(request.requestId(), request);
    }

    @Override
    public synchronized Optional<HelpRequest> helpRequest(String requestId) {
        return Optional.ofNullable(helpRequests.get(requestId));
    }

    /**
     * 「谁和我在同一个组织里」：小队队友 + 联盟盟友，去重、不含自己。
     *
     * <p><b>为什么这条查询住在存储而不是某个服务里</b>：它读的就是本类持有的两张成员表，
     * 而它同时被两条路径需要（求助请求的广播、以及未来的其它通知）；放在服务里会让另一个服务
     * 为了发一条通知去依赖那个服务 —— {@code CityAppService → SocialAppService} 正是这么成环的。
     */
    @Override
    public synchronized List<String> peerPlayerIds(String playerId) {
        List<String> peers = new ArrayList<>();
        Optional<Squad> squad = squadOf(playerId);
        if (squad.isPresent()) {
            for (String member : squad.get().memberIds()) {
                if (!member.equals(playerId)) {
                    peers.add(member);
                }
            }
        }
        Optional<Alliance> alliance = allianceOf(playerId);
        if (alliance.isPresent()) {
            for (String member : alliance.get().memberIds()) {
                if (!member.equals(playerId) && !peers.contains(member)) {
                    peers.add(member);
                }
            }
        }
        return peers;
    }

    @Override
    public synchronized void removeHelpRequest(String requestId) {
        helpRequests.remove(requestId);
    }

    /** 全部待帮助请求。红点与「一键帮助」都基于它。 */
    @Override
    public synchronized List<HelpRequest> helpRequests() {
        return Collections.unmodifiableList(new ArrayList<>(helpRequests.values()));
    }

    /** 帮助次数 +1。 */
    @Override
    public synchronized void markHelped(String requestId) {
        HelpRequest request = helpRequests.get(requestId);
        if (request != null) {
            helpRequests.put(requestId, new HelpRequest(request.requestId(), request.fromPlayerId(),
                    request.fromPlayerName(), request.kind(), request.targetKey(), request.targetDesc(),
                    request.finishAt(), request.helpedCount() + 1));
        }
    }

    @Override
    public synchronized int counts() {
        return squadsById.size() + alliancesById.size() + chats.size() + helpRequests.size();
    }

    /**
     * 清空全部状态。单测在 {@code @BeforeEach} 里调用。
     *
     * <p>必须清而不是每个用例换名字：小队名、联盟名、联盟标签都是全局唯一约束，
     * 用例之间不清空的话，第二个用例会因为「名字已被占用」而失败 ——
     * 而那条失败信息看起来像是业务逻辑坏了，实际是测试隔离没做好。
     */
    @Override
    public synchronized void clear() {
        squadsById.clear();
        squadIdByPlayer.clear();
        squadIdByName.clear();
        alliancesById.clear();
        allianceIdByPlayer.clear();
        rallies.clear();
        allianceIdByName.clear();
        allianceIdByTag.clear();
        disbandProtectedUntil.clear();
        pendingApplications.clear();
        chats.clear();
        unreadEvents.clear();
        helpRequests.clear();
    }
}
