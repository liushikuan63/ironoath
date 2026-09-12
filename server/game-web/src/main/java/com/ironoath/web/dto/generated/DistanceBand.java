// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 距离档位（NEAR / MID / FAR）。
 *
 * **它不是信息隐藏手段，这一点必须写清楚。** TargetBrief 必然带 coord —— 不知道坐标就无法出兵，而客户端本来就知道自己的家坐标，所以精确距离它自己就能算出来。把 distanceBand 说成「防止客户端推演行军时间」是错的，写着错的理由会让下一个人在真正需要保密的地方（resourceHint）也放松警惕。
 *
 * 它的真实作用是**把分档口径收到服务端**：列表要按远近分组、要打「近/中/远」标签，如果只给坐标，每个客户端都得自己算距离再自己定分界，于是 iOS / 安卓 / 编辑器三端会给出三套分界，同一个目标在一端显示「近」、在另一端显示「中」。分界来自 SEARCH_NEAR_RATIO / SEARCH_MID_RATIO，下发的是结论而不是原料。
 *
 * B08 验收 12 的要求是「响应体中无距离数值字段，只有 distanceBand」，本字段就是那条要求的落地：距离只以档位形式出现，服务端不下发一个可以让客户端直接渲染成「3.2 小时可达」的数字。
 */
public enum DistanceBand {
    NEAR,
    MID,
    FAR
}
