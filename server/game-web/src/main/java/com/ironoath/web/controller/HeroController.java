package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.HeroEquipReq;
import com.ironoath.web.dto.generated.HeroGrowResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.HeroItemReq;
import com.ironoath.web.dto.generated.HeroLevelUpReq;
import com.ironoath.web.dto.generated.HeroListResp;
import com.ironoath.web.dto.generated.SetLineupReq;
import com.ironoath.web.dto.generated.SetLineupResp;
import com.ironoath.web.service.HeroAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：武将域 HTTP 入口 —— 五条养成线 + 编队（B06 §2/§4）。
 * 依赖：Spring Web、HeroAppService。
 *
 * <p>Controller 保持极薄：取 playerId → 调 service → 包 Result。
 * 校验、加锁、幂等、扣材料全在 service 里，这样它们能在没有 HTTP 的情况下被单测覆盖。
 *
 * <p><b>六个养成端点各占一条路径，而不是合成一个 {@code /hero/grow?type=…}</b>：
 * 它们的请求体形状不同（升级带 expItems 列表、装备带 slot、技能带 skillSlot），
 * 合成一个就必须让所有字段都可选，于是「升级请求里带了 slot」这种客户端 bug
 * 在服务端看起来完全合法。分开之后每个端点的必填字段都是强约束。
 *
 * <p>TODO(需确认): playerId 仍从 {@code X-Player-Id} 头取。武将养成是深度付费线，
 * B15 接入微信登录后必须改成从已验证的 token 解析 —— 信任请求头等于把改别人武将的入口敞开。
 */
@RestController
@RequestMapping("/hero")
public class HeroController {

    private final HeroAppService heroAppService;

    public HeroController(HeroAppService heroAppService) {
        this.heroAppService = heroAppService;
    }

    /** 武将总览：全部武将、三套编队、各稀有度碎片余额、带兵上限。 */
    @GetMapping("/list")
    public Result<HeroListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.list(playerId));
    }

    /** 等级线：投喂经验书（B06 §2.1）。一次可以喂多种、多本，服务端连续升级。 */
    @PostMapping("/levelUp")
    public Result<HeroGrowResp> levelUp(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody HeroLevelUpReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.levelUp(playerId, req));
    }

    /** 星级线：消耗该稀有度的碎片升一星（B06 §2.2）。 */
    @PostMapping("/starUp")
    public Result<HeroGrowResp> starUp(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                       @RequestBody HeroIdReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.starUp(playerId, req));
    }

    /** 碎片合成：凑够 hero_rarity.composeFragment 就获得该武将（B06 §1）。 */
    @PostMapping("/compose")
    public Result<HeroGrowResp> compose(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody HeroIdReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.compose(playerId, req));
    }

    /** 觉醒线：消耗觉醒石推进一阶（B06 §2.3）。最后一阶必须用高阶石。 */
    @PostMapping("/awaken")
    public Result<HeroGrowResp> awaken(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                       @RequestBody HeroItemReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.awaken(playerId, req));
    }

    /** 技能线：消耗技能书升主/副技能（B06 §2.4）。书的 effectTarget 必须与 skillSlot 一致。 */
    @PostMapping("/skillUp")
    public Result<HeroGrowResp> skillUp(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody HeroItemReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.skillUp(playerId, req));
    }

    /**
     * 装备线：穿或卸（B06 §2.5）。equipUid 为 null 表示卸下。
     *
     * <p><b>"回到背包"这句已从语义里去掉</b>（B20 §五⑤）：装备是按件的实例，
     * 穿上只是翻 worn 标志，实例一直在背包的账本里；卸下也只是翻回来并占回那一格。
     */
    @PostMapping("/equip")
    public Result<HeroGrowResp> equip(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                      @RequestBody HeroEquipReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.equip(playerId, req));
    }

    /**
     * 设置一套编队（B06 §4）。
     *
     * <p><b>整体替换语义</b>：请求里的三个位置就是这套编队的全部内容，传 null 即空位。
     * 这正是 B06 验收 5「下阵无残留」的实现方式 —— 增量修改（「把 X 加进队伍」）
     * 会让「移除」变成一条独立路径，而独立路径漏掉一次清理就是永久残留的加成。
     */
    @PostMapping("/lineup")
    public Result<SetLineupResp> setLineup(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody SetLineupReq req) {
        requirePlayer(playerId);
        return Result.ok(heroAppService.setLineup(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
