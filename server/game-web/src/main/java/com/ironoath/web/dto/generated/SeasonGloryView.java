// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 荣耀三件套（B14 §4：赛季数据不进主表，只有这三项抄回主存档）。
 *
 * <b>真相在赛季账本，不在这里</b>：这三项是逐季点查账本派生出来的（{@code SeasonLedgerStore#gloryOf}），主存档里那一份只是给面板读的<b>派生缓存</b> —— 缓存与账本不一致时以账本为准并就地修一次。所以这个对象<b>不代表任何独立于账本的事实</b>，客户端也不该把它当写回来的入口。
 *
 * <b>徽章按「每季一枚」记</b>，因此集合等于「我参与并已结算的赛季 id」；赛季数是归档保留数（个位数），逐季点查的代价可以接受。
 */
public record SeasonGloryView(
        int gloryLevel,   // 荣耀等级 = 参与过并结算过的赛季数。0 是合法值（本赛季还没结算），与「从没进过赛季」同义 —— 结算账本里没有记录时它就是 0。
        SeasonTier highestTier,   // 历史最高段位（各季里最好的一次，不是本赛季的）。从未结算过的人回 BRONZE 而不是 null：「没打过」与「打过但最低档」在面板上要长成同一个入口，缺一个非空值会让客户端到处判 null。
        List<String> badges)   // 赛季徽章，每季一枚（= 参与并已结算的赛季 id）。刻意不下发「徽章外观」那类字段：本版本没有徽章美术表，编一个外观 id 就是给下一个批次埋一个假契约。
{
}
