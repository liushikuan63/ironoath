package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.AllianceRole;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Squad;
import com.ironoath.core.social.SquadRole;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.mongo.MongoSocialStore;

/**
 * 职责：社交存储在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的最后一档）。
 * 依赖：真实配置表（小队/联盟规则现取）+ 本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>这一档的核心风险是"读返回副本"</b>：内存版原先直接把库里的对象交给调用方，
 * 于是"改了没 save"在 dev 下完全看不出来、换 Mongo 就是一次静默丢档。所以每个往返用例
 * 之外都有一条副本语义用例；没有它，Mongo 版天然是副本这件事会让测试以为两侧一致，
 * 而真相是内存版在替调用方掩盖错误。
 *
 * <p><b>夹具刻意用 {@code restore(...)} 直接构造富状态</b>：走领域方法一条条把
 * level/fund/tech/donatedToday 攒出来，会把"某个字段没被映射"淹没在长长的业务路径里。
 * 直接给每个字段一个非默认值，再用 describe 逐字段比对，漏字段会当场变成一行差异。
 */
class SocialStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String DAY = "2026-09-12";
    private static ConfigRegistry configs;
    private static SocialRulesAssembler rules;
    private static TestMongo db;

    @BeforeAll
    static void setUp() {
        configs = ConfigRegistry.loadFromDirectory(locateConfigDir());
        rules = new SocialRulesAssembler(configs);
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearSocial() {
        if (db != null) {
            newMongoStore().clear();
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // ---------- 小队 ----------

    @Test
    @DisplayName("小队全字段往返：成员角色、金币、经验、联盟归属与解散状态都要读得回来")
    void squadRoundTripsEveryField() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Squad squad = richSquad("SQ-1", "铁血");
            store.saveSquad(squad);

            assertThat(describe(store.squadById("SQ-1").orElseThrow()))
                    .as("%s：按 id 读回来的小队必须逐字段等于写进去的那份", label)
                    .isEqualTo(describe(squad));
            assertThat(describe(store.squadOf("P-2").orElseThrow()))
                    .as("%s：按成员反查也要落到同一支小队", label)
                    .isEqualTo(describe(squad));
            assertThat(store.squadNameTaken("铁血")).as("%s：名字已被占用", label).isTrue();
            assertThat(store.squadNameTaken("别的名字")).as("%s：未占用", label).isFalse();
        }
    }

    @Test
    @DisplayName("读返回副本：改了不 save，两套实现都必须看不到那笔改动")
    void squadReadReturnsADetachedCopy() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveSquad(richSquad("SQ-1", "铁血"));
            Squad copy = store.squadById("SQ-1").orElseThrow();
            int before = copy.memberCount();
            copy.join("P-9", 30);

            assertThat(store.squadById("SQ-1").orElseThrow().memberCount())
                    .as("%s：内存版返回活对象时这条永远测不出来，换 Mongo 就是「改了没存也不报错」", label)
                    .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("小队同名冲突：两套实现都要响亮拒绝，而不是让其中一支从名字上消失")
    void squadNameCollisionIsRefused() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveSquad(richSquad("SQ-1", "铁血"));
            assertThatThrownBy(() -> store.saveSquad(richSquad("SQ-2", "铁血")))
                    .as("%s：同名写入必须被拒", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("小队名已被其它小队占用");
        }
    }

    @Test
    @DisplayName("解散小队被 unbind 收尾：两套实现都要让名字与成员查询同时释放")
    void disbandedSquadIsRemovedByUnbind() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Squad squad = richSquad("SQ-1", "铁血");
            squad.disband(T0 + 1_000L);
            store.saveSquad(squad);
            store.unbindSquadMember("SQ-1", "P-1");

            assertThat(store.squadById("SQ-1")).as("%s：解散后按 id 查不到", label).isEmpty();
            assertThat(store.squadOf("P-2")).as("%s：解散后成员也查不到", label).isEmpty();
            assertThat(store.squadNameTaken("铁血")).as("%s：名字要释放", label).isFalse();
        }
    }

    // ---------- 联盟 ----------

    @Test
    @DisplayName("联盟全字段往返：贡献、当日捐献、科技等级、资金、版本与解散状态都要字字对上")
    void allianceRoundTripsEveryField() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Alliance alliance = richAlliance("AL-1", "铁盟", "IRON");
            store.saveAlliance(alliance);

            assertThat(describe(store.allianceById("AL-1").orElseThrow()))
                    .as("%s：按 id 往返", label).isEqualTo(describe(alliance));
            assertThat(describe(store.allianceOf("P-2").orElseThrow()))
                    .as("%s：按成员反查", label).isEqualTo(describe(alliance));
            assertThat(store.allAlliances()).as("%s：allAlliances 也要包含它", label)
                    .extracting(Alliance::id).containsExactly("AL-1");
            assertThat(store.allianceNameTaken("铁盟")).as("%s：名字占用", label).isTrue();
            assertThat(store.allianceTagTaken("IRON")).as("%s：标签占用", label).isTrue();
        }
    }

    @Test
    @DisplayName("联盟读返回副本：改了不 save 必须看不到")
    void allianceReadReturnsADetachedCopy() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveAlliance(richAlliance("AL-1", "铁盟", "IRON"));
            Alliance copy = store.allianceById("AL-1").orElseThrow();
            long before = copy.fund();
            copy.addFund(999L);

            assertThat(store.allianceById("AL-1").orElseThrow().fund())
                    .as("%s：没 save 的加钱不许出现在库里", label).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("联盟名与标签冲突：两套实现都要拒绝，且文案能区分是哪一条撞了")
    void allianceNameAndTagCollisionsAreRefused() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveAlliance(richAlliance("AL-1", "铁盟", "IRON"));
            assertThatThrownBy(() -> store.saveAlliance(richAlliance("AL-2", "铁盟", "GOLD")))
                    .as("%s：重名", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("联盟名已被其它联盟占用");
            assertThatThrownBy(() -> store.saveAlliance(richAlliance("AL-3", "别的盟", "IRON")))
                    .as("%s：重标签", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("联盟标签已被其它联盟占用");
        }
    }

    @Test
    @DisplayName("解散联盟：removeAlliance 之后名字、标签与成员反查同时释放")
    void removeAllianceFreesEveryLookup() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Alliance alliance = richAlliance("AL-1", "铁盟", "IRON");
            store.saveAlliance(alliance);
            store.removeAlliance(alliance);

            assertThat(store.allianceById("AL-1")).as("%s：按 id 查不到", label).isEmpty();
            assertThat(store.allianceOf("P-2")).as("%s：成员反查查不到", label).isEmpty();
            assertThat(store.allianceNameTaken("铁盟")).as("%s：名字释放", label).isFalse();
            assertThat(store.allianceTagTaken("IRON")).as("%s：标签释放", label).isFalse();
        }
    }

    // ---------- 入盟申请与解散保护期 ----------

    @Test
    @DisplayName("入盟申请：同一对联盟+玩家只算一次，计数与移除两侧一致")
    void applicationsAreIdempotent() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.addApplication("AL-1", "P-1")).as("%s：第一次申请成功", label).isTrue();
            assertThat(store.addApplication("AL-1", "P-1")).as("%s：重复申请返回 false", label).isFalse();
            store.addApplication("AL-1", "P-2");
            store.addApplication("AL-2", "P-1");

            assertThat(store.hasApplication("AL-1", "P-1")).as("%s：查得到", label).isTrue();
            assertThat(store.pendingApplicationCount("AL-1")).as("%s：AL-1 两条", label).isEqualTo(2);
            assertThat(store.removeApplication("AL-1", "P-1")).as("%s：移除成功", label).isTrue();
            assertThat(store.removeApplication("AL-1", "P-1")).as("%s：再移除 false", label).isFalse();
            assertThat(store.pendingApplicationCount("AL-1")).as("%s：剩一条", label).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("解散保护期：只延长不缩短，两套实现同一条")
    void protectFromCreatingOnlyExtends() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.protectFromCreating("P-1", T0 + 100L);
            store.protectFromCreating("P-1", T0 + 50L);
            assertThat(store.disbandProtectedUntil("P-1")).as("%s：短的不许覆盖长的", label)
                    .isEqualTo(T0 + 100L);
            store.protectFromCreating("P-1", T0 + 200L);
            assertThat(store.disbandProtectedUntil("P-1")).as("%s：更长的要生效", label)
                    .isEqualTo(T0 + 200L);
            assertThat(store.disbandProtectedUntil("P-9")).as("%s：没记录的人回 0", label).isZero();
        }
    }

    // ---------- 聊天 ----------

    @Test
    @DisplayName("聊天分页：保留上限、游标定位、找不到游标回空页，三条语义两侧一致")
    void chatPagingAndRetentionMatch() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.appendChat("WORLD", chat("m1", 1L), 3);
            store.appendChat("WORLD", chat("m2", 2L), 3);
            store.appendChat("WORLD", chat("m3", 3L), 3);
            store.appendChat("WORLD", chat("m4", 4L), 3);

            assertThat(ids(store.chat("WORLD", null, 10)))
                    .as("%s：上限 3，最旧的 m1 已被裁掉", label)
                    .containsExactly("m2", "m3", "m4");
            assertThat(ids(store.chat("WORLD", "m3", 10)))
                    .as("%s：游标之前只回 m2", label).containsExactly("m2");
            assertThat(ids(store.chat("WORLD", "m1", 10)))
                    .as("%s：游标已被裁掉 → 空页，而不是退回最新一页", label).isEmpty();
            assertThat(store.hasMore("WORLD", "m4", 1)).as("%s：m4 之前有 2 条 > 1", label).isTrue();
            assertThat(store.hasMore("WORLD", "m4", 2)).as("%s：2 条不再有更多", label).isFalse();
            assertThat(store.hasMore("WORLD", "gone", 1)).as("%s：陌生游标没有更多", label).isFalse();
        }
    }

    @Test
    @DisplayName("同一频道内 messageId 撞号必须响亮拒绝：静默收下会让翻页跳过一整段")
    void chatDuplicateMessageIdIsRefused() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.appendChat("WORLD", chat("m1", 1L), 10);
            assertThatThrownBy(() -> store.appendChat("WORLD", chat("m1", 2L), 10))
                    .as("%s：同频道重复 id 必须抛", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("聊天消息 id 在同一个频道里重复了");
        }
    }

    // ---------- 社交事件与帮助请求 ----------

    @Test
    @DisplayName("社交事件：按条标记与全部标记的返回值、剩余列表两侧一致")
    void eventsAckMatches() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.pushEvent("P-1", event("e1", T0));
            store.pushEvent("P-1", event("e2", T0 + 1L));

            assertThat(store.ackEvents("P-1", List.of("e1"))).as("%s：标掉一条", label).isEqualTo(1);
            assertThat(idsOfEvents(store.unreadEvents("P-1"))).as("%s：剩 e2", label)
                    .containsExactly("e2");
            assertThat(store.ackEvents("P-1", List.of("no-such-id")))
                    .as("%s：陌生 id 标 0 条", label).isZero();
            assertThat(store.ackEvents("P-1", List.of())).as("%s：空列表=全标", label).isEqualTo(1);
            assertThat(store.unreadEvents("P-1")).as("%s：全标后为空", label).isEmpty();
            assertThat(store.ackEvents("P-9", List.of())).as("%s：没有事件的玩家回 0", label).isZero();
        }
    }

    @Test
    @DisplayName("帮助请求：写入、+1、移除三段都要在两侧成立")
    void helpRequestsRoundTrip() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.putHelpRequest(help("H-1"));
            assertThat(store.helpRequest("H-1").orElseThrow()).as("%s：原样读回", label)
                    .isEqualTo(help("H-1"));

            store.markHelped("H-1");
            assertThat(store.helpRequest("H-1").orElseThrow().helpedCount())
                    .as("%s：帮助次数 +1", label).isEqualTo(1);
            assertThat(store.helpRequests()).as("%s：列表里有它", label)
                    .extracting(SocialStore.HelpRequest::requestId).containsExactly("H-1");

            store.removeHelpRequest("H-1");
            assertThat(store.helpRequest("H-1")).as("%s：移除后读不到", label).isEmpty();
        }
    }

    @Test
    @DisplayName("同组织成员并集：小队队友 + 联盟盟友去重、不含自己，顺序两侧一致")
    void peerUnionMatches() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveSquad(richSquad("SQ-1", "铁血"));
            store.saveAlliance(richAlliance("AL-1", "铁盟", "IRON"));

            assertThat(store.peerPlayerIds("P-1"))
                    .as("%s：P-2/P-3 来自小队，P-4 只来自联盟，去重且不含自己", label)
                    .containsExactly("P-2", "P-3", "P-4");
        }
    }

    // ---------- 集结 ----------

    @Test
    @DisplayName("集结往返：进行中的查询、到期扫描、参与者与出发结果都要对得上")
    void ralliesRoundTrip() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Rally preparing = richRally("R-1", Rally.Status.PREPARING, null, T0 + 60_000L);
            Rally departed = richRally("R-2", Rally.Status.DEPARTED,
                    new Rally.Departure(Map.of("unit_infantry_t1", 150L), 150L, 2, T0 + 60_000L),
                    T0 + 60_000L);
            store.saveRally(preparing);
            store.saveRally(departed);

            assertThat(describe(store.rallyOf("R-1").orElseThrow()))
                    .as("%s：进行中的集结逐字段往返", label).isEqualTo(describe(preparing));
            assertThat(describe(store.rallyOf("R-2").orElseThrow()))
                    .as("%s：已出发的集结包含 departure", label).isEqualTo(describe(departed));
            assertThat(store.preparingRalliesOf("SQ-1")).as("%s：只回 PREPARING", label)
                    .extracting(Rally::rallyId).containsExactly("R-1");
            assertThat(store.dueRallies(T0 + 30_000L)).as("%s：还没到点", label).isEmpty();
            assertThat(store.dueRallies(T0 + 90_000L)).as("%s：到点后回 R-1", label)
                    .extracting(Rally::rallyId).containsExactly("R-1");
        }
    }

    // ---------- 计数与清空 ----------

    @Test
    @DisplayName("counts 口径与 clear 行为两侧一致")
    void countsAndClearMatch() {
        for (SocialStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveSquad(richSquad("SQ-1", "铁血"));
            store.saveAlliance(richAlliance("AL-1", "铁盟", "IRON"));
            store.appendChat("WORLD", chat("m1", 1L), 10);
            store.putHelpRequest(help("H-1"));

            assertThat(store.counts()).as("%s：1+1+1 频道+1", label).isEqualTo(4);
            store.clear();
            assertThat(store.counts()).as("%s：清空归零", label).isZero();
            assertThat(store.squadById("SQ-1")).as("%s：小队也清掉", label).isEmpty();
            assertThat(store.allianceById("AL-1")).as("%s：联盟也清掉", label).isEmpty();
        }
    }

    // ---------- 夹具 ----------

    /** 两个实现都要跑：内存版恒定，Mongo 版在本机没有 Mongo 时整体跳过（Skipped 会显示出来）。 */
    private List<SocialStore> bothStores() {
        requireMongo();
        return List.of(new InMemorySocialStore(), newMongoStore());
    }

    private static MongoSocialStore newMongoStore() {
        requireMongo();
        return new MongoSocialStore(db.template(), rules);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 社交存储在 Mongo 上的等价性今天没有被验证");
    }

    /** 富状态小队：每个持久化字段都刻意取非默认值。 */
    private static Squad richSquad(String id, String name) {
        Map<String, SquadRole> members = new LinkedHashMap<>();
        members.put("P-1", SquadRole.LEADER);
        members.put("P-2", SquadRole.MEMBER);
        members.put("P-3", SquadRole.MEMBER);
        Map<String, Long> coins = new LinkedHashMap<>();
        coins.put("P-1", 7L);
        coins.put("P-2", 3L);
        coins.put("P-3", 0L);
        return Squad.restore(id, name, "P-1", rules.squadRules(), members, 3, 250L,
                "AL-1", coins, 10L, 4L, 0L);
    }

    /** 富状态联盟：含当日捐献与科技两张容易被旧 restore 丢掉的表。 */
    private static Alliance richAlliance(String id, String name, String tag) {
        Map<String, AllianceRole> members = new LinkedHashMap<>();
        members.put("P-1", AllianceRole.LEADER);
        members.put("P-2", AllianceRole.OFFICER);
        members.put("P-3", AllianceRole.ELDER);
        members.put("P-4", AllianceRole.MEMBER);
        Map<String, Long> contributions = new LinkedHashMap<>();
        contributions.put("P-1", 120L);
        contributions.put("P-2", 30L);
        contributions.put("P-3", 5L);
        Map<String, Integer> donated = new LinkedHashMap<>();
        donated.put("P-1:" + DAY, 2);
        donated.put("P-2:" + DAY, 1);
        Map<String, Integer> techs = new LinkedHashMap<>();
        techs.put("tech_atk", 3);
        techs.put("tech_def", 1);
        return Alliance.restore(id, name, tag, "P-1", rules.allianceRules(), members,
                contributions, donated, techs, 4, 900L, 50_000L, 7, 2, 42L, 0L);
    }

    private static Rally richRally(String id, Rally.Status status, Rally.Departure departure, long prepareUntil) {
        Map<String, Rally.Participant> participants = new LinkedHashMap<>();
        participants.put("P-1", new Rally.Participant("P-1",
                Map.of("unit_infantry_t1", 100L), List.of("hero_ssr_01")));
        participants.put("P-2", new Rally.Participant("P-2",
                Map.of("unit_archer_t1", 50L), List.of()));
        return Rally.restore(id, Rally.Scope.SQUAD, "SQ-1", "P-1", 5, 2, T0, prepareUntil,
                participants, status, departure, 10L, 20L, "MONSTER");
    }

    private static SocialStore.ChatMessage chat(String messageId, long sentAt) {
        return new SocialStore.ChatMessage(messageId, "WORLD", "P-1", "甲", "内容-" + messageId, sentAt);
    }

    private static SocialStore.SocialEvent event(String eventId, long occurredAt) {
        return new SocialStore.SocialEvent(eventId, "HELP_REQUEST", "标题", "正文", null, null,
                "H-1", occurredAt, occurredAt + 60_000L);
    }

    private static SocialStore.HelpRequest help(String requestId) {
        return new SocialStore.HelpRequest(requestId, "P-1", "甲", "BUILDING", "bld_1", "兵营", T0, 0);
    }

    private static List<String> ids(List<SocialStore.ChatMessage> messages) {
        List<String> out = new ArrayList<>();
        for (SocialStore.ChatMessage message : messages) {
            out.add(message.messageId());
        }
        return out;
    }

    private static List<String> idsOfEvents(List<SocialStore.SocialEvent> events) {
        List<String> out = new ArrayList<>();
        for (SocialStore.SocialEvent event : events) {
            out.add(event.eventId());
        }
        return out;
    }

    /** 逐字段描述。新增状态字段时必须在这里出现，否则"映射少带一个字段"没人能发现。 */
    private static String describe(Squad squad) {
        StringBuilder b = new StringBuilder();
        b.append(squad.id()).append('#').append(squad.name()).append('#').append(squad.leaderId())
                .append('#').append(squad.level()).append('#').append(squad.exp())
                .append('#').append(squad.allianceId()).append('#').append(squad.squadCoinPool())
                .append('#').append(squad.dailyQuestProgress()).append('#').append(squad.disbandedAt())
                .append("#members=");
        for (Map.Entry<String, SquadRole> entry : squad.members().entrySet()) {
            b.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        b.append("#coins=");
        for (Map.Entry<String, Long> entry : squad.squadCoins().entrySet()) {
            b.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return b.toString();
    }

    private static String describe(Alliance alliance) {
        StringBuilder b = new StringBuilder();
        b.append(alliance.id()).append('#').append(alliance.name()).append('#').append(alliance.tag())
                .append('#').append(alliance.leaderId()).append('#').append(alliance.level())
                .append('#').append(alliance.exp()).append('#').append(alliance.fund())
                .append('#').append(alliance.territoryCount()).append('#').append(alliance.paidCapTier())
                .append('#').append(alliance.version()).append('#').append(alliance.disbandedAt())
                .append("#members=");
        for (Map.Entry<String, AllianceRole> entry : alliance.members().entrySet()) {
            b.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        b.append("#contributions=").append(alliance.contributions())
                .append("#donated=").append(alliance.donatedTodayByKey())
                .append("#techs=").append(alliance.techLevels());
        return b.toString();
    }

    private static String describe(Rally rally) {
        StringBuilder b = new StringBuilder();
        b.append(rally.rallyId()).append('#').append(rally.scope()).append('#').append(rally.groupId())
                .append('#').append(rally.initiatorId()).append('#').append(rally.maxMembers())
                .append('#').append(rally.minMembersRequired()).append('#').append(rally.createdAt())
                .append('#').append(rally.prepareUntil()).append('#').append(rally.status())
                .append('#').append(rally.targetX()).append(',').append(rally.targetY())
                .append('#').append(rally.targetType()).append("#participants=");
        for (Map.Entry<String, Rally.Participant> entry : rally.participants().entrySet()) {
            b.append(entry.getKey()).append('=').append(entry.getValue().troops())
                    .append('/').append(entry.getValue().heroes()).append(';');
        }
        Rally.Departure departure = rally.departure();
        b.append("#departure=");
        if (departure != null) {
            b.append(departure.mergedTroops()).append('/').append(departure.totalTroops())
                    .append('/').append(departure.memberCount()).append('/').append(departure.departAt());
        }
        return b.toString();
    }

    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }
}