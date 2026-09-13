package com.ironoath.web.security;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;

/**
 * 职责：微信登录换取失败的统一异常（{@link ErrorCode#WECHAT_LOGIN_FAILED}）。
 * 依赖：game-common。
 *
 * <p>单独一个类是为了让调用方一眼看出"这是外部渠道的失败"，而不是把它当成
 * {@code PARAM_INVALID} 之类的客户端错误 —— 前者要在客户端提示"重试登录"，
 * 后者要去查客户端传了什么。
 */
public class WeChatLoginException extends BizException {

    public WeChatLoginException(String detail) {
        super(ErrorCode.WECHAT_LOGIN_FAILED, detail);
    }
}
