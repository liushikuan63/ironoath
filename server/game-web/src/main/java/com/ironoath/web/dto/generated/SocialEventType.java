// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 社交事件类型。离线补偿（B10 验收 12）与推送（验收 5）共用这一套类型 —— 推送是「实时送到」，补偿是「上线后补齐」，两者的**内容必须一致**，否则玩家会看到两种不同的通知文案描述同一件事。
 *
 * 其中 SQUAD_JOINED / ALLIANCE_JOINED / ALLIANCE_REJECTED / HELP_REQUESTED 不对应任何验收项，但缺了它们玩家就会遇到「申请交上去石沉大海」——B10 禁止项明写绝不静默失败，礼貌性通知也是这条纪律的一部分。（原先这里写的是「后四个」，按位置数的说法在追加取值之后就会误导，改成点名。）
 *
 * **NATION_LEFT / NATION_DISBANDED 是 2026-09-13 裁决补的**（收口清单 §三·补 A7）：国家侧此前一条通知都不发，而「我的国没了」是只能靠玩家自己点一下才发现的那类事实 —— GET /nation 对一个刚失去国籍的人回 13000，他从中读不出「我原来那个国呢」。注意这两个类型**不会同时发给同一个人**：最后一个成员联盟退出导致的自动亡国，走的是 NATION_LEFT 一条并把「该国随之解散」写进标题，而不是给同一件事发两条。
 *
 * **PRIVATE_MESSAGE 是 C23 补的（2026-09-14）**：私聊此前「发得出去、对方不主动拉就永远不知道」。它是**「有人找你」的信标，不携带正文** —— 正文的家是聊天频道（{@code /chat/list}）。把内容抄进事件会出现两个版本，而且事件带 3 小时 TTL、聊天带条数裁剪，两边各自消失的时间还不一样，症状就是「通知说有人找我，点开却看不到那条」。同一个发信人在未读里只留一条，连发多条不叠加。
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
    ALLIANCE_ROLE_SET,
    NATION_LEFT,
    NATION_DISBANDED,
    PRIVATE_MESSAGE
}
