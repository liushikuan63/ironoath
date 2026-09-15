package com.ironoath.web.mail;

import java.util.List;
import java.util.Optional;

/**
 * 职责：邮件存储端口（B12 §2）—— 一封属于某玩家的、带附件与过期时刻的运行期记录。
 * 依赖：无（纯接口 + 本文件内的 record）。
 *
 * <p><b>为什么端口放在 game-web 而不是 game-core</b>：与 {@code BattleReportStore} 同一条理由 ——
 * game-core 里没有任何逻辑要读邮件（发奖溢出是<b>写</b>，读取的玩家侧全在 web），
 * 而端口的形状（按玩家查、按 id 原子改状态、按时刻清）只有 web 的存储实现需要满足。
 * 放到 core 就得把 {@code RewardLine} 再拆一遍裸 long，那是为迁就分层而扔掉类型信息。
 *
 * <p><b>为什么「领过没有」是端口上的一个原子动作而不是 service 里的两步</b>：
 * 领取必须恰好一次。{@code 读列表 → 判断没领过 → 发奖 → 写回已领} 这条路径在任何两个并发请求之间
 * 都会插进来（同一玩家换两个 requestId 点两下、或运维工单重投），结果是同一封邮件发两遍奖励 ——
 * 而重复发奖是经济口子，不是显示问题。所以这里只给 {@link #claim}：
 * <b>只有从「未领」翻到「已领」的那一次返回 true</b>，其余一律 false。
 * 发奖失败时用 {@link #releaseClaim} 把这一格翻回去，邮件于是仍然留在列表里等下一次领
 * （B12 验收 5 要求「失败邮件保留并提示」）。
 *
 * <p>内存版供 dev/test，Mongo 版供生产；两者语义必须逐条一致，
 * 由 {@code MailStoreEquivalenceTest} 按同一次调用比对返回值与异常文本来钉。
 */
public interface MailStore {

    /** 一封邮件。<b>没领的东西就写在这里</b>，所以 {@code rewards} 是这一记录的一部分而不是另一张表。 */
    record MailRecord(String mailId, String playerId, String kind, String title, String text,
                      List<RewardLine> rewards, String sourceRef, long createdAt, long expireAt,
                      Long readAt, Long claimedAt) {

        public MailRecord {
            require(mailId, "mailId");
            require(playerId, "playerId");
            require(kind, "kind");
            require(title, "title");
            require(text, "text");
            require(sourceRef, "sourceRef");
            if (expireAt <= createdAt) {
                throw new IllegalArgumentException("mail.expireAt 必须晚于 createdAt：过期早于生成等于这封邮件从不存在");
            }
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }

        /** 有没有附件可领。纯通知类邮件（运营公告）没有。 */
        public boolean hasAttachment() {
            return !rewards.isEmpty();
        }

        /** 还能不能领：有附件且没领过。 */
        public boolean claimable(long now) {
            return hasAttachment() && claimedAt == null && now < expireAt;
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("mail." + field + " 不得为空：一封没有" + field + "的邮件发不出去也查不回来");
            }
        }
    }

    /** 一条附件。与协议里的 {@code MailReward} 同形，字段名保持一致以免两侧各有一套叫法。 */
    record RewardLine(String type, String id, long count, String name) {

        public RewardLine {
            if (type == null || type.isBlank() || id == null || id.isBlank()) {
                throw new IllegalArgumentException("附件的 type/id 不得为空：领不到又说不清少了什么");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("附件 count 必须为正，实际=" + count
                        + "：0 条附件的正确表达是不放进列表，而不是放一条 0 —— 后者玩家会看到一个「×0」");
            }
            name = name == null ? "" : name;
        }
    }

    /** 写入一封邮件。同一 mailId 重复写入视为幂等（不覆盖），与战报端口同一约定。 */
    void save(MailRecord mail);

    /** 某玩家未过期的邮件，按 {@code createdAt} 倒序。<b>过期的一律不出现</b>（清理顺带发生在读路径）。 */
    List<MailRecord> listOf(String playerId, long nowMillis);

    Optional<MailRecord> findById(String playerId, String mailId);

    /**
     * 原子地把一封邮件从「未领」翻成「已领」。
     *
     * @return true 表示这一次赢得领取权；false 表示它已被领过、已过期或压根不存在
     */
    boolean claim(String playerId, String mailId, long nowMillis);

    /** 把刚拿到的领取权退回未领（发奖失败时用），返回是否退回成功。 */
    boolean releaseClaim(String playerId, String mailId);

    /** 标成已读。返回是否命中（不存在或已过期时 false）。已读过的再标一次仍是 true（幂等）。 */
    boolean markRead(String playerId, String mailId, long nowMillis);

    /** 清理过期邮件，返回清理条数。惰性调用，不跑定时器（B00 陷阱 2、B12 禁止项）。 */
    int purgeExpired(long nowMillis);

    /** 当前存量条数（健康度与测试用）。 */
    int count();

    /** 测试辅助：清空。 */
    void clear();
}
