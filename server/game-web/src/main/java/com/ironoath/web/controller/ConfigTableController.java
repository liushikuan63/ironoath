package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.ConfigTableResp;
import com.ironoath.web.release.HotUpdateTableService;

/**
 * 职责：**白名单配置表**的取内容出口（收口清单 #2287 ⑤ / V26-1）。
 *
 * <p><b>它不是 {@code /ops/config/manifest} 的替代，而是补上它缺的那一半</b>：manifest 是
 * **运维**端点（要运维令牌）且只给「哪些表名过期了」；客户端要真的换掉自己那份展示数据，
 * 得有一条面向自己的、按表名取内容的通道。
 *
 * <p><b>边界只有一处</b>：global.HOT_UPDATE_TABLES（表级白名单）。白名单外的表名与不存在的表名
 * 给**同一个**错误码 —— 区分开等于把白名单内容泄露给客户端。
 *
 * <p><b>只读</b>：这个端点不写任何状态 ⇒ 客户端可以在安全刷新点随便重取。
 */
@RestController
@RequestMapping("/config")
public class ConfigTableController {

    private final HotUpdateTableService tables;

    public ConfigTableController(HotUpdateTableService tables) {
        this.tables = tables;
    }

    /**
     * 按表名取白名单表的内容（整表 JSON 字符串 + 版本号）。
     *
     * @param name 表名；必须命中白名单，否则回「没有这张配置表」
     *
     * <p>identity-exempt: 白名单表的内容是**所有玩家共享的同一份展示数据**
     * （公告表对每个玩家一模一样）⇒ 按玩家过滤没有语义。**这正是白名单的意义**：
     * 能被公开取到的只有「与谁无关的展示内容」，玩法与经济数值的表根本进不了这个口
     * （`global` / `stage` / `item` 都在白名单外）。
     */
    @GetMapping("/table")
    public Result<ConfigTableResp> table(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestParam("name") String name) {
        return Result.ok(tables.table(name));
    }
}
