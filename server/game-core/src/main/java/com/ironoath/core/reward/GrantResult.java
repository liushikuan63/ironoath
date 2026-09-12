package com.ironoath.core.reward;

import java.util.List;

/**
 * 职责：发奖结果 —— 实际发放、溢出部分、补偿记录三件事都必须显式返回。
 * 依赖：无（纯数据）。
 *
 * <p>为什么溢出要单独返回而不是静默截断：B04 验收 2 要求「邮件正文写明溢出数量」，
 * 客户端也要给玩家一个「背包满了，已转邮件」的提示。
 * 静默截断会让玩家以为拿到了全部奖励，等发现少了就是客服工单。
 *
 * @param granted      实际发放成功的部分
 * @param overflow     溢出部分（背包满 / 资源超上限），已转邮件或被丢弃
 * @param mailId       溢出转邮件的邮件 id；未转邮件时为 null
 * @param compensationId 发放过程中出错、已进补偿队列的记录 id；全部成功时为 null。
 *                       B04 验收 7 要求「异常时不静默：落日志 + 补偿队列有记录」，
 *                       这个字段就是那条记录的可查凭证
 */
public record GrantResult(
        List<RewardItem> granted,
        List<RewardItem> overflow,
        String mailId,
        String compensationId) {

    public GrantResult {
        granted = List.copyOf(granted);
        overflow = List.copyOf(overflow);
    }

    /** 全部发放成功、无溢出。 */
    public static GrantResult full(List<RewardItem> granted) {
        return new GrantResult(granted, List.of(), null, null);
    }

    public boolean hasOverflow() {
        return !overflow.isEmpty();
    }

    public boolean hasCompensation() {
        return compensationId != null;
    }

    /**
     * 是否完全成功（无溢出、无补偿）。
     *
     * <p><b>目前只有测试调用它</b>：生产路径要的不是一个布尔，而是"哪一部分没进去" ——
     * 调用方分别读 {@link #hasOverflow()} 与 {@link #compensationId()} 去写日志、发补发凭证。
     * 所以并不存在一条"发放是否干净"的统一闸门，别按那个假设加逻辑或把它删掉。
     */
    public boolean isClean() {
        return overflow.isEmpty() && compensationId == null;
    }
}
