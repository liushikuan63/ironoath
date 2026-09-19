package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.AvatarFrameCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AvatarFrameListResp;
import com.ironoath.web.dto.generated.AvatarFrameView;
import com.ironoath.web.dto.generated.WearFrameReq;
import com.ironoath.web.dto.generated.WearFrameResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：头像框（B24 块③ 外观）—— 列出、佩戴、卸下。
 * 依赖：game-config（avatar_frame 表）、玩家存档（那两位）、城建服务的锁与结算入口。
 *
 * <p><b>外观不参与任何数值</b>（B15 §一 第 7 条 + 公理一）：本服务只改 {@code PlayerSave} 上
 * 那两位（佩戴 / 已拥有），不碰资源、城建、武将、军队、科技、付费 ——
 * 而这条纪律的机器判据是 {@code AvatarFrameEndpointTest} 里那条**逐字段相等**的判别性用例：
 * 任何人日后给外观顺手加一点加成，它会当场红。
 *
 * <p><b>佩戴只接受已拥有的框</b>：没买过就戴得上等于白送。而"拥有"来自购买那一刻记下的那一笔
 * （{@code PlayerSave#ownAvatarFrame}），与"当前戴着哪个"分成两位 ——
 * 合成一位会让卸下变成失去。
 */
@Service
public class AvatarFrameService {

    private static final Logger LOG = LoggerFactory.getLogger(AvatarFrameService.class);

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final CityAppService cityAppService;

    public AvatarFrameService(ConfigRegistry configs, PlayerRepository players,
                              CityAppService cityAppService) {
        this.configs = configs;
        this.players = players;
        this.cityAppService = cityAppService;
    }

    /** 全部头像框（含没拥有的）：让玩家看见有什么可拿，是收集类外观存在的意义。 */
    public AvatarFrameListResp list(String playerId) {
        return cityAppService.withSettledCity(playerId, snap ->
                new AvatarFrameListResp(viewOf(snap.player()), snap.now()));
    }

    /**
     * 佩戴（{@code frameId=null} = 卸下）。
     *
     * <p><b>写存档走 {@link CityAppService#withSettledCity}</b>：那是本仓库唯一"拿锁 + 结算 + 落库"的
     * 入口（与军队/科技同一条纪律）。自己 load + save 会与并发的城建改动互相覆盖 ——
     * 而仓储返回的是深拷贝，后写的那份会把前一份的改动抹掉。
     */
    public WearFrameResp wear(String playerId, WearFrameReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        return cityAppService.withSettledCity(playerId, snap -> {
            // **改 snap.player()，不自己 findById 再 save**：结算路径可能已经推进过存档版本，
            // 手里那份重读出来的旧版本号一写就是"乐观锁冲突"（新手期最容易撞的一条）
            PlayerSave save = snap.player();
            String frameId = req.frameId();
            if (frameId == null) {
                save.setAvatarFrame(null);
            } else {
                AvatarFrameCfg cfg = configs.all(AvatarFrameCfg.class).stream()
                        .filter(row -> row.id().equals(frameId)).findFirst()
                        .orElseThrow(() -> new BizException(ErrorCode.CONFIG_NOT_FOUND,
                                "头像框配置不存在: " + frameId));
                if (!save.ownedAvatarFrames().contains(cfg.id())) {
                    // 没拥有就戴不上：这条与商店那条"买过才算拥有"是同一条链的两端
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            cfg.name() + " 还没有获得，先去拿到它");
                }
                save.setAvatarFrame(cfg.id());
            }
            LOG.info("头像框佩戴变更 playerId={} 现在是={}", playerId, save.avatarFrame());
            return new WearFrameResp(viewOf(save), snap.now());
        });
    }

    /**
     * 记下"这个人拥有这个框"（购买成功那一刻由商店调用）。幂等。
     *
     * <p>同样走 {@code withSettledCity} 改 {@code snap.player()}：与佩戴一条纪律 ——
     * 自己读取 + 自己 save 会在结算刚推进过版本时撞乐观锁。
     */
    public void grant(String playerId, String frameId) {
        cityAppService.withSettledCity(playerId, snap -> {
            snap.player().ownAvatarFrame(frameId);
            return null;
        });
    }

    private List<AvatarFrameView> viewOf(PlayerSave save) {
        List<AvatarFrameView> frames = new ArrayList<>();
        for (AvatarFrameCfg cfg : configs.all(AvatarFrameCfg.class)) {
            boolean owned = save.ownedAvatarFrames().contains(cfg.id());
            frames.add(new AvatarFrameView(cfg.id(), cfg.name(), cfg.rarity().name(),
                    cfg.placeholderColor(), owned, owned && cfg.id().equals(save.avatarFrame())));
        }
        return frames;
    }

}
