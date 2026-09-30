// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * **一条正在生效的国策**，带到期时刻。
 *
 * **为什么单独一个类型而不在 `NationPolicyView` 上加一个 `activeUntil`**：候选清单里那 8 行根本还没有生效时刻（还没通过、还没开始计时），加上去就是一份「大多数时候是 0 或者 null」的字段，客户端每处都要判空 —— 而判空的那个分支永远没人测。拆开之后「生效中」这件事的类型上就带着到期时刻，拿不到就是拿不到。
 *
 * **到期时刻是必填**：这一列存在的理由就是「还剩多久」，而 B21 块③ 说的「生效期间可查当前国策」落到屏上必然带倒计时。
 */
public record NationPolicyActiveView(
        NationPolicyView policy,
        long activeUntil)   // 这一条到期的服务端时刻（毫秒）。**由服务端下发，客户端不许自己加时长**（铁律 5）—— 倒计时是两个同源时刻相减。
{
}
