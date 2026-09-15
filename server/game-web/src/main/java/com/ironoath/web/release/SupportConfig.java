package com.ironoath.web.release;

import com.ironoath.web.dto.generated.SupportEntry;

/**
 * 职责：客服与退款入口的部署配置（上线检查清单 §二 8/9）。
 * 依赖：无（纯数据）。
 *
 * <p><b>为什么走环境变量而不是配置表</b>：corpId 标识的是**微信账号侧**的客服配置，
 * 与 AppID / AppSecret 同族 —— 它不是玩法数值，改它的人也不是策划。配置表是"玩法数值的家"，
 * 把部署凭据塞进去会让"这张表随包下发"这件事多一类不该外泄的东西。
 *
 * <p><b>为什么客户端拿到的是一份"配置"而不是两个常量</b>：入口必须一级可见，
 * 而"配没配"这件事只有服务端知道；客户端自己写死一套，换环境就得重新发版。
 */
public record SupportConfig(String corpId, String url) {

    /** 三项都齐才算配好：只配一半时点下去照样打不开客服，那比"未配置"更难解释。 */
    public boolean configured() {
        return corpId != null && !corpId.isBlank() && url != null && !url.isBlank();
    }

    /**
     * 转成下发给客户端的形态。
     *
     * <p><b>没配时返回 null 而不是空串</b>：客户端据此显示「本环境未配置客服」，
     * 而"配置了一个空的 corpId"和"没配置"在下游长得一样时，排查会先怀疑微信侧。
     */
    public SupportEntry toEntry() {
        return configured() ? new SupportEntry(corpId, url) : null;
    }
}
