package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatListResp;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.ChatSendResp;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：聊天域 HTTP 入口（B10 §5，验收 9）。
 * 依赖：Spring Web、{@link SocialAppService}。
 *
 * <p><b>两个端点都用 POST</b>，包括拉取历史。GET 的查询串会进访问日志与网关监控，
 * 而私聊频道的键里含对方 playerId —— 把「谁在和谁私聊」写进日志是隐私问题，
 * 不是性能问题。放到请求体里就没有这个泄漏面。
 *
 * <p><b>被限流时返回业务错误码 SOCIAL_CHAT_RATE_LIMITED，绝不静默丢弃</b>（验收 9）：
 * 假装发成功会让玩家以为对方收到了，而对方什么都没看到 ——
 * 那比明确的「发得太快」严重得多，因为前者会让玩家反复重发同一句话，
 * 反而更快撞上限流。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final SocialAppService social;
    private final TimeService timeService;

    public ChatController(SocialAppService social, TimeService timeService) {
        this.social = social;
        this.timeService = timeService;
    }

    /** 发消息。同内容 10 秒内超过 3 次会被拦（global.CHAT_RATE_LIMIT_*）。 */
    @PostMapping("/send")
    public Result<ChatSendResp> send(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestBody ChatSendReq req) {
        requirePlayer(playerId);
        if (req.channel() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        return Result.ok(social.chatSend(playerId, req, timeService.serverNow()));
    }

    /** 拉取某频道的最近消息，按时间升序。limit 会被夹到 global.CHAT_LOCAL_HISTORY_MAX。 */
    @PostMapping("/list")
    public Result<ChatListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestBody ChatListReq req) {
        requirePlayer(playerId);
        if (req.channel() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 不得为空");
        }
        if (req.limit() < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "limit 必须 >= 1，实际=" + req.limit());
        }
        return Result.ok(social.chatList(playerId, req, timeService.serverNow()));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
