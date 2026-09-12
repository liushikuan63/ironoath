package com.ironoath.web.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.dto.generated.AgentTickReq;
import com.ironoath.web.dto.generated.AgentTickResp;
import com.ironoath.web.ops.OpsTokenGuard;

/**
 * 职责：Bot tick 的运维入口（B11 §三 调度，收口清单 §五 C1）。
 * 依赖：{@link BotRuntimeService}、{@link OpsTokenGuard}、{@link TimeService}。
 *
 * <p><b>为什么这个动作要长成一个被外部调用的端点</b>：B11 §三/§十 禁止 {@code @Scheduled} 与常驻线程，
 * 而"到点就把 Bot 的待办推一轮"又必须有人按秒级节奏发起 —— 服务端自己不发起，就把这件事交给
 * 部署侧的调度系统（与 {@code POST /season/settle} 同一条形状：外部按时钟打，打多少次都不会出事）。
 *
 * <p><b>与结算端点的两处刻意不同</b>：
 * <ol>
 *   <li><b>不占幂等键</b>：结算是发钱，重复调用必须只发一次；tick 是泵，每一脚都该往前推。
 *       这里的 requestId 只做追踪号。</li>
 *   <li><b>失败不回滚任何东西</b>：一轮里某个 Bot 的动作失败只进 {@code failed} 计数
 *       （它下一次决策照常排期），所以这一脚打完就是打完了，没有"重放同一脚"的语义。</li>
 * </ol>
 *
 * <p><b>令牌闸门挡的是身份</b>：没配 {@code ironoath.ops.token} 时它一律拒绝（fail-closed），
 * 于是这台服上的 Bot 只在有人请求世界时靠 15 秒兜底动一动 —— 这是开发环境的正常形态，
 * 不是漏配也没人管的形态（漏配的后果在 prod 是"世界不动"，而 {@code ProductionReadiness} 会在启动时拒绝）。
 */
@RestController
@RequestMapping("/bot")
public class BotController {

    private final BotRuntimeService runtime;
    private final OpsTokenGuard ops;
    private final TimeService timeService;

    public BotController(BotRuntimeService runtime, OpsTokenGuard ops, TimeService timeService) {
        this.runtime = runtime;
        this.ops = ops;
        this.timeService = timeService;
    }

    /**
     * 推进一轮 Bot 决策与动作。
     *
     * @param req 必须带 requestId（追踪号）。为空时报 {@code REQUEST_ID_MISSING} ——
     *            不是因为要幂等，而是因为<b>没有追踪号的运维调用无法在日志里被归因</b>：
     *            一轮 tick 会写很多条 Bot 的日志，出事时要能回答"是哪一脚打的、什么时候"。
     */
    @PostMapping("/tick")
    public Result<AgentTickResp> tick(
            @RequestHeader(name = OpsTokenGuard.HEADER, required = false) String opsToken,
            @RequestBody(required = false) AgentTickReq req) {
        ops.require(opsToken);
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING,
                    "调用 tick 必须带 requestId（追踪号，用于在日志里归因是哪一次调度）");
        }
        long now = timeService.serverNow();
        Map<String, Long> report = runtime.tick(now);
        // 构造参数按**生成记录的声明顺序**逐个对齐（它取 schema 的 properties 序）。
        // 全部字段都是 long，顺序传错不会编译失败、也不会被任何守卫拦下 —— 只会静默串值。
        // 2026-09-12 的 C1b 抓到过一次真实的串值（required 与 properties 两处顺序不一致误导了作者），
        // 回归由 BotRuntimeTickTest.tickResponseFieldsAreNotPermuted 守着
        return Result.ok(new AgentTickResp(
                report.get("rounds"), report.get("processed"), report.get("executed"),
                report.get("deferred"), report.get("dropped"), report.get("failed"),
                report.get("budgetHit"), report.get("pending"),
                report.get("upgraded"), report.get("trained"),
                report.get("hunted"), report.get("gathered"),
                report.get("skipped"), report.get("unhandled"),
                now, report.get("agents")));
    }
}
