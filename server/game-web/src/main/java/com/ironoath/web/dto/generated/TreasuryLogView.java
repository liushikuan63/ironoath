// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条国库流水（B13 §3 验收 5：谁 / 何时 / 支给谁 / 多少，四样缺一不可）。
 *
 * <b>这张表存在的理由是防贪污</b>：国库是公共资产，而公共资产的纠纷会溢出到现实（盟主卷款、公会撕逼上社交媒体），所以它不是内部账本而是<b>要公示给成员看的东西</b> —— 只有服务端留档而玩家看不到的日志，等于把审计权交给了被审计的那个人。
 */
public record TreasuryLogView(
        long at,   // 这笔流水发生的服务端时刻。客户端不得用本地时间去排这个序（铁律 5）。
        String operatorId,   // 谁做的。<b>系统动作写 {@code system}</b>（周税入账就是它），人做的写那个人的 id —— 没有「谁」的日志无法追责，所以领域层在签名上就不允许为空。
        String counterparty,   // 对手方：支给谁（出账）或来自哪里（入账）。同一个字段承担两个方向是刻意的 —— 领域层就是这么记的（{@code payee}），而把方向拆成两列会让「把金额加总」得出一个说不清的结论。
        long amount,   // 这笔的规模（国库资金单位，与 GOLD 同单位；划拨给玩家时 1:1），**必须是 long**（B01 的定点约定：全项目金额不用浮点）。方向由 counterparty 表达，所以这里恒为正 —— 一旦允许负数，同一张表里就会出现两种符号口径。
        String reason,   // 用途。领域层同样不允许为空：没有「为什么」的日志等于没有日志。
        long balanceAfter)   // 这笔之后的余额（国库资金单位）。带上它是为了让流水能**自证连贯**：任意相邻两行的余额差就该等于下一行的金额，对不上的那一段就是有人在改账本。
{
}
