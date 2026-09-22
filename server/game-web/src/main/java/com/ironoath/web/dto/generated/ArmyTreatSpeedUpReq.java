// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /army/treatSpeedUp 请求体（B09/B05 的加速治疗）。
 *
 * **为什么不像训练那样带 unitId**（2026-09-22 改）：治疗是**全局一批**（`HospitalView` 只有 treating 与剩余秒，`TreatReq` 也只带 requestId），服务端实现里 `req.unitId()` **一次都没读** —— 而旧契约把它列成必填，于是客户端要么编一个没有意义的 unitId，要么根本发不出这个请求（这正是它一直没人调用的原因）。契约不该要求一个没人用的字段。
 */
public record ArmyTreatSpeedUpReq(
        String requestId,
        String itemId)   // 加速道具的 id。**必填**：服务端原话是"加速治疗必须指定 itemId"、"秒数只能来自道具配置" —— 治疗没有金币加速这条路。
{
}
