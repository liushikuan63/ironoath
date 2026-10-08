package com.ironoath.web.social;

/**
 * 社交事件 id 的唯一产地。
 *
 * <p><b>为什么要有这个类</b>（台账 #819，与 #802 的集结 id、#818 的提案 id 同族）：原先五处各自
 * 拼 {@code "evt_" + 类型 + 相关对象 + 毫秒}，而 {@code SocialStore.pushEvent} 是往 List 里追加、
 * 不去重，{@code ackEvents} 又按 id 做 {@code removeIf(contains)} ⇒ 同一玩家在同一毫秒里拿到两条
 * 「同类型 + 同相关对象」的事件时它们共用一个 id，玩家读掉一条就把另一条一起删没了。
 * 这不是概率问题：{@code MarchAppService.processDue(playerId, now)} 是<b>一个 now 扫一批到期行军</b>，
 * 同一批里同一守方挨到两次打就必然同 id。
 *
 * <p>尾巴只负责唯一性，可读的前缀仍由各调用方自己拼 —— 让日志与排查一眼看出它是哪类事件。
 */
public final class SocialEventIds {

    private SocialEventIds() {
    }

    /** {@code 可读前缀 + "_" + 16 位十六进制随机尾}。 */
    public static String of(String readablePrefix) {
        return readablePrefix + "_"
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
