package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.MailClaimAllReq;
import com.ironoath.web.dto.generated.MailClaimAllResp;
import com.ironoath.web.dto.generated.MailListResp;
import com.ironoath.web.dto.generated.MailReadReq;
import com.ironoath.web.dto.generated.MailReadResp;
import com.ironoath.web.mail.MailAppService;

/**
 * 职责：邮件域 HTTP 入口（B12 §2）—— 列表、一键领取、标已读。
 * 依赖：Spring Web、{@link MailAppService}。
 *
 * <p><b>只有三条，且没有「领这一封」</b>：单封领取看着只是多一个端点，实际是多一套判据 ——
 * 「已领过」要判两次（单封与一键各自判），两处的先后与竞态处理迟早分叉。
 * B12 的验收原文也只有一键领取（50 封只发 1 次批量请求），所以这里刻意不提供第二条路。
 *
 * <p><b>也没有「删邮件」</b>：玩家能删的话，「我没看到那封补偿」就再也没有可核对的证据。
 * 过期是唯一的消失方式，而它的判据只有一个时刻（{@code MAIL_RETENTION_DAYS}）。
 */
@RestController
@RequestMapping("/mail")
public class MailController {

    private final MailAppService mails;

    public MailController(MailAppService mails) {
        this.mails = mails;
    }

    /** 收件箱。顺带按保留期清理过期邮件（惰性，不跑定时器）。 */
    @GetMapping("/list")
    public Result<MailListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(mails.list(playerId));
    }

    /** 一键领取全部可领附件。失败的邮件保留，并在响应的 failed 里带着原因回给玩家。 */
    @PostMapping("/claimAll")
    public Result<MailClaimAllResp> claimAll(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody MailClaimAllReq req) {
        requirePlayer(playerId);
        return Result.ok(mails.claimAll(playerId, req));
    }

    /** 标一封为已读，顺带回新的未读封数。 */
    @PostMapping("/read")
    public Result<MailReadResp> read(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestBody MailReadReq req) {
        requirePlayer(playerId);
        return Result.ok(mails.markRead(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
