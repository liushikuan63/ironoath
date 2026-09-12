// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 计价货币。取值必须与 game-config 的 `ShopCfg.PriceCurrency` 与 `shop.json` 的 fieldTypes 声明逐字一致（由 `ShopContractParityTest` 钉住三处）。
 *
 * 四个取值的账本各不相同：GOLD 是 resource 表里的金币（`PlayerWallet`）；ALLIANCE_COIN 是成员个人贡献值（`Alliance.contributions`，B10 明写「贡献值可兑换联盟商店道具」）；SQUAD_COIN 是小队币（`Squad.squadCoins`，由小队互助产出）；SEASON_COIN 是赛季结算发的赛季币（`SeasonLedger`）—— 它的**用途口径尚未裁决**（见收口清单 #6b），而且当前唯一一行的货品是 `item_buff_rally_2h`，一个效果没有定义数值的道具（#19），所以本文件里它是枚举成员但不可购买。
 */
public enum ShopCurrency {
    GOLD,
    ALLIANCE_COIN,
    SQUAD_COIN,
    SEASON_COIN
}
