// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 装备槽位（B06 §2.5：4 槽位）。取值与 equip 表的 slot 列一致。
 */
public enum EquipSlot {
    WEAPON,
    ARMOR,
    MOUNT,
    ACCESSORY
}
