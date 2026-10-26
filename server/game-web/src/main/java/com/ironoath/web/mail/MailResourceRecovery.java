package com.ironoath.web.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;

/** Owner-locked pure resource mail recovery. Gold/items, equipment and paid lanes remain separate. */
final class MailResourceRecovery {
    record Delivered(MailClaimPlan plan, GrantResult result) {}
    private final MailStore store;
    private final ConfigRegistry configs;
    private final RewardService rewards;
    MailResourceRecovery(MailStore store, ConfigRegistry configs, RewardService rewards) {
        this.store = store; this.configs = configs; this.rewards = rewards;
    }
    boolean supports(MailStore.MailRecord mail) {
        String source = mail.sourceRef().toLowerCase(Locale.ROOT);
        if (source.startsWith("pay:") || source.startsWith("paid") || source.startsWith("payment:")) return false;
        return !mail.rewards().isEmpty() && mail.rewards().stream().allMatch(line ->
                ("RESOURCE".equals(line.type()) || "STAMINA".equals(line.type())) && !"GOLD".equals(line.id()));
    }
    MailClaimPlan freeze(MailStore.MailRecord mail, long eligibleAt) {
        if (!supports(mail)) throw new IllegalArgumentException("此邮件不属于普通纯资源恢复切片");
        // Validate the complete immutable attachment list before accepting or touching any asset.
        for (var line : mail.rewards()) {
            if (configs.getResource(line.id()) == null) throw new IllegalArgumentException("资源附件配置缺失");
        }
        long expires = Math.addExact(eligibleAt, Math.multiplyExact(configs.longParam("MAIL_RETENTION_DAYS"), 86_400_000L));
        return new MailClaimPlan(MailClaimPlan.keyOf(mail.mailId()), mail.mailId(), mail.rewards(), eligibleAt, expires);
    }
    List<Delivered> recover(String playerId) {
        var out = new ArrayList<Delivered>();
        for (var mail : store.pendingOf(playerId)) {
            var plan = mail.acceptedPlan();
            out.add(new Delivered(plan, deliverAndFinish(playerId, plan)));
        }
        return List.copyOf(out);
    }
    GrantResult deliverAndFinish(String playerId, MailClaimPlan plan) {
        var facts = Set.of("mail-overflow-created:" + plan.acceptedAt(), "mail-overflow-expires:" + plan.overflowExpireAt());
        var items = plan.attachments().stream().map(line -> new RewardItem(RewardType.valueOf(line.type()), line.id(), line.count())).toList();
        var result = rewards.grantMailResources(playerId, items,
                new RewardContext("mail_resource", "mail:" + plan.mailId(), "mail_resource:" + plan.mailId(), true, facts));
        if (!store.completeAccepted(playerId, plan.mailId(), plan.businessKey()))
            throw new IllegalStateException("邮件领取计划结案失败，收据已保留");
        return result;
    }
}
