package com.ironoath.web.store.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ironoath.web.mail.MailStore;

/**
 * 职责：邮件存储的进程内实现（dev / 单测）。
 * 依赖：{@link MailStore} 端口、slf4j。
 *
 * <p><b>启动即打 WARN</b>：本实现重启即丢，而邮件里装着<b>没领走的奖励</b> ——
 * 丢了就是玩家资产消失，比丢一份战报严重。它与 {@code TransientRewardPorts} 的假 mailId
 * 是同一件事的两端：正因为这个实现会丢，B12 才必须把生产实现换上（{@code MongoMailStore}）。
 *
 * <p><b>全部改动都在同一把进程内锁里</b>：{@link #claim} 的「查一眼再翻格」必须是原子的，
 * 否则同一玩家两次点击可能都读到「未领」。这不是 B13 禁止项说的「用 synchronized 做跨节点锁」——
 * 跨节点的那一道由 Mongo 侧的<b>条件更新</b>（matched 上 claimedAt 为空）负责，
 * 两把闸各管自己那一层，谁也不替补谁。
 */
public final class InMemoryMailStore implements MailStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMailStore.class);

    /** mailId → 邮件。用 LinkedHashMap 只是为了让「同毫秒生成」的相对顺序稳定，不参与判定。 */
    private final Map<String, MailRecord> mails = new LinkedHashMap<>();

    @Override
    public synchronized void save(MailRecord mail) {
        MailRecord previous = mails.putIfAbsent(mail.mailId(), mail);
        if (previous != null) {
            LOG.warn("重复写入同一封邮件，已按幂等忽略 mailId={} —— 生成 id 的地方不该重发，除非是一次补投",
                    mail.mailId());
        }
    }

    @Override
    public synchronized List<MailRecord> listOf(String playerId, long nowMillis) {
        List<MailRecord> out = new ArrayList<>();
        for (MailRecord mail : mails.values()) {
            if (mail.playerId().equals(playerId) && mail.expireAt() > nowMillis) {
                out.add(mail);
            }
        }
        // 与 Mongo 版逐字同一条排序：createdAt 倒序，同刻按 mailId 升序。
        // 两侧排序不同会让"第一页看到的邮件"在 dev 与 prod 不一样，而那是最容易漏测的差异
        out.sort(Comparator.comparingLong(MailRecord::createdAt).reversed()
                .thenComparing(MailRecord::mailId));
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<MailRecord> findById(String playerId, String mailId) {
        MailRecord mail = mails.get(mailId);
        return mail == null || !mail.playerId().equals(playerId) ? Optional.empty() : Optional.of(mail);
    }

    @Override
    public synchronized boolean claim(String playerId, String mailId, long nowMillis) {
        MailRecord mail = mails.get(mailId);
        if (mail == null || !mail.playerId().equals(playerId) || !mail.claimable(nowMillis)) {
            return false;
        }
        mails.put(mailId, withClaimedAt(mail, nowMillis));
        return true;
    }

    @Override
    public synchronized boolean releaseClaim(String playerId, String mailId) {
        MailRecord mail = mails.get(mailId);
        if (mail == null || !mail.playerId().equals(playerId) || mail.claimedAt() == null) {
            return false;
        }
        mails.put(mailId, withClaimedAt(mail, null));
        return true;
    }

    @Override
    public synchronized boolean markRead(String playerId, String mailId, long nowMillis) {
        MailRecord mail = mails.get(mailId);
        if (mail == null || !mail.playerId().equals(playerId) || mail.expireAt() <= nowMillis) {
            return false;
        }
        if (mail.readAt() != null) {
            return true;
        }
        mails.put(mailId, new MailRecord(mail.mailId(), mail.playerId(), mail.kind(), mail.title(),
                mail.text(), mail.rewards(), mail.sourceRef(), mail.createdAt(), mail.expireAt(),
                nowMillis, mail.claimedAt()));
        return true;
    }

    @Override
    public synchronized int purgeExpired(long nowMillis) {
        List<String> doomed = new ArrayList<>();
        for (MailRecord mail : mails.values()) {
            if (mail.expireAt() <= nowMillis) {
                doomed.add(mail.mailId());
            }
        }
        doomed.forEach(mails::remove);
        return doomed.size();
    }

    @Override
    public synchronized List<MailRecord> recent(String playerId, long sinceCreatedAtMillis,
                                                long nowMillis, int limit) {
        if (limit <= 0) {
            // 与 Mongo 侧同一条：limit<=0 不是"不限条数"而是"一条都别给"。
            // 当成无限制会让一个手滑传 0 的调用变成全集合扫描 + 本文件最大的响应
            return List.of();
        }
        List<MailRecord> out = matching(playerId, sinceCreatedAtMillis, nowMillis);
        out.sort(Comparator.comparingLong(MailRecord::createdAt).reversed()
                .thenComparing(MailRecord::mailId));
        return List.copyOf(out.subList(0, Math.min(limit, out.size())));
    }

    @Override
    public synchronized int countRecent(String playerId, long sinceCreatedAtMillis, long nowMillis) {
        return matching(playerId, sinceCreatedAtMillis, nowMillis).size();
    }

    /** 过滤条件只写这一处：列表与总数必须同文，否则两个数会各说一套（两侧实现同一条纪律）。 */
    private List<MailRecord> matching(String playerId, long sinceCreatedAtMillis, long nowMillis) {
        boolean allPlayers = playerId == null || playerId.isBlank();
        List<MailRecord> out = new ArrayList<>();
        for (MailRecord mail : mails.values()) {
            if ((allPlayers || mail.playerId().equals(playerId))
                    && mail.createdAt() >= sinceCreatedAtMillis && mail.expireAt() > nowMillis) {
                out.add(mail);
            }
        }
        return out;
    }

    @Override
    public synchronized int count() {
        return mails.size();
    }

    @Override
    public synchronized void clear() {
        mails.clear();
    }

    private static MailRecord withClaimedAt(MailRecord mail, Long claimedAt) {
        return new MailRecord(mail.mailId(), mail.playerId(), mail.kind(), mail.title(), mail.text(),
                mail.rewards(), mail.sourceRef(), mail.createdAt(), mail.expireAt(), mail.readAt(), claimedAt);
    }
}
