package com.ironoath.core.gacha;

import java.util.Optional;

/**
 * 职责：抽卡进度（保底计数）的仓储端口。
 * 依赖：无。
 *
 * <p>为什么保底进度要单独一个仓储而不是塞进 {@code HeroRoster}：
 * 保底是<b>按卡池</b>记的，一个玩家有 N 个卡池就有 N 份进度；
 * 而 HeroRoster 是玩家唯一的武将存档。把 N 份进度塞进一份存档，
 * 会让「抽一次卡」和「升一次武将等级」争同一个乐观锁版本号 ——
 * 玩家一边抽卡一边喂经验书就会频繁撞版本，两个本来无关的操作互相失败。
 */
public interface GachaStateRepository {

    /** 取某玩家某卡池的进度；没有则返回 empty（首次抽该池）。 */
    Optional<GachaState> find(String playerId, String poolId);

    /**
     * 写入进度。
     *
     * <p>不返回版本号也不做乐观锁比对：抽卡流程整段在玩家锁内，
     * 而保底计数字段是<b>单调递增或按规则清零</b>的，
     * 不存在「两个并发请求各自读到旧值再互相覆盖」之外的语义 ——
     * 玩家锁已经排除了并发。真要跨实例，Redisson 锁同样排除。
     */
    void save(GachaState state);
}
