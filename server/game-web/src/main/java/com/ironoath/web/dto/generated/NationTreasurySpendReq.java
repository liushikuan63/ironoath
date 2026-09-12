// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/treasury/spend 请求体：从国库支出一笔（B13 §3）。
 *
 * **权限走 role_permission 表的 WITHDRAW_TREASURY**（该表 allowLeader=true、其余两档 false，why 写明「国库支取涉及全国资源，只给国主」）—— B13 §2 给首相写了「国库支出（限额）」，但那个限额没有数值，所以本轮以权限表为准、首相暂不可支取（要放开就先给限额定数并改表）。
 *
 * **每笔都进国库日志**（谁/何时/支给谁/多少，验收 5）：扣账与写日志在领域层同一步完成，给玩家的那一笔在扣账之后走发放器 —— 顺序是刻意的，失败方向选「记了没发出去」（那笔有补偿队列与客服入口，反过来没有）。
 */
public record NationTreasurySpendReq(
        String requestId,   // 幂等键。重放一次俸禄不该重复出账 —— 国库是公共资产，重复出账是真金白银的损失。
        TreasuryPayeeType payeeType,   // 落点类型。选 PLAYER 时必须给 payeeId；选 SINK 时必须给 sink。两者都给或都不给都会被拒（含糊的支给对象是这张日志最怕的东西）。
        String payeeId,   // 收款玩家 id。payeeType=PLAYER 时必填，且必须是一个存在的玩家 —— 记在一个不存在的 id 上等于这笔钱没有收款人，而日志却写着有。
        TreasurySink sink,   // 消耗性用途。payeeType=SINK 时必填。
        long amount,   // 支出额（国库资金单位，正整数）。余额不足直接拒绝（NATION_TREASURY_NOT_ENOUGH），不做部分出账 —— 半笔俸禄比不发更难解释。
        String reason)   // 用途说明。领域层不允许为空：没有「为什么」的日志等于没有日志。
{
}
