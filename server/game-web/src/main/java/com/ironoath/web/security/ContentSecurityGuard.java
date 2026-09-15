package com.ironoath.web.security;

import com.ironoath.common.ErrorCode;
import com.ironoath.common.BizException;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 职责：把"玩家可自由填写的内容要送检"这件事收成一个调用点（B15 §3 / 上线检查清单 §二 7）。
 * 依赖：{@link ContentSecurityClient}（端口）、{@link PlayerRepository}（从账号键取 openid）。
 *
 * <p><b>命中之后的处置是"拒绝 + 提示"，不是静默替换</b> —— 上线检查清单 §二 7 的原话。
 * 替换掉敏感字的做法看起来更"友好"，但玩家会以为平台在替他改稿，而审核看到的是改过的版本，
 * 两边都没有人是清醒的；拒绝并说清原因，至少玩家知道发生了什么。
 *
 * <p><b>没问成的时候放行，但响亮记账</b>（这是本轮定下的取舍，理由写在这里）：
 * 内容安全是外部依赖，它抖一下就把全服聊天与建号一起拒掉，等于把一次外部故障升级成全服不可用 ——
 * 而那正是玩家与客服最先看到的东西。反过来，放行期间的漏检有 ERROR 日志与累计计数可查。
 * 两害相权，选"可用但可查"。**这条是取舍不是定论**：如果合规侧要求"N 分钟送检失败即熔断"，
 * 改这一处 switch 即可，不需要动四个调用点。
 */
@Component
public class ContentSecurityGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ContentSecurityGuard.class);

    private final ContentSecurityClient client;
    private final PlayerRepository players;

    /** 未能送检的累计次数：只放行不记账的话，"内容安全空转了多久"在日志里查不出来。 */
    private final AtomicLong notCheckedCount = new AtomicLong();

    public ContentSecurityGuard(ContentSecurityClient client, PlayerRepository players) {
        this.client = client;
        this.players = players;
    }

    /**
     * 送检一段内容；判定违规就抛（业务错误 + 可读提示）。
     *
     * @param playerId   送检人（用于取 openid）
     * @param scene      微信侧的送检场景
     * @param content    待检内容
     * @param rejectCode 该调用点自己的"内容不合法"错误码（昵称/小队名/联盟名/聊天各有一个）
     * @param what       出提示时用的名词，例如「昵称」「小队名」
     */
    public void requireClean(String playerId, ContentSecurityClient.Scene scene, String content,
                             ErrorCode rejectCode, String what) {
        requireCleanForAccount(players.findByPlayerId(playerId)
                        .map(PlayerSave::deviceId).orElse(null),
                scene, content, rejectCode, what);
    }

    /**
     * 同上，但直接收账号键。
     *
     * <p><b>为什么需要这个入口</b>：昵称是在**建档之前**检查的 —— 那一刻存档还不存在，
     * 唯一知道 openid 的东西是刚算出来的账号键。硬要它先查一次存档再去送检，
     * 结果是新号的昵称永远送检不了（而新号恰恰是昵称唯一会变的时刻）。
     */
    public void requireCleanForAccount(String accountKey, ContentSecurityClient.Scene scene,
                                       String content, ErrorCode rejectCode, String what) {
        String openId = openIdOf(accountKey);
        if (openId == null) {
            // 没有 openid 就没有送检对象：这不是外部故障，是"这个账号本来就不带微信身份"。
            // 本地开发者号每次都会走到这里，所以按 WARN 记 —— 若在此报 ERROR，
            // 表现是本地跑一轮测试刷出几十条 ERROR，而"ERROR 满天飞"会训练出忽略 ERROR 的习惯，
            // 那比漏一条日志贵得多。真正的外部故障（下面那一档）才是 ERROR。
            notCheckedCount.incrementAndGet();
            LOG.warn("内容未送检：账号不是微信账号（无 openid），项目={} 不是内容没问题", what);
            return;
        }
        switch (client.check(openId, scene, content)) {
            case ALLOWED -> {
                // 通过：什么都不做
            }
            case RISKY -> throw new BizException(rejectCode,
                    what + "含有平台不允许的内容，请修改后重试");
            case UNAVAILABLE -> {
                long total = notCheckedCount.incrementAndGet();
                LOG.error("内容送检失败，已按既定取舍放行（累计 {} 次）：scene={} 项目={}。"
                        + "这是「没问成」而不是「内容没问题」—— 连续出现要当成外部依赖故障处理",
                        total, scene, what);
            }
        }
    }

    /** 累计未能送检次数（运维读它判断内容安全是不是在空转）。 */
    public long notCheckedCount() {
        return notCheckedCount.get();
    }

    /**
     * 账号键 {@code wx:<openid>} 里取出 openid。
     *
     * <p>非微信账号（本地设备号建档的开发者号）取不出 openid，于是送检只能是"没问成" ——
     * 这一档在生产不会出现（线上账号一律走微信登录），本地则正好靠它跑通除送检外的整条链路。
     */
    private String openIdOf(String accountKey) {
        if (accountKey == null || !accountKey.startsWith(WeChatCodeExchanger.WECHAT_ACCOUNT_PREFIX)) {
            return null;
        }
        return accountKey.substring(WeChatCodeExchanger.WECHAT_ACCOUNT_PREFIX.length());
    }
}
