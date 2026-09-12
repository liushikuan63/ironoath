package com.ironoath.core.city;

import com.ironoath.common.ErrorCode;

/**
 * 职责：升级前置校验的结构化结果 —— B03 要求「不满足时返回结构化错误，客户端显示『还缺 XXX』，
 *       而不是笼统的『条件不足』」。
 * 依赖：game-common 的 ErrorCode（纯 Java）。
 *
 * <p>为什么必须结构化：玩家看到「条件不足」会去猜，猜到第三次就退游了。
 * 看到「需要主城 8 级，当前 6 级」他会去升主城 —— 错误提示本身就是引导。
 * 这也是 B03 禁止项「不要让 getRow 式的静默失败出现」的正面对应物。
 *
 * @param passed  是否通过全部校验
 * @param code    失败原因的错误码；通过时为 {@link ErrorCode#OK}
 * @param need    需要什么（人类可读，如「主城 8 级」「木材 12000」）
 * @param current 当前是什么（如「主城 6 级」「木材 3400」）
 */
public record UpgradeCheck(boolean passed, ErrorCode code, String need, String current) {

    /** 校验通过。 */
    public static UpgradeCheck ok() {
        return new UpgradeCheck(true, ErrorCode.OK, "", "");
    }

    /**
     * 校验失败。
     *
     * @param code    错误码，必须是城建段位（3xxx）
     * @param need    需要什么
     * @param current 当前是什么
     */
    public static UpgradeCheck fail(ErrorCode code, String need, String current) {
        if (code == null || code.isSuccess()) {
            throw new IllegalArgumentException("fail 需要一个非成功的错误码");
        }
        if (need == null || need.isBlank() || current == null || current.isBlank()) {
            // need/current 为空就等于退回成「条件不足」，正是本类要避免的事
            throw new IllegalArgumentException("结构化错误必须同时给出 need 与 current，code=" + code);
        }
        return new UpgradeCheck(false, code, need, current);
    }

    /** 通过则原样返回，失败则抛出携带结构化详情的业务异常。 */
    public void orThrow() {
        if (!passed) {
            throw new com.ironoath.common.BizException(code, detail());
        }
    }

    /** 拼成「需要 X，当前 Y」的详情文本，用于 Result.detail 与日志。 */
    public String detail() {
        return "需要 " + need + "，当前 " + current;
    }
}
