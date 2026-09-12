package com.ironoath.web.web;

import com.ironoath.web.controller.CityController;
import com.ironoath.web.service.PowerRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 职责：在任何一次成功的写操作之后重算玩家战力（B08 §1）。
 * 依赖：{@link PowerRefreshService}。
 *
 * <p><b>为什么挂在 HTTP 边界而不是各个 service 里</b>：战力是城建 + 部队 + 武将的派生值，
 * 能改变它的写路径有二十多个（建造、升级、加速、取消、训练、治疗、武将升级/升星/觉醒/技能/装备/
 * 编队、抽卡……）。逐个挂钩子必然会漏，而漏掉的表现是「我练了兵，战力没变」——
 * 不会有任何测试变红，只会在玩家社区里变成一句「这游戏的战力是假的」。
 * 挂在边界上，新端点自动被覆盖，不需要写它的人记得这件事。
 *
 * <p><b>只对写方法生效</b>：GET 不改状态，重算纯属浪费。
 * 但 GET 也可能因为惰性结算而让战力变化（例如离线产出结算不会，但训练队列到点会）——
 * 那种变化会在玩家下一次写操作或下一次搜索时被算出来，不影响判定正确性，
 * 因为圈层判定用的始终是「当场重算」而不是「池里的值」（见 {@code TargetSearchService}）。
 *
 * <p><b>失败只记日志，绝不向上抛</b>：响应此时已经写完，
 * 抛异常会让一个已经成功的操作在客户端表现为失败，玩家会重发请求 ——
 * 而重发一个已经扣过资源的请求，正是幂等设计要防的最坏情况。
 *
 * <p><b>注意 postHandle 在失败路径上也会跑</b>：Spring 的 {@code processDispatchResult}
 * 先用 HandlerExceptionResolver 处理异常、再调 {@code applyPostHandle}，
 * 所以一个被 {@code GlobalExceptionHandler} 接住的 BizException 之后本方法照样执行。
 * 这是无害的：失败的请求没有改变任何状态，而 {@code PowerRefreshService}
 * 在算出来的战力与存档一致时不写库 —— 但这层「无害」依赖的是重算的幂等性，
 * 不是「这里只在成功时跑」，写代码的人必须知道这一点。
 */
@Component
public class PowerRefreshInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PowerRefreshInterceptor.class);

    private final PowerRefreshService powerRefreshService;

    public PowerRefreshInterceptor(PowerRefreshService powerRefreshService) {
        this.powerRefreshService = powerRefreshService;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, org.springframework.web.servlet.ModelAndView modelAndView) {
        if (!isMutating(request.getMethod())) {
            return;
        }
        String playerId = request.getHeader(CityController.PLAYER_HEADER);
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        try {
            powerRefreshService.refresh(playerId);
        } catch (RuntimeException e) {
            // 吞掉并记警告：见类注释。这里最典型的失败是乐观锁冲突（同一玩家的并发写），
            // 下一次请求会重算，不需要重试，也不该让玩家看到失败
            LOG.warn("写操作后的战力重算失败，将在下一次请求补算 playerId={} uri={} 原因={}",
                    playerId, request.getRequestURI(), e.toString());
        }
    }

    private static boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }
}
