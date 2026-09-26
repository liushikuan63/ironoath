// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/resume 响应体。恢复会把**暂停的那段时间还给这栋楼**（剩余时间与暂停前一致），所以这里的 remainingSeconds 是接上之后的真实剩余。
 */
public record CityResumeResp(
        String buildingId,
        String status,   // 恢复后的状态，恒为 UPGRADING。
        long finishAt,   // 恢复后的完成时刻（已把暂停时长顺延进去）。客户端的倒计时以它为准。
        long remainingSeconds)
{
}
