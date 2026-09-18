// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /gift/popup 响应：这一屏到底弹不弹、弹哪一个（B19 S3-ii）。
 *
 * **为什么弹窗由读时决定而不是事件推弹窗**：一次结算可能同时收完三个建筑，事件侧直接弹窗就会连弹三包，而「全局冷却 10 分钟」这条恰好是为了挡它（PopupThrottle 类注释原话）。所以事件源只记「什么时候触发过」，弹不弹、弹哪个由这里读时算。
 *
 * **只回『弹哪个包 + 价格档 + 这次报价什么时候过期』，不回礼包内容**：内容住在 product_reward，弹窗里再抄一份就会写「2 张建造令、实际发到 1 张」，而这条分叉只在玩家付费之后才暴露（B19 §四 禁止项）。
 */
public record GiftPopupResp(
        boolean popup,   // 此刻是否要弹。false 时下面三样都是 null —— 客户端不许自己退回去弹「上一个还在 TTL 里的」，那等于绕过频控。
        String giftId,   // 礼包行 id（gift 表）。弹哪个包要能被运营与埋点对上，而不是只给一句文案。
        String productId,   // 要买就下单这一档（pay_product id）。价格与内容都按它现查，本响应不复制。
        Long offerExpireAt,   // 本次报价过期时刻（毫秒）= 触发时刻 + gift.offerTtlMinutes。过期之后必须重新触发才有弹窗，不做常驻挂件（§五⑤：玩家不看界面时倒计时照样在走，那是拿焦虑换点击）。
        long cooldownSec,   // 被压住时「多久之后再问一次」的秒数（本次判定各条压制理由里最短的那个），允许弹出时为 0。客户端拿它做请求节流；它**不是**倒计时，倒计时读 offerExpireAt。
        long serverNow)   // 服务端时刻：任何倒计时都由它减出来，客户端时钟不参与（铁律 5）。
{
}
