// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 列表里的一关。
 */
public record StageEntry(
        String stageId,
        String chapterId,
        long stageNo,
        String name,
        long staminaCost,
        long roundLimit,
        UnitRestriction unitRestriction,
        BossMechanic bossMechanic,
        boolean unlocked,
        String lockedReason,   // 未解锁的原因文案；已解锁为 null。**必须给原因**：一个灰掉的关卡不说明为什么，玩家会以为是 bug（B08 的同一条纪律：绝不静默失败）
        boolean attempted,   // 是否挑战过。**与 progress 分开下发**：生成器不支持可空的 $ref，而「没打过」与「打过但 0 星」在 UI 上是两种状态（前者显示未挑战，后者显示 0 星），用一个全 0 的对象表达不了这个区别
        StageProgressView progress)
{
}
