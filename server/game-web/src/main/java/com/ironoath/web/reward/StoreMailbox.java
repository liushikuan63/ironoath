package com.ironoath.web.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.mail.MailStore.MailRecord;
import com.ironoath.web.mail.MailStore.RewardLine;

/**
 * 职责：把 B04 的「发奖溢出转邮件」落到<b>真邮件</b>上（B12 §2 的两个生产者之一）。
 * 依赖：{@link MailStore}、{@link RewardNames}（附件显示名与任务/背包同源）、TimeService、配置表。
 *
 * <p><b>它换掉的是 {@code TransientRewardPorts.TransientMailbox}</b>：那份实现返回
 * {@code mail_overflow_1} 这种<b>进程内序号</b>，玩家拿到的 mailId 谁都查不回来 ——
 * 而它记下的是「玩家该得但当下没拿到」的东西。收口清单 #143 那一句「有名字没读者」
 * 在这里是要紧的反面：<b>有号而查不到</b>比没号更糟，因为它看起来像有凭据。
 *
 * <p><b>正文必须写明溢出数量与原因</b>（B04 验收 2 原文），所以这一段是服务端生成的，
 * 不是给客户端一个 key 让它自己拼（铁律 2）。三件信息缺一不可：
 * 少了「哪些东西」玩家不知道少了什么；少了「多少」他以为全给了；
 * 少了「为什么」（背包满还是仓库满）他不知道下一步该清哪个。
 * 第三件在本仓库里由 {@code ctx.source()}/{@code sourceRef} 给出<b>是谁发的</b>，
 * 而「装不下的那一个容器」在发放器返回的溢出明细里 —— 这里如实写「放不进背包或超过上限」，
 * 不去猜具体是哪一个：猜错的表现是玩家清了仓库而东西还是领不到。
 */
public final class StoreMailbox implements RewardPorts.Mailbox {

    private static final Logger LOG = LoggerFactory.getLogger(StoreMailbox.class);
    private static final int RETENTION_FALLBACK_DAYS = 30;

    private final MailStore store;
    private final RewardNames names;
    private final TimeService timeService;
    private final ConfigRegistry configs;

    public StoreMailbox(MailStore store, RewardNames names, TimeService timeService,
                        ConfigRegistry configs) {
        this.store = store;
        this.names = names;
        this.timeService = timeService;
        this.configs = configs;
    }

    @Override
    public String sendOverflow(String playerId, List<RewardItem> overflow, RewardContext ctx) {
        long now = timeService.serverNow();
        int days = retentionDays();
        List<RewardLine> lines = new ArrayList<>(overflow.size());
        for (RewardItem item : overflow) {
            lines.add(new RewardLine(item.type().name(), item.id(), item.count(), names.nameOf(item)));
        }
        String mailId = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        store.save(new MailRecord(mailId, playerId, "OVERFLOW", "奖励放不下，先存进邮箱",
                bodyOf(lines, ctx, days), lines, ctx.source() + ':' + ctx.sourceRef(), now,
                now + days * 86_400_000L, null, null));
        // INFO 而不是旧的 ERROR：溢出转邮件是设计好的路径，不是故障。
        // 真正的故障是「这批东西无处安放」，那会在领取时以 failed 的形式回到玩家面前
        LOG.info("溢出奖励已转邮件 playerId={} mailId={} 来源={}({}) 条数={} 保留={}天",
                playerId, mailId, ctx.source(), ctx.sourceRef(), lines.size(), days);
        return mailId;
    }

    /**
     * 主动把一批奖励发进邮箱（不是溢出、也不是补偿）。
     *
     * <p><b>与 {@link #sendOverflow} 分开的理由是正文</b>：溢出那封要告诉玩家「背包满了，清一清」——
     * 那是他可以去处理的事；而赛季结束未领的奖励与背包状态毫无关系，正文写"放不下"就是一句假话，
     * 玩家会去背包里翻一个根本不存在的容量问题。两件事在数据上同形、在对玩家的解释上完全不同，
     * 所以宁可多一个方法，也不让同一封邮件说两种话。
     *
     * @return 邮件 id
     */
    public String sendGrant(String playerId, String kind, String title, List<RewardItem> items,
                            String reason, RewardContext ctx) {
        long now = timeService.serverNow();
        int days = retentionDays();
        List<RewardLine> lines = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            lines.add(new RewardLine(item.type().name(), item.id(), item.count(), names.nameOf(item)));
        }
        String mailId = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // 正文必须逐条写出「什么、多少」：只写"有 N 档没领"的邮件等于让玩家拿着信去问客服 ——
        // 而这封信正是为了"不静默作废"才发的，说不清内容就失败了
        StringBuilder text = new StringBuilder(reason);
        for (RewardLine line : lines) {
            text.append("\n· ").append(line.name()).append(" ×").append(line.count());
        }
        text.append("\n请在 ").append(days).append(" 天内领取。");
        store.save(new MailRecord(mailId, playerId, kind, title, text.toString(), lines,
                ctx.source() + ':' + ctx.sourceRef(), now, now + days * 86_400_000L, null, null));
        LOG.info("奖励已发进邮箱 playerId={} mailId={} 类型={} 来源={}({}) 条数={} 保留={}天",
                playerId, mailId, kind, ctx.source(), ctx.sourceRef(), lines.size(), days);
        return mailId;
    }

    /** 正文：逐条写「什么、多少」，再写一句为什么与多久之内要领。 */
    private static String bodyOf(List<RewardLine> lines, RewardContext ctx, int days) {
        StringBuilder text = new StringBuilder("这次发放有一部分放不下（背包或仓库已满、或资源超过上限），"
                + "已先存进这封邮件，请在 ").append(days).append(" 天内领取：\n");
        for (RewardLine line : lines) {
            text.append("· ").append(line.name()).append(" ×").append(line.count()).append('\n');
        }
        text.append("来源：").append(ctx.source());
        if (!ctx.sourceRef().isBlank()) {
            text.append('（').append(ctx.sourceRef()).append('）');
        }
        return text.toString().stripTrailing();
    }

    private int retentionDays() {
        try {
            return (int) configs.longParam("MAIL_RETENTION_DAYS");
        } catch (RuntimeException e) {
            // 参数读不出来就用 B12 §2 的原文天数，但要把这件事喊出来：
            // 静默用一个默认值会让「改表没生效」变成永久且无人知晓的口径分叉
            LOG.error("读不到 global.MAIL_RETENTION_DAYS，本封邮件按 {} 天保留 —— "
                    + "配置表是保留天数的唯一来源，这条 ERROR 必须在上线前消失", RETENTION_FALLBACK_DAYS, e);
            return RETENTION_FALLBACK_DAYS;
        }
    }
}
