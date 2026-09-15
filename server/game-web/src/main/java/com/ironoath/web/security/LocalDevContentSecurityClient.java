package com.ironoath.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 职责：本地开发与单测用的内容安全端口实现 —— <b>一律放行</b>，且明确声明不能上生产。
 * 依赖：无。
 *
 * <p><b>为什么它不做本地敏感词库</b>：那会造出第二套判定，而它和微信侧的结论必然不同 ——
 * 表现是"本地测过没问题、上线被拒"或者反过来（本地拦了微信其实放行的正常词）。
 * 本地实现的职责只有一条：让链路在没有外部凭据的环境里能跑通；判定口径只有微信那一份。
 * 拒绝路径的证据靠单测注入的假实现，不靠这个类。
 *
 * <p>{@link #productionReady()} 固定 false：prod 下本 bean 在场会让服务拒绝启动
 * （{@code ProductionReadiness}），避免"忘了配 AppSecret 就上线、全服内容无人送检"。
 */
public final class LocalDevContentSecurityClient implements ContentSecurityClient {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDevContentSecurityClient.class);

    public LocalDevContentSecurityClient() {
        LOG.warn("内容安全使用本地开发实现：所有昵称/小队名/联盟名/聊天都**不会**真的送检。"
                + "上线前必须配置 WECHAT_APP_ID / WECHAT_APP_SECRET "
                + "并切到 WeChatContentSecurityClient（prod 下本实现会让服务拒绝启动）");
    }

    @Override
    public Verdict check(String openId, Scene scene, String content) {
        return Verdict.ALLOWED;
    }
}
