/**
 * 职责：资源类型的中文名（客户端**唯一一份**）。
 *
 * <p>名字本身与 `contract/config/resource.json` 的 `name` 列逐字相同（木材/石料/铁矿/粮草/金币/体力） ——
 * 也就是说它是**配置表数据的客户端副本**。理想形态是服务端下发（B00 的口径：客户端不得自行翻译配置表文案，
 * 与收口清单 #255 把建筑名改成服务端下发是同一条），但 `ResourceAmount` 现在只有 `type`/`amount` 两个字段，
 * 契约里 `ResourceAmount` 又在 army / bag / tech 三份 schema 里各有一份定义、服务端有 5 处构造点 ——
 * 那是一次独立的契约改动，已记进项目队列等单独一格做（**在那之前这里是唯一真源，不许再抄第四份**）。
 *
 * <p>原先这份映射私下长在 `offline/OfflineReport.ts` 里（只有它一个使用者）；V03-a 研究页要显示"下一级成本"
 * 才暴露了它其实是跨模块的事实，所以搬到 `ui/` 下并为两处共用。
 */

/** 资源类型 → 中文名。表里没有的取值退回原文，不显示空行（与其它 label 表同一条纪律）。 */
export const RESOURCE_NAMES: Readonly<Record<string, string>> = {
  WOOD: '木材', STONE: '石料', IRON: '铁矿', GRAIN: '粮草', GOLD: '金币', STAMINA: '体力',
}

export function resourceName(type: string): string {
  return RESOURCE_NAMES[type] ?? type
}
