package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.SpyBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/** 生产发言→频道读取驱动的延迟互动；既有即时联盟寒暄不是本单元的证明。 */
@SpringBootTest
@ActiveProfiles("test")
class BotConversationTest {
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @SpyBean SocialAppService social;
    @Autowired SocialStore store;
    @Autowired BotRegistry bots;
    @Autowired TimeService time;
    @Autowired com.ironoath.core.world.WorldRepository world;
    @Autowired com.ironoath.core.army.ArmyRepository armies;
    @Autowired com.ironoath.core.march.MarchRepository marches;
    @Autowired com.ironoath.web.bot.BotWorldAdapter adapter;
    @Autowired com.ironoath.web.service.PowerRefreshService power;
    @Autowired com.ironoath.web.social.SocialRulesAssembler rules;
    @SpyBean com.ironoath.web.service.AttackGuardService attackGuard;
    @SpyBean com.ironoath.web.bot.BotConversationStore conversations;

    @BeforeEach void reset() {
        org.mockito.Mockito.reset(conversations);
        org.mockito.Mockito.reset(social,attackGuard);
        ((InMemoryPlayerStore) players).clear();
        ((com.ironoath.web.store.memory.InMemoryWorldStore)world).clear();
        ((com.ironoath.web.store.memory.InMemoryArmyStore)armies).clear();
        ((com.ironoath.web.store.memory.InMemoryMarchStore)marches).clear();
        adapter.resetCounters();
        store.clear();
        bots.clear();
        conversations.clear();
    }

    @Test void exactTargetReceivesAnAggressiveReplyOnlyAfterItsPersonaDelay() {
        String human = human("挑战者");
        String target = bot("铁卫", "bot_jielue", FixedPoint.SCALE);
        bot("路人", "bot_jielue", FixedPoint.SCALE);
        long now = time.serverNow();
        social.chatSend(human, req(ChatChannel.WORLD, "@铁卫 来战", null), now);
        assertThat(messages(human, now + 2_999)).hasSize(1);
        var delivered = messages(human, now + 3_000);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1).senderId()).isEqualTo(target);
        assertThat(delivered.get(1).content()).contains("@挑战者", "应战");
        assertThat(messages(human, now + 60_000)).hasSize(2);
    }

    @Test void cautiousAndAllyPersonasHaveDifferentReplies() {
        String human = human("邀战者");
        String cautious = bot("田园", "bot_paoyao", 0);
        String ally = bot("同袍", "bot_mengyou", 0);
        long now = time.serverNow();
        social.chatSend(human, req(ChatChannel.WORLD, "@田园 不敢来战吗", null), now);
        social.chatSend(human, req(ChatChannel.WORLD, "@同袍 来战", null), now + 1);
        var delivered = messages(human, now + 3_001);
        assertThat(delivered.stream().filter(m -> m.senderId().equals(cautious)).toList())
                .singleElement().satisfies(m -> assertThat(m.content()).contains("先守城"));
        assertThat(delivered.stream().filter(m -> m.senderId().equals(ally)).toList())
                .singleElement().satisfies(m -> assertThat(m.content()).contains("盟友"));
    }

    @Test void ordinaryAmbiguousPrivateAndBotMessagesProduceNoReply() {
        String human = human("普通玩家");
        String target = bot("守卫", "bot_jielue", FixedPoint.SCALE);
        bot("重名", "bot_jielue", FixedPoint.SCALE);
        bot("重名", "bot_jielue", FixedPoint.SCALE);
        long now = time.serverNow();
        social.chatSend(human, req(ChatChannel.WORLD, "@守卫 你好", null), now);
        social.chatSend(human, req(ChatChannel.WORLD, "@重名 来战", null), now + 1);
        social.chatSend(human, req(ChatChannel.PRIVATE, "@守卫 来战", target), now + 2);
        social.chatSend(target, req(ChatChannel.WORLD, "@守卫 来战", null), now + 3);
        assertThat(messages(human, now + 30_000)).hasSize(3);
        assertThat(bots.mayAppearIn("PRIVATE_CHAT_TO_HUMAN")).isFalse();
    }

    @Test void successfulChatWithFailedCompletionDoesNotResendAfterHistoryClipping() {
        String human=human("重试玩家");
        String bot=bot("恢复卫士","bot_jielue",FixedPoint.SCALE);
        long now=time.serverNow();
        doThrow(new IllegalStateException("fixture: completion write failed")).doCallRealMethod()
                .when(conversations).complete(anyString(),anyLong(),anyLong(),anyLong());
        social.chatSend(human,req(ChatChannel.WORLD,"@恢复卫士 来战",null),now);
        assertThat(messages(human,now+3_000)).hasSize(2);
        for(int i=0;i<210;i++) store.appendChat("WORLD",new SocialStore.ChatMessage("clip"+i,
                "WORLD",human,"重试玩家","闲聊"+i,now+3_001+i),200);
        assertThat(messages(human,now+4_000)).hasSize(200).noneMatch(m -> m.senderId().equals(bot));
        assertThat(conversations.due(now+4_000,10)).isEmpty();
        assertThat(store.chatDeliverySequence("WORLD",bot)).isEqualTo(now);
    }

    @Test void blockedAfterSchedulingAndOfflineOrUnsociablePersonasStaySilent() {
        String human=human("守规玩家");
        String bot=bot("候答","bot_jielue",FixedPoint.SCALE);
        String offline=bot("离线","bot_jielue",FixedPoint.SCALE);
        long now=time.serverNow();
        var off=bots.profileOf(offline);
        int hour=java.time.Instant.ofEpochMilli(now).atZone(com.ironoath.common.time.DayKey.CALENDAR_ZONE).getHour();
        bots.register(new BotProfile(offline,off.archetypeId(),off.ai(),new BotProfile.Persona(42,7,99,
                List.of((hour+2)%24),3,3,0),FixedPoint.SCALE));
        String quiet=bot("沉默","bot_jielue",FixedPoint.SCALE);
        var qp=bots.profileOf(quiet);
        bots.register(new BotProfile(quiet,qp.archetypeId(),new BotProfile.AiProfile(FixedPoint.SCALE,5_000,0,
                FixedPoint.SCALE),qp.persona(),FixedPoint.SCALE));
        social.chatSend(human,req(ChatChannel.WORLD,"@候答 来战",null),now);
        store.block(human,bot);
        social.chatSend(human,req(ChatChannel.WORLD,"@离线 来战",null),now+1);
        social.chatSend(human,req(ChatChannel.WORLD,"@沉默 来战",null),now+2);
        assertThat(messages(human,now+30_000)).hasSize(3);
        assertThat(conversations.due(now+30_000,10)).isEmpty();
    }

    @Test void nicknamePrefixesMultipleMentionsAndBareIdsDoNotChooseAnObject() {
        String human=human("边界玩家");
        String bot=bot("骑士","bot_jielue",FixedPoint.SCALE);
        bot("游侠","bot_jielue",FixedPoint.SCALE);
        long now=time.serverNow();
        social.chatSend(human,req(ChatChannel.WORLD,"@骑士团 来战",null),now);
        social.chatSend(human,req(ChatChannel.WORLD,"@骑士 @游侠 来战",null),now+1);
        social.chatSend(human,req(ChatChannel.WORLD,"@"+bot+" 来战",null),now+2);
        assertThat(messages(human,now+30_000)).hasSize(3);
    }

    @Test void exitedMemberCannotReplyWhileTheReverseIndexStillReferencesItsOldAlliance() {
        String human=human("盟主");
        String bot=bot("盟卫","bot_jielue",FixedPoint.SCALE);
        alliance(human,bot);
        long now=time.serverNow();
        social.chatSend(human,req(ChatChannel.ALLIANCE,"@盟卫 来战",null),now);
        assertThat(social.chatList(human,new ChatListReq(ChatChannel.ALLIANCE,null,null,20),now+3_000).messages())
                .hasSize(2).anyMatch(m -> m.senderId().equals(bot));
        social.chatSend(human,req(ChatChannel.ALLIANCE,"@盟卫 再来战",null),now+20_000);
        var a=store.allianceOf(bot).orElseThrow();
        // 真实leave先saveAlliance再unbind；此夹具停在两步之间，核实际memberIds而非旧索引。
        long version=a.version(); a.leave(bot); store.saveAlliance(a,version);
        assertThat(social.chatList(human,new ChatListReq(ChatChannel.ALLIANCE,null,null,20),now+23_000).messages())
                .hasSize(3).filteredOn(m -> m.senderId().equals(bot)).hasSize(1);
        assertThat(conversations.due(now+23_000,10)).isEmpty();
    }

    @Test void successfulRealRaidSchedulesOneAddressedChallengeWithoutSendingAnotherMarch() {
        String victim=human("对手");
        String bot=bot("掠袭者","bot_jielue",FixedPoint.SCALE);
        prepareArmy(victim,300,300);
        prepareArmy(bot,256,256);
        long now=time.serverNow();
        adapter.execute(bot,new com.ironoath.core.bot.BotDecisionTree.Decision(
                com.ironoath.core.bot.BotDecisionTree.Action.RAID,false,0,"fixture: select raid"),now);
        assertThat(marches.activeCountOf(bot)).isEqualTo(1);
        assertThat(messages(victim,now+2_999)).isEmpty();
        assertThat(messages(victim,now+3_000)).singleElement().satisfies(m -> {
            assertThat(m.senderId()).isEqualTo(bot);
            assertThat(m.content()).contains("@对手","已出征");
        });
        assertThat(marches.activeCountOf(bot)).isEqualTo(1);
    }

    @Test void noTargetOrCancelledRaidCannotIssueAPhantomChallenge() {
        String bot=bot("无对手","bot_jielue",FixedPoint.SCALE);
        prepareArmy(bot,256,256);
        long now=time.serverNow();
        adapter.execute(bot,new com.ironoath.core.bot.BotDecisionTree.Decision(
                com.ironoath.core.bot.BotDecisionTree.Action.RAID,false,0,"fixture: no target"),now);
        assertThat(conversations.due(now+30_000,10)).isEmpty();
        assertThat(messages(bot,now+30_000)).isEmpty();
        String victim=human("取消对手"); prepareArmy(victim,300,300);
        adapter.execute(bot,new com.ironoath.core.bot.BotDecisionTree.Decision(
                com.ironoath.core.bot.BotDecisionTree.Action.RAID,false,0,"fixture: valid target"),now+40_000);
        assertThat(marches.activeCountOf(bot)).isEqualTo(1);
        // 删除自有测试行军模拟撤回/失效，绝不动原账号或候选实例。
        ((com.ironoath.web.store.memory.InMemoryMarchStore)marches).clear();
        assertThat(messages(victim,now+43_000)).isEmpty();
    }

    @Test void temporaryRaidGuardFailureKeepsPendingForTheNextRealRead() {
        String victim=human("短故障对手");
        String bot=bot("短故障掠袭","bot_jielue",FixedPoint.SCALE);
        prepareArmy(victim,300,300); prepareArmy(bot,256,256);
        long now=time.serverNow();
        adapter.execute(bot,new com.ironoath.core.bot.BotDecisionTree.Decision(
                com.ironoath.core.bot.BotDecisionTree.Action.RAID,false,0,"fixture: valid raid"),now);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture: transient Mongo unavailable"))
                .doCallRealMethod().when(attackGuard).guard(anyString(),any(com.ironoath.core.world.Coord.class),anyLong());
        assertThat(messages(victim,now+3_000)).isEmpty();
        assertThat(conversations.due(now+3_000,10)).hasSize(1);
        assertThat(messages(victim,now+4_000)).singleElement().satisfies(m -> assertThat(m.senderId()).isEqualTo(bot));
    }

    @Test void fiftyUnknownProfilesDoNotStarveARegisteredBotsDueReply() {
        String human=human("公平玩家");
        String bot=bot("队尾卫士","bot_jielue",FixedPoint.SCALE);
        long now=time.serverNow();
        for(int i=0;i<50;i++) conversations.offer(new com.ironoath.web.bot.BotConversationStore.Pending(
                "unknown"+i,human,"WORLD","WORLD","old"+i,"旧待办",now-100_000+i,now-90_000+i,null),10_000);
        social.chatSend(human,req(ChatChannel.WORLD,"@队尾卫士 来战",null),now);
        assertThat(messages(human,now+3_000)).hasSize(2).anyMatch(m -> m.senderId().equals(bot));
        assertThat(conversations.due(now+3_000,100)).hasSize(50);
    }

    @Test void membershipSwitchBeforeSendCannotLeakTheOldConversationIntoANewAlliance() {
        String human=human("原盟主");
        String bot=bot("迁盟卫士","bot_jielue",FixedPoint.SCALE);
        String other=human("新盟主");
        alliance(human,bot); alliance(other,null);
        long now=time.serverNow();
        social.chatSend(human,req(ChatChannel.ALLIANCE,"@迁盟卫士 来战",null),now);
        doAnswer(invocation -> {
            var old=store.allianceOf(bot).orElseThrow(); long ov=old.version();
            old.leave(bot); store.saveAlliance(old,ov); store.unbindAllianceMember(old.id(),bot);
            var next=store.allianceOf(other).orElseThrow(); long nv=next.version();
            next.join(bot); store.saveAlliance(next,nv);
            return invocation.callRealMethod();
        }).when(social).chatSendOrdered(eq(bot),any(ChatSendReq.class),anyLong(),anyLong(),anyString(),anyString());
        assertThat(social.chatList(human,new ChatListReq(ChatChannel.ALLIANCE,null,null,20),now+3_000).messages()).hasSize(1);
        assertThat(social.chatList(other,new ChatListReq(ChatChannel.ALLIANCE,null,null,20),now+4_000).messages()).isEmpty();
    }

    private void prepareArmy(String player,int x,int y) {
        assertThat(world.placeCity(player,com.ironoath.core.world.Coord.of(x,y))).isTrue();
        var save=players.findByPlayerId(player).orElseThrow(); save.setProtectUntil(null); players.save(save);
        if(armies.findByPlayerId(player).isEmpty()) armies.insertIfAbsent(player,new com.ironoath.core.army.ArmyState());
        var army=armies.findByPlayerId(player).orElseThrow(); long version=armies.versionOf(player);
        army.add("unit_infantry_t1",300); armies.save(player,army,version); power.refresh(player);
    }

    @Test void squadRepliesRequireBothPlayersInTheSameLiveSquad() {
        String human=human("队长");
        String bot=bot("队卫","bot_mengyou",0);
        String outsider=bot("队外","bot_jielue",FixedPoint.SCALE);
        var squad=com.ironoath.core.social.Squad.create("squad-"+UUID.randomUUID(),"互动队",human,rules.squadRules());
        squad.join(bot,16); store.saveSquad(squad,0L);
        long now=time.serverNow();
        social.chatSend(human,req(ChatChannel.SQUAD,"@队卫 来战",null),now);
        social.chatSend(human,req(ChatChannel.SQUAD,"@队外 来战",null),now+1);
        assertThat(social.chatList(human,new ChatListReq(ChatChannel.SQUAD,null,null,20),now+3_001).messages())
                .hasSize(3).filteredOn(m -> m.senderId().equals(bot)).hasSize(1);
        assertThat(conversations.due(now+3_001,10)).noneMatch(p -> p.botId().equals(outsider));
        social.chatSend(human,req(ChatChannel.SQUAD,"@队卫 再来战",null),now+20_000);
        var stored=store.squadOf(bot).orElseThrow(); long version=stored.version();
        stored.leave(bot); store.saveSquad(stored,version);
        assertThat(social.chatList(human,new ChatListReq(ChatChannel.SQUAD,null,null,20),now+23_000).messages())
                .hasSize(4).filteredOn(m -> m.senderId().equals(bot)).hasSize(1);
    }

    private void alliance(String human,String bot) {
        var save=players.findByPlayerId(human).orElseThrow(); save.setCityLevel(16);
        var gold=save.resources().get("GOLD");
        save.putResource("GOLD",new com.ironoath.core.player.PlayerResourceState(100_000,gold.cap(),
                gold.protectedAmount(),gold.perHour(),gold.lastSettle())); players.save(save);
        social.allianceCreate(human,new com.ironoath.web.dto.generated.AllianceCreateReq("req-"+UUID.randomUUID(),
                "互动盟"+UUID.randomUUID().toString().substring(0,4),"T"+UUID.randomUUID().toString().substring(0,3)));
        if(bot!=null) {
            var a=store.allianceOf(human).orElseThrow(); long version=a.version(); a.join(bot); store.saveAlliance(a,version);
        }
    }

    private List<com.ironoath.web.dto.generated.ChatMessageView> messages(String human, long now) {
        return social.chatList(human, new ChatListReq(ChatChannel.WORLD, null, null, 200), now).messages();
    }

    private ChatSendReq req(ChatChannel channel, String text, String peer) {
        return new ChatSendReq("req-" + UUID.randomUUID(), channel, text, peer);
    }

    private String human(String nick) {
        return init.init(new PlayerInitReq("req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                nick, 1_700_000_000_000L, "")).playerId();
    }

    private String bot(String nick, String archetype, long aggression) {
        String id = human(nick);
        bots.register(new BotProfile(id, archetype,
                new BotProfile.AiProfile(aggression, 5_000, FixedPoint.SCALE, FixedPoint.SCALE),
                new BotProfile.Persona(42, 7, 99, IntStream.range(0, 24).boxed().toList(), 3, 3, 0),
                FixedPoint.SCALE));
        return id;
    }
}
