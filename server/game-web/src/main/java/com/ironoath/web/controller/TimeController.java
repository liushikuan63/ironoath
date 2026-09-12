package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.dto.generated.TimeSync;
import com.ironoath.web.dto.generated.TimeSyncReq;
import com.ironoath.web.dto.generated.TimeSyncResp;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：时间校准入口 —— 客户端据此把本地时钟对齐到服务端（铁律 5）。
 * 依赖：Spring Web、game-common 的 TimeService。
 *
 * <p>客户端拿到 offset 后不能直接用：单次采样含一个单程网络延迟，
 * 必须由客户端 {@code core/TimeSync.ts} 做加权移动平均并剔除抖动极值。
 * 服务端只负责如实返回 {@code serverNow - clientTime}。
 *
 * <p>这个接口不校验身份、不读存档、不产生副作用，因此可以在登录前调用，
 * 让客户端在进入主城的第一个请求之前就把时钟对齐。
 */
@RestController
@RequestMapping("/time")
public class TimeController {

    private final TimeService timeService;

    public TimeController(TimeService timeService) {
        this.timeService = timeService;
    }

    @PostMapping("/sync")
    public Result<TimeSyncResp> sync(@RequestBody TimeSyncReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        TimeService.TimeSync sync = timeService.calibrate(req.clientTime());
        return Result.ok(new TimeSyncResp(new TimeSync(sync.offset(), sync.syncAt())));
    }
}
