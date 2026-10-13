package com.ironoath.web.bot;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotChatBook;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.service.AttackGuardService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.ChatAcceptedEvent;
import com.ironoath.web.social.ChatReadEvent;
import com.ironoath.web.social.SocialStore;

/** 允许频道的有对象互动；只排持久待办，下一次读/tick到期才发真人聊天。 */
@Service
public class BotConversationService {
    private static final Logger LOG = LoggerFactory.getLogger(BotConversationService.class);
    private static final Pattern CHALLENGE = Pattern.compile("来打|来战|挑战|不敢|胆小|挑衅");
    private final BotRegistry bots;
    private final PlayerRepository players;
    private final SocialStore socialStore;
    private final SocialAppService social;
    private final BotRulesAssembler assembler;
    private final BotConversationStore pending;
    private final ConfigRegistry configs;
    private final MarchRepository marches;
    private final WorldRepository world;
    private final AttackGuardService attackGuard;

    public BotConversationService(BotRegistry bots, PlayerRepository players, SocialStore socialStore,
            SocialAppService social, BotRulesAssembler assembler, BotConversationStore pending,
            ConfigRegistry configs, MarchRepository marches, WorldRepository world, AttackGuardService attackGuard) {
        this.bots=bots; this.players=players; this.socialStore=socialStore; this.social=social;
        this.assembler=assembler; this.pending=pending; this.configs=configs;
        this.marches=marches; this.world=world; this.attackGuard=attackGuard;
    }

    @EventListener public void onChat(ChatAcceptedEvent event) {
        try {
            var message=event.message();
            if (bots.profileOf(message.senderId()) != null || "PRIVATE".equals(message.channel())
                    || !CHALLENGE.matcher(message.content()).find() || !message.content().contains("@")) return;
            // 一次批量读取画像对象的展示字段，不按内部id识别、不广播回复。
            var brief=players.findBriefs(List.copyOf(bots.botIds()));
            var targets=brief.values().stream().filter(b -> b.nickName()!=null && !b.nickName().isBlank())
                    .filter(b -> mentions(message.content(), b.nickName()))
                    .filter(b -> eligible(b.playerId(), message.senderId(), message.channel(), event.channelKey()))
                    .toList();
            if (targets.size()!=1) return;
            BotProfile profile=bots.profileOf(targets.get(0).playerId());
            long now=message.sentAt();
            if (profile==null || !active(profile,now)) return;
            Rng rng=Rng.of(now*31L + message.messageId().hashCode());
            if (!roll(rng,profile.ai().sociability())) return;
            BotChatBook.Scene scene="bot_mengyou".equals(profile.archetypeId())
                    ? BotChatBook.Scene.TAUNT_ALLY_REPLY
                    : roll(rng,profile.ai().aggression()) ? BotChatBook.Scene.TAUNT_AGGRESSIVE_REPLY
                    : BotChatBook.Scene.TAUNT_CAUTIOUS_REPLY;
            schedule(profile,message.senderId(),message.channel(),event.channelKey(),message.messageId(),scene,now,null,rng);
        } catch (RuntimeException e) { LOG.warn("聊天互动未排期，不影响原发送：{}", e.toString()); }
    }

    @EventListener public void onRaid(BotRaidAcceptedEvent event) {
        try {
            BotProfile profile=bots.profileOf(event.playerId());
            if (profile==null || bots.profileOf(event.targetId())!=null || !active(profile,event.atMillis())) return;
            Rng rng=Rng.of(event.atMillis()*31L+event.marchId().hashCode());
            if (!roll(rng,profile.ai().sociability())) return;
            schedule(profile,event.targetId(),"WORLD","WORLD",event.marchId(),
                    BotChatBook.Scene.TAUNT_ATTACK,event.atMillis(),event.marchId(),rng);
        } catch (RuntimeException e) { LOG.warn("行军互动未排期，不影响派兵：{}",e.toString()); }
    }

    private void schedule(BotProfile profile,String peer,String channel,String key,String source,
            BotChatBook.Scene scene,long now,String march,Rng rng) {
        if (!eligible(profile.botId(),peer,channel,key)) return;
        var save=players.findByPlayerId(profile.botId()).orElse(null);
        var target=players.findByPlayerId(peer).orElse(null);
        if (save==null || target==null || target.nickName()==null || target.nickName().isBlank()) return;
        var line=assembler.chatBook().pick(rng,scene,save.cityLevel());
        if (line.isEmpty()) return;
        long delay=rng.range(profile.persona().reactionDelayMinSec(),profile.persona().reactionDelayMaxSec())*1000L;
        String content=line.get().text().replace("{target}", target.nickName());
        pending.offer(new BotConversationStore.Pending(profile.botId(),peer,channel,key,source,
                content,now,now+delay,march),cooldown());
    }

    @EventListener public void onRead(ChatReadEvent event) { drive(event.atMillis()); }

    /** 单轮沿既有条数/时间预算；失败保留待办，成功或失效CAS完成，不破坏原请求。 */
    public void drive(long now) {
        long start=System.nanoTime();
        try {
            for (var task:pending.dueFor(now,List.copyOf(bots.botIds()),(int)configs.longParam("BOT_DUE_BATCH_LIMIT"))) {
                if ((System.nanoTime()-start)/1_000_000L>=configs.longParam("BOT_ROUND_BUDGET_MS")) break;
                try {
                    BotProfile profile=bots.profileOf(task.botId());
                    if (profile==null) continue; // 重启尚未恢复画像时保留，不能把未知当已回收。
                    if (!eligible(task.botId(),task.peerId(),task.channel(),task.channelKey())
                            || !active(profile,now) || !validRaid(task,now)) {
                        pending.complete(task.botId(),task.sequence(),now,cooldown());
                        continue;
                    }
                    social.chatSendOrdered(task.botId(),new ChatSendReq("conversation-"+task.sequence(),
                            ChatChannel.valueOf(task.channel()),task.content(),null),now,task.sequence(),
                            task.channelKey(),task.peerId());
                    pending.complete(task.botId(),task.sequence(),now,cooldown());
                } catch (RuntimeException e) { LOG.warn("互动投递待重试，bot={} source={}：{}",task.botId(),task.sourceId(),e.toString()); }
            }
        } catch (RuntimeException e) { LOG.warn("互动惰性驱动失败，不影响请求：{}",e.toString()); }
    }

    private boolean validRaid(BotConversationStore.Pending task,long now) {
        if (task.marchId()==null) return true;
        var march=marches.findById(task.marchId()).orElse(null);
        var target=world.cityOf(task.peerId()).orElse(null);
        if (march==null || target==null || !march.playerId().equals(task.botId()) || !march.to().equals(target)
                || march.action()!=March.Action.ATTACK || march.status()!=March.Status.MARCHING) return false;
        try { attackGuard.guard(task.botId(),target,now); return true; }
        catch (com.ironoath.common.BizException e) { return false; }
    }

    private boolean eligible(String bot,String peer,String channel,String expectedKey) {
        if (bot.equals(peer) || bots.profileOf(peer)!=null || players.findByPlayerId(peer).isEmpty()
                || players.findByPlayerId(bot).isEmpty() || socialStore.hasBlocked(peer,bot) || socialStore.hasBlocked(bot,peer)
                || !bots.mayAppearIn(channel+"_CHAT")) return false;
        String botKey=key(bot,channel),peerKey=key(peer,channel);
        return expectedKey.equals(botKey) && expectedKey.equals(peerKey);
    }
    private String key(String player,String channel) {
        return switch(channel) {
            case "WORLD" -> "WORLD";
            case "ALLIANCE" -> socialStore.allianceOf(player)
                    .filter(a -> !a.isDisbanded() && a.memberIds().contains(player))
                    .map(a -> "ALLIANCE:"+a.id()).orElse("");
            case "SQUAD" -> socialStore.squadOf(player)
                    .filter(s -> !s.isDisbanded() && s.memberIds().contains(player))
                    .map(s -> "SQUAD:"+s.id()).orElse("");
            default -> "";
        };
    }
    private long cooldown() { return configs.longParam("CHAT_RATE_LIMIT_WINDOW_SECONDS")*1000L; }
    private static boolean active(BotProfile profile,long now) {
        return profile.persona().activeHours().contains(Instant.ofEpochMilli(now).atZone(DayKey.CALENDAR_ZONE).getHour());
    }
    private static boolean mentions(String content,String name) {
        return Pattern.compile("(?:^|\\s)@"+Pattern.quote(name)+"(?=$|[\\s，。！？、,:：;；!?])").matcher(content).find();
    }
    private static boolean roll(Rng rng,long chance) {
        return chance>=FixedPoint.SCALE || chance>0 && rng.range(0,FixedPoint.SCALE-1)<chance;
    }
}
