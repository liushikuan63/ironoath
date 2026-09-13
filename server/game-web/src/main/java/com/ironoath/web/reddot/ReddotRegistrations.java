package com.ironoath.web.reddot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.reddot.ReddotTree;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：红点树的**唯一**注册点 —— 决定"哪些叶子存在、各自的条件读谁的查询"。
 * 依赖：{@link SocialAppService} 暴露的"有没有可做的事"查询、{@link TimeService}。
 *
 * <p><b>为什么必须集中在一个文件里</b>：B12 把红点列为最容易做砸的一块，理由是"每加一个功能
 * 都要回去改一堆散落的判断，维护成本指数上升"。而散落的判断不会让任何测试变红：漏改的表现是
 * 「有红点点进去没东西」或「明明有事却没红点」，两种都只会被玩家当 bug 报上来。
 * 所以 {@code scripts/check-no-scattered-reddot.sh} 规定：红点相关标识符只允许出现在
 * 红点模块与本注册点里。
 *
 * <p><b>分工线画在这里</b>：业务 Service 只回答"有没有可做的事"（{@code hasHelpable}），
 * "该不该亮"由这棵树决定。反过来让每个 Service 自己吐一个布尔给 UI，就是散落的开始 ——
 * 而那一步做完测试还是全绿的。
 *
 * <p><b>条件函数只能读已经存在的判定</b>：这里不写任何"什么算有事做"的逻辑。
 * 抄一份谓词=第二份真相，表现正是徽标与点进去的内容不一致（见收口清单 #42）。
 */
@Configuration
public class ReddotRegistrations {

    private static final Logger LOG = LoggerFactory.getLogger(ReddotRegistrations.class);

    @Bean
    public ReddotTree reddotTree(SocialAppService social, CityAppService city, TimeService time) {
        ReddotTree tree = new ReddotTree();
        tree.register("social/help", playerId -> social.hasHelpable(playerId, time.serverNow()),
                "有能帮的互助请求（额度未用完）");
        tree.register("social/invite", social::hasPendingInvite,
                "有待处理的入盟申请或集结邀请");
        tree.register("social/events", social::hasUnreadEvents,
                "有未读社交事件");
        // 复用升级流程自己的算式（CityAppService#attemptOf → CityState#validateUpgrade），
        // 不在这里比较资源与等级 —— 那样红点会与「点进去到底能不能升」各说各话
        tree.register("city/building", playerId -> city.hasUpgradable(playerId, time.serverNow()),
                "有已放置的建筑此刻能开始升级（不含需要地块坐标的首次建造）");
        // 叶子数必须被看见：它长期停在个位数就说明有人在业务模块里自己判红点
        LOG.info("红点树注册完成：{} 个叶子 {}。新增功能要亮红点，只改这里，不要在业务 Service 里判断",
                tree.leafCount(), tree.leafKeys());
        return tree;
    }
}
