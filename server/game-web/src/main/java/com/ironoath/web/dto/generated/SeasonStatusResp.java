// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /season/status 响应：赛季时间轴的当前状态。
 */
public record SeasonStatusResp(
        String seasonId,   // 赛季 id（从 season 表行 id 的前缀反推，如 season_01）。未配置赛季锚点时为空串。归档集合名 season_<id> 由它拼出。
        SeasonPhase phase,   // 当前规则阶段；**null = 赛季未启用**（没配 SEASON_START_AT），不是「第 0 天」。这一位不在 required 里 —— 生成器按「非必填 = 可空」产出 `SeasonPhase | null`，可空性由所在位置声明而不是 $defs 里那个 type 数组（那个数组对枚举不起作用）。此前它被列进 required，于是生成出来的类型说非空而 SeasonAppService#status 会下发 null：客户端 `switch (resp.phase)` 照样编译通过，把 null 当成兜底分支。
        Long seasonStartAt,   // 赛季开始的服务端时刻（毫秒）。null = 未配置。客户端要它才能把自己的服务器时钟换算成「赛季第几天」，而不该自己拿本地时间算 —— 那是 B00 铁律 5 禁止的事。
        Integer dayIndex,   // 赛季第几天（0-based），整天数向下取整，所以阶段切换精确落在整日边界。<b>null = 赛季未启用</b>，不是「第 0 天」。
        long totalDays,   // 本赛季总天数（season 表各行 durationDays 之和）。超过它就进入休赛期。
        Long phaseEndAt,   // 当前阶段结束的服务端时刻（毫秒），UI 画倒计时用，绝不由客户端自己减。休赛期指向赛季终点。null = 赛季未启用。
        boolean allowsPvp,   // 当前是否允许玩家间攻击。**这是服务端的权威答案**，客户端只能据此把按钮置灰，真正的拦截在 AttackGuardService 里 —— 只在 UI 上禁 while 服务端放行，等于让改包的客户端照打。
        boolean allowsCapitalWar,   // 当前是否开放中央王城（问鼎期）。
        boolean readOnly,   // 是否只读期（休赛期：只展示荣耀，不再产生新的赛季行为）。
        SeasonGloryView glory,   // 我的荣耀三件套。<b>需要身份</b>：没带 {@code X-Player-Id} 时为 null（与本接口的 {@code myRank} 同一条规则 —— 全服信息不该被身份门槛挡住，属于个人的东西才要身份）。可空由「不在 required 里」声明；这里曾写过一个 {@code nullable: true}，但生成器不读它，留着只会让人以为那是开关。 服务端优先读主存档里的那份派生缓存；缓存缺失或落后于账本时（重启、或结算写缓存失败过），以赛季账本为准重算并就地修一次。
        Integer myRank,   // 当前玩家在本赛季实时榜上的名次，1-based。三种取值的含义必须分清： - **null**：赛季未启用，或这次请求没带 `X-Player-Id`（本接口不要求身份）； - **0**：赛季在跑，但这个玩家还没上报过战力 —— 也就是「未上榜」，不是「第 0 名」； - **>=1**：实时榜上的名次。 注意这是**实时榜**而不是快照榜：面板要让玩家看到自己现在的位置，而结算只认快照（B14 禁止项），两者刻意分开。
        long serverNow)   // 服务端时间戳，用于客户端校准时钟。
{
}
