package com.ironoath.web.reward;

import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;

import java.util.List;

/**
 * 职责：非资源非道具奖励这一类端口的<b>过渡实现</b>（目前只剩 {@link UnsupportedExtras} 一件）。
 * 依赖：game-core 的端口（纯 Java + slf4j）。
 *
 * <h2>这里的东西为什么可以「只是过渡」</h2>
 * 发放器的三段式结果（granted / overflow / compensationId）必须能被端到端验证，
 * 所以过渡实现不能留空 —— 但<b>过渡不等于可以静默</b>：本类留下的每一件事都有响亮的失败。
 *
 * <h2>两件已经从这里搬走的事（记下来防有人再搬回来）</h2>
 * <ol>
 *   <li><b>溢出邮件</b>（B12 §2）：原来就在本类里，返回 {@code mail_overflow_1} 这种进程内序号，
 *       谁都查不回来。现在走 {@code StoreMailbox} + {@code MailStore}，
 *       邮件是可查询、按 MAIL_RETENTION_DAYS 过期的真记录。</li>
 *   <li><b>补偿队列</b>（B04 验收 7）：原来是一份 {@code TransientCompensation}，
 *       记录只在内存与日志里，且 id 用实例内自增序号 —— 重启后序号从 1 重来，
 *       新实例的第一笔欠账会被当成"重复写入"静默丢掉。
 *       现在走 {@link StoreCompensation} + {@link RewardCompensationStore}，
 *       这是「发奖失败不静默」的最后兜底，也是玩家投诉唯一查得回来的依据。</li>
 * </ol>
 *
 * <p>搬走之后本类只剩 {@link UnsupportedExtras} 一件，而它<b>不是待补的洞</b>：
 * 未实现的奖励类型必须响亮地失败并进补偿台账，静默返回「发放成功」会让玩家看到
 * 「已获得 SSR 碎片 ×5」但账户里什么都没有。B06（武将）与 B15（特权）已各自接上真实现，
 * 剩下的体力（B09）落地前，走到这里仍然应当抛。
 */
public final class TransientRewardPorts {

    private TransientRewardPorts() {
    }

    /**
     * 非资源非道具奖励：一律抛异常，让发放器把它记进补偿台账。
     *
     * <p>identity-exempt: 端口签名带的 playerId 在本实现里读不到用途 —— 它每一次调用都抛，
     * 而"抛给谁"由调用方（发放器）拿着身份去记台账，这里没有任何一行需要知道是谁。
     *
     * <p>刻意不返回「发放成功」：静默成功是最坏的结果 —— 玩家看到已获得但账户里没有，
     * 而且系统里没有任何痕迹可查。响亮地失败并留下补偿记录，才是可运维的行为。
     */
    public static final class UnsupportedExtras implements RewardPorts.Extras {

        @Override
        public long grant(String playerId, RewardType type, String id, long count, long now) {
            throw new UnsupportedOperationException("奖励类型 " + type + "（id=" + id + "）尚未落地："
                    + switch (type) {
                        case HERO, HERO_FRAGMENT -> "由 B06 武将系统实现";
                        case STAMINA -> "由 B09 PVE 与关卡内容实现";
                        // B19 已经落了真实现（HeroFragmentExtras → PaidPrivilegeGrants）。
                        // 走到这里只可能是装配漏了 @Primary 的那个 bean —— 是环境问题，不是功能没写
                        case PRIVILEGE -> "特权由 B19 的 HeroFragmentExtras 处理，落到这里说明装配缺了它";
                        case RESOURCE, ITEM -> "不应走到这里：资源与道具由 Wallet / Bag 处理";
                    });
        }
    }
}
