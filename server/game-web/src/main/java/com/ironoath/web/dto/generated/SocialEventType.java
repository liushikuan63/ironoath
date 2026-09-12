// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 社交事件类型。离线补偿（B10 验收 12）与推送（验收 5）共用这一套类型 —— 推送是「实时送到」，补偿是「上线后补齐」，两者的**内容必须一致**，否则玩家会看到两种不同的通知文案描述同一件事。
 *
 * 后四个（SQUAD_JOINED / ALLIANCE_JOINED / ALLIANCE_REJECTED / HELP_REQUESTED）不对应任何验收项，但缺了它们玩家就会遇到「申请交上去石沉大海」——B10 禁止项明写绝不静默失败，礼貌性通知也是这条纪律的一部分。
 */
public enum SocialEventType {
    MEMBER_ATTACKED,
    SQUAD_DISBANDED,
    ALLIANCE_APPLIED,
    ALLIANCE_KICKED,
    SQUAD_KICKED,
    RALLY_INVITED,
    RALLY_DEPARTED,
    ALLIANCE_TRANSFERRED,
    ALLIANCE_DISBANDED,
    HELP_RECEIVED,
    SQUAD_JOINED,
    ALLIANCE_JOINED,
    ALLIANCE_REJECTED,
    HELP_REQUESTED,
    ALLIANCE_ROLE_SET
}
