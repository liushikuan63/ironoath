package com.ironoath.web.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.dto.generated.BlockListView;
import com.ironoath.web.dto.generated.FollowReq;
import com.ironoath.web.dto.generated.FriendListView;
import com.ironoath.web.dto.generated.BlockReq;
import com.ironoath.web.dto.generated.HelpResp;
import com.ironoath.web.dto.generated.ReportReq;
import com.ironoath.web.dto.generated.ReportResp;
import com.ironoath.core.reddot.ReddotTree;
import com.ironoath.web.dto.generated.HelpReq;
import com.ironoath.web.dto.generated.PermissionListResp;
import com.ironoath.web.dto.generated.ReddotNodeView;
import com.ironoath.web.dto.generated.ReddotTreeResp;
import com.ironoath.web.dto.generated.SocialEventAckReq;
import com.ironoath.web.dto.generated.SocialHelpListResp;
import com.ironoath.web.dto.generated.SocialSummaryResp;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：社交域 HTTP 入口 —— 汇总、权限、互助帮助、事件已读（B10 §2/§4/§5）。
 * 依赖：Spring Web、{@link SocialAppService}。
 *
 * <p><b>汇总与权限是 GET，帮助与已读是 POST</b>：前者无副作用可以安全重试，
 * 后者会扣每日额度、会清红点，重放就有实际后果 —— 所以它们必须带 requestId 走幂等。
 *
 * <p>{@code GET /social/summary} 是社交页的唯一入口：三层社交（小队 / 联盟 / 国家）
 * 加红点加未读事件一次给全。拆成三个接口的话，玩家打开面板要等三次往返，
 * 而弱网下三次往返的失败率是单次的好几倍 —— 表现就是「社交页一半有一半没有」。
 */
@RestController
@RequestMapping("/social")
public class SocialController {

    private final SocialAppService social;
    private final TimeService timeService;
    private final ReddotTree reddot;

    public SocialController(SocialAppService social, TimeService timeService, ReddotTree reddot) {
        this.social = social;
        this.timeService = timeService;
        this.reddot = reddot;
    }

    /**
     * GET /social/reddot：整棵红点树（B12 §4）。
     *
     * <p>与 {@code /social/summary} 里的 {@code pendingHelps} / {@code pendingInvites}
     * 读的是<b>同一批判定</b>（{@code SocialAppService#hasHelpable} 等），只是消费方式不同：
     * 摘要给面板显示数字，这里给全局角标做树形聚合。刻意不做"自己再判一遍"，
     * 那正是徽标与点进去内容不一致的成因（见收口清单 #42）。
     */
    @GetMapping("/reddot")
    public Result<ReddotTreeResp> reddot(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        String who = requirePlayer(playerId);
        ReddotTree.NodeState whole = reddot.subtree("", who);
        List<ReddotNodeView> nodes = new ArrayList<>(whole.children().size());
        for (ReddotTree.NodeState child : whole.children()) {
            nodes.add(toView(child));
        }
        return Result.ok(new ReddotTreeResp(nodes, reddot.leafCount(), timeService.serverNow()));
    }

    /** NodeState → 协议形状。children 一律给数组（叶子给空数组而不是 null），客户端据此区分"是叶子"。 */
    private static ReddotNodeView toView(ReddotTree.NodeState state) {
        List<ReddotNodeView> children = new ArrayList<>(state.children().size());
        for (ReddotTree.NodeState child : state.children()) {
            children.add(toView(child));
        }
        return new ReddotNodeView(state.key(), state.lit(), children);
    }

    /** 社交汇总：小队、联盟、国家（B13 前恒为 null）、红点、未读事件。 */
    @GetMapping("/summary")
    public Result<SocialSummaryResp> summary(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        return Result.ok(social.summary(requirePlayer(playerId), timeService.serverNow()));
    }

    /**
     * 我在某个层级拥有的权限位（验收 4）。
     *
     * <p><b>下发结论而不是整张矩阵</b>：客户端拿到列表就能决定按钮灰不灰，
     * 把 role_permission 表发出去等于把权限模型交给客户端，而客户端的任何判断都可以被绕过。
     */
    @GetMapping("/permissions")
    public Result<PermissionListResp> permissions(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestParam(name = "scope", defaultValue = "ALLIANCE") String scope) {
        return Result.ok(social.permissions(requirePlayer(playerId), scope, timeService.serverNow()));
    }

    /** GET /social/helpRequests（B10 验收 6）。列表与徽标同源，见 SocialAppService#helpBoard。 */
    @GetMapping("/helpRequests")
    public Result<SocialHelpListResp> helpRequests(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        return Result.ok(social.helpList(requirePlayer(playerId), timeService.serverNow()));
    }

    /** 帮助一条请求。会消耗与联盟帮助共用的每日额度（禁止项：不得简单叠加）。 */
    @PostMapping("/help")
    public Result<HelpResp> help(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                 @RequestBody HelpReq req) {
        requirePlayer(playerId);
        if (req.helpRequestId() == null || req.helpRequestId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "helpRequestId 不得为空");
        }
        return Result.ok(social.help(playerId, req.helpRequestId(), timeService.serverNow()));
    }

    /**
     * 一键帮助全部（验收 6：正确帮助所有可帮助项，红点清零）。
     *
     * <p>请求体只需要 requestId，所以复用一个「只带幂等键」的形状。
     * 一次请求处理全部而不是让客户端逐条发：逐条发在弱网下会有几次超时，
     * 玩家看到的是「点了全部却只帮到 5 个人」而红点还剩一半。
     */
    @PostMapping("/helpAll")
    public Result<HelpResp> helpAll(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                    @RequestBody(required = false) SocialEventAckReq ignored) {
        // 请求体被刻意忽略：一键帮助不需要任何参数，能帮谁由服务端算。
        // 让客户端传「要帮哪些人」等于把筛选口径交出去，而红点清零这条验收就没法保证了
        return Result.ok(social.helpAll(requirePlayer(playerId), timeService.serverNow()));
    }

    /** 标记事件已读（验收 12：离线补偿的事件不能每次上线都重收一遍）。 */
    @PostMapping("/ackEvents")
    public Result<SocialSummaryResp> ackEvents(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                              @RequestBody SocialEventAckReq req) {
        requirePlayer(playerId);
        List<String> eventIds = req.eventIds() == null ? List.of() : req.eventIds();
        return Result.ok(social.ackEvents(playerId, eventIds, timeService.serverNow()));
    }

    // ---------- 举报与拉黑（B22 §一 3） ----------

    /**
     * 举报一个玩家 / 一条消息。**只做留痕**（§五 裁决②）：处置归运营侧。
     * 同一目标 24 小时内超过 {@code global.SOCIAL_REPORT_DAILY_LIMIT} 次会被防刷闸拦下。
     */
    @PostMapping("/report")
    public Result<ReportResp> report(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestBody ReportReq req) {
        requirePlayer(playerId);
        return Result.ok(social.report(playerId, req, timeService.serverNow()));
    }

    /** 拉黑：私聊拒收 + 频道里不再显示他的消息。**不改变任何战斗 / PVP / 外交关系**（B22 §一 3）。 */
    @PostMapping("/block")
    public Result<BlockListView> block(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                       @RequestBody BlockReq req) {
        requirePlayer(playerId);
        return Result.ok(social.block(playerId, req));
    }

    /** 取消拉黑。 */
    @PostMapping("/unblock")
    public Result<BlockListView> unblock(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody BlockReq req) {
        requirePlayer(playerId);
        return Result.ok(social.unblock(playerId, req));
    }

    /** 我拉黑了谁。 */
    @GetMapping("/blocks")
    public Result<BlockListView> blocks(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(social.blocks(playerId));
    }

    /** 关注一个人（单向，B22 §一 4）。**对方不会收到通知**。 */
    @PostMapping("/follow")
    public Result<FriendListView> follow(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                         @RequestBody FollowReq req) {
        requirePlayer(playerId);
        return Result.ok(social.follow(playerId, req));
    }

    /** 取消关注。 */
    @PostMapping("/unfollow")
    public Result<FriendListView> unfollow(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody FollowReq req) {
        requirePlayer(playerId);
        return Result.ok(social.unfollow(playerId, req));
    }

    /** 我关注的人（最近关注的在前），带在线状态。 */
    @GetMapping("/follows")
    public Result<FriendListView> follows(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(social.follows(playerId));
    }

    private static String requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return playerId;
    }
}
