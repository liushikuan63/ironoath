// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 关卡结算里的一行损失：StageUnit 的两个字段 + 一份**服务端下发的展示名**。为什么不复用 StageUnit：那个类型同时充当「玩家派出去的兵」（ChallengeStageReq.units）的载荷，给它加 name 等于要求客户端上传一个它根本没有的字段（客户端不持有 unit 表数据，B00 铁律）。而损失行是直接画给玩家看的 —— 印 unit_infantry_t3 就是把内部编号端上屏（#255 建筑名 / #268 资源名 / #278 技能名 / #281 碎片名 / #288 赛季行 id 同族第八处）。
 */
public record StageLoss(
        String unitId,   // unit 表的行 id，含阶级（如 unit_infantry_t3）。与 StageUnit/MarchUnit 同名同义
        long count,
        String name)   // 兵种展示名，取自 unit 表的 name 列。客户端不得自行翻译表文案，也不得拿另一次响应里的名字在本地 join（那是第二真源，且损失的兵种可能早已不在编成里）
{
}
