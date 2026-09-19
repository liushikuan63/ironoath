package com.ironoath.web.service;

import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.OfflineReportView;
import com.ironoath.web.dto.generated.PlayerInitResp;
import com.ironoath.web.dto.generated.PlayerProfile;
import com.ironoath.web.dto.generated.PowerSnapshot;
import com.ironoath.web.dto.generated.ResourceState;
import com.ironoath.web.dto.generated.ResourceType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：领域模型 → 协议 DTO 的转换（生成物 DTO 与 game-core 领域对象之间的唯一桥）。
 * 依赖：game-core、生成的 DTO。
 *
 * <p>{@code ResourceType.valueOf(typeId)} 之所以安全：生成器在 CI 中强制校验
 * 协议枚举与 {@code contract/config/resource.json} 的 id 集合完全一致
 * （Schema 里的 {@code x-enum-source}）。配置表加了一种资源但忘了改 Schema，构建就会失败，
 * 而不是等到线上抛 IllegalArgumentException。
 */
public final class PlayerDtoMapper {

    private PlayerDtoMapper() {
    }

    /**
     * 组装 {@code /player/init} 的响应，资源取存档原样快照。
     *
     * <p>只适用于<b>存档本身就是刚算好的</b>那条路径（新号建档）。登录与并发建号兜底
     * 必须改用 {@link #toInitResp(PlayerSave, long, Map)} 传结算过的状态，
     * 否则响应里的 serverNow 与各资源的 lastSettle 不同源。
     */
    public static PlayerInitResp toInitResp(PlayerSave save, long serverNow, String authToken) {
        return toInitResp(save, serverNow, save.resources(), authToken);
    }

    /**
     * 组装 {@code /player/init} 的响应。
     *
     * @param serverNow 服务端时间戳。由调用方传入而不是在此读时钟，
     *                  保证同一次响应里的 serverNow 与各资源的 lastSettle 来自同一时刻
     * @param resources 要与 {@code serverNow} 同源的资源状态。<b>登录返回的是存量快照，
     *                  客户端按 B00 铁律从不自己结算</b>，所以这里给什么玩家就看到什么 ——
     *                  给未结算的旧存量配一个新时刻，症状是「重登一次，资源数字倒退几分钟」
     */
    public static PlayerInitResp toInitResp(PlayerSave save, long serverNow,
                                            Map<String, PlayerResourceState> resources,
                                            String authToken) {
        return toInitResp(save, serverNow, null, resources, authToken, 0L, 1L);
    }

    /**
     * 带「自上次登录以来」边界与阈值的完整版本（B25-S3）。
     *
     * @param previousLoginAt 上次登录时刻；**新号传 null**（没有「上一次」可言）
     * @param minIdleMinutes  距上次登录不足这么多分钟就不打扰，来源 global 表
     * @param minItems        至少这么多条明细才值得弹，来源 global 表
     */
    public static PlayerInitResp toInitResp(PlayerSave save, long serverNow, Long previousLoginAt,
                                            Map<String, PlayerResourceState> resources,
                                            String authToken, long minIdleMinutes, long minItems) {
        Map<ResourceType, ResourceState> views = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerResourceState> e : resources.entrySet()) {
            PlayerResourceState s = e.getValue();
            views.put(ResourceType.valueOf(e.getKey()), new ResourceState(
                    s.current(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        }

        PlayerProfile profile = new PlayerProfile(
                save.playerId(), save.nickName(), save.avatarId(),
                save.createdAt(), save.lastLoginAt());

        PlayerPower p = save.power();
        PowerSnapshot power = new PowerSnapshot(p.displayPower(), p.matchPower(), p.peakPower());

        OfflineReportView offlineReport = new OfflineReportView(
                previousLoginAt, minIdleMinutes, (int) minItems);

        return new PlayerInitResp(
                save.playerId(), authToken == null ? "" : authToken, serverNow, profile, save.cityLevel(),
                views, power, save.protectUntil(), offlineReport);
    }
}
