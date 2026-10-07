// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /rally/list 响应：我所在的小队、联盟与国家里**进行中**的集结（国家那一支由 V22-a 接上）。面板列表用 —— 只返回 PREPARING 的，已出发或已取消的集结留在面板上没有意义，而「点进去发现早就出发了」比「看不到」更让人困惑。
 */
public record RallyListResp(
        List<RallyView> rallies,   // 进行中的集结，按创建时刻升序（先发起的排前面，因为它的准备窗口先结束）。
        long serverNow)   // 服务端时间戳。客户端据此算准备窗口的剩余秒数（铁律 5：倒计时不得用本地时钟）。
{
}
