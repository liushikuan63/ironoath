/**
 * 职责：大地图上实体格的那一行字 —— 引擎无关，可脱离 Cocos 跑单测（B00 铁律 2）。
 * 依赖：`game/ui/ResourceNames`（资源中文名的唯一一份）。
 *
 * <p><b>为什么从 `scene/WorldMap.ts` 里搬出来</b：这个函数原本长在场景文件里，于是
 * "地图格子上印的是 `铁矿` 还是 `IRON`" 这条判据**只能靠跑真产物去看截图**，而默认缩放档
 * 根本不画资源格标签（`renderEntities` 只在 `zoom > 0` 时给文案），headless 又送不进触摸
 * —— 结果那道门永远走不到正向分支（台账 #269/#272 记的就是这件事）。搬进无引擎依赖的模块后，
 * 同一件事改成单测判：不需要浏览器、不需要缩放、不需要点得动任何东西。
 *
 * <p><b>纪律照旧</b>：只搬运服务端已经下发的字段，客户端一个都不自己判（等级、昵称、
 * 资源类型都照原样显示）。唯一允许的加工是**把枚举翻成中文名**，因为服务端给的是
 * `WOOD/STONE/IRON/GRAIN` 这种枚举原文，印给玩家读不出来（#255 同族）。
 */

import type { WorldEntity } from '../../net/generated/WorldProtocol'
import { resourceName } from '../ui/ResourceNames'

/**
 * 实体上的文字。空串表示这一格**不该画标签**，调用方不得把空串显示成 `null` 或 `undefined`。
 */
export function entityCaption(entity: WorldEntity): string {
  const level = entity.level === null ? '' : `Lv${entity.level}`
  switch (entity.type) {
    case 'CITY':
      return entity.ownerName ?? level
    case 'MONSTER':
      return level
    case 'RESOURCE':
      // 服务端只给 `WOOD/STONE/IRON/GRAIN` 这个枚举，格子上印原文玩家读不出（#255 同族）
      return entity.resourceType === null ? '' : resourceName(entity.resourceType)
    case 'BUILDING':
      return entity.allianceTag ?? level
    case 'MARCH':
      return entity.load === null ? '' : `${entity.load}`
    case 'EMPTY':
    default:
      return ''
  }
}
