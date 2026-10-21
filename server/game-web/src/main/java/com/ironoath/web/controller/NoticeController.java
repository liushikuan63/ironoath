package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.NoticeListResp;
import com.ironoath.web.notice.NoticeService;

/**
 * 职责：运营公告的只读出口（收口清单 #2287 ⑤ / V26-1）。
 *
 * <p><b>为什么独立成一个控制器</b>：公告是「看」，不是「买」也不是「打」—— 与 {@code PayController}、
 * {@code GiftController} 分开，卡口与客户端路由都能一眼看出这是读侧。
 *
 * <p><b>只读**：这个端点不改任何状态（不写玩家档、不扣东西）⇒ 客户端可以随时在安全刷新点重取。
 */
@RestController
@RequestMapping("/notice")
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    /**
     * 当前生效的公告（服务端已按窗口与优先级排好；客户端不重排、不自己判窗口）。
     *
     * <p>identity-exempt: 公告是**全服同一份**的运营内容（所有玩家看到的窗口与排序完全一样），
     * 按玩家过滤没有任何语义 ⇒ 这里的 playerId 只用来标识「这是一个登录态的客户端」，
     * 不参与筛选。**真要按人发不同公告，那是另一套机制**（定向投放），不在这条端点上假装支持。
     */
    @GetMapping("/list")
    public Result<NoticeListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        return Result.ok(notices.list());
    }
}
