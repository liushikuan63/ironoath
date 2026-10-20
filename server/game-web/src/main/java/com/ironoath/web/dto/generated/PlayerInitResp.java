// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;
import java.util.Map;

/**
 * POST /player/init 响应体（data 字段内容）
 */
public record PlayerInitResp(
        String playerId,
        String authToken,   // 会话票据（B15 §三）。客户端之后每个请求都要带 X-Auth-Token；没有微信登录体系的环境返回空串
        long serverNow,   // 服务端时间戳，客户端据此算偏移（铁律 5）
        PlayerProfile profile,
        int cityLevel,   // 主城等级，新号 = 1
        Map<ResourceType, ResourceState> resources,   // 五种资源快照
        PowerSnapshot power,
        Long protectUntil,   // 新手保护到期时间（服务端毫秒时间戳）；null 表示无保护
        OfflineReportView offlineReport,   // 「自上次登录以来」的汇总**判定依据**（B25-S3）。下发的是时间边界与两个阈值，不是一个算好的汇总 —— 汇总由客户端从它已经拉到的面板数据里聚合（裁决①(a)：只聚合既有账本，不新造第二本账）
        List<ResourceNameView> resourceNames,   // 全部资源的中文名（resource 表的 name 列）。**为什么随 /player/init 下发**：客户端没有配置表加载器（resource 表不进包）⇒ 拿不到表；抄一份就是**改表只改一半**（服务端认了新名，客户端还显示旧名），而两边各自自洽 ⇒ 单测 / 类型检查 / 构建全绿，没有任何一条断言会红。**为什么一次给全部而不是逐个金额对象带 name**：面板上显示资源名的地方有十几处，其中好几处手里**只有类型没有金额对象**（抽卡消耗、社交政策消耗、地图实体、战报指标）—— 逐个对象带 name 要改五份契约，一次下发则让客户端自己建映射，十八处调用点一行不用改。**刻意不在 required 里（字段缺省 = 滚动升级期间旧服务端没这个字段）**：客户端把「没有这个字段」读作「这次没有名字」⇒ 两边都能跑（旧服务端 + 新客户端只是名字退化成枚举原文，不会崩也不会读错数）。⚠️ 注意它**不是**「array + null」那种可空写法 —— 生成器只支持「单一类型 + null」且那个 null 分支只走原始类型（`resolvePrimitive`），数组走不进去（实测报错「使用了不支持的类型: array」）；而「不在 required 里」在 TS 侧产出的是 `resourceNames?: ResourceNameView[]`，正是这里要的语义。
        int autoTrainDefaultBatches,   // 客户端「自动续训」默认填几批（global 表的 AUTO_TRAIN_DEFAULT_BATCHES）。**与服务端上限 AUTO_TRAIN_MAX_BATCHES 是两个数**（默认 3 / 上限 5）：上限是「最多允许几批」，默认是「替他填几批」—— 取小是有意的（一次自动开 3 批 ≈ 睡一觉回来还有一批在训，而花掉的是他自己看得到的资源）。**为什么必须下发**：客户端没有配置表加载器 ⇒ 自己写一份就是第二个真相（表里改了就漂）。**字段缺省 = 滚动升级期间的旧服务端**（它没这个字段）⇒ 客户端要能扛：退回 1 批（最小可用值，服务端不会拒）。
        ToastTuning toast)   // 飘字队列的参数包。放在 init 而不是每个面板各自问一次：队列住在编排层（整个客户端只有一个出口），而 init 是它唯一必然先于任何提示发生的时刻。
{
}
