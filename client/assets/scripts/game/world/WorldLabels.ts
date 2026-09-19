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

import type { WorldEntity, WorldEntityType } from '../../net/generated/WorldProtocol'
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

/** 名牌板子的高度（与 `WorldMap.drawCaptionPlate` 画的那一条一致，两处只此一份定义）。 */
export const CAPTION_PLATE_HEIGHT = 18

/**
 * 名牌板子的宽度：按字数估（CJK 12px 字号 ≈ 每字 13px），上限 150。
 *
 * <p>为什么从视图里搬出来：藏牌要先知道牌有多宽。公式留在 `WorldMap` 里的话，
 * 纯函数就得抄第二份，而"抄第二份"正是本项目反复出问题的形状。
 */
export function captionPlateWidth(caption: string): number {
  return Math.min(150, 14 + caption.length * 13)
}

/** 名牌板子在 mapLayer 本地系里的盒子：中心 = 实体位置上方 `iconSize/2 + 11`。 */
export interface CaptionBox {
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
}

export function captionBox(centerX: number, centerY: number, iconSize: number, caption: string): CaptionBox {
  const width = captionPlateWidth(caption)
  return {
    x: centerX - width / 2,
    y: centerY + iconSize / 2 + 11 - CAPTION_PLATE_HEIGHT / 2,
    width,
    height: CAPTION_PLATE_HEIGHT,
  }
}

/**
 * 谁的名牌在挤的时候留下：城 > 资源 > 联盟建筑 > 怪物 > 行军。
 *
 * <p>理由：玩家扫这张图最先要回答的是"这是谁的地盘"和"这里能采什么"，
 * 野怪等级在自己头上有图标形状可辨，行军名牌是自家队伍的临时信息、看一眼就够。
 */
const CAPTION_PRIORITY: Readonly<Record<WorldEntityType, number>> = {
  CITY: 0,
  RESOURCE: 1,
  BUILDING: 2,
  MONSTER: 3,
  MARCH: 4,
  EMPTY: 5,
}

export function captionPriority(type: WorldEntityType): number {
  return CAPTION_PRIORITY[type] ?? CAPTION_PRIORITY.EMPTY
}

/** 参与藏牌判定的一个候选名牌。`key` 由调用方给（渲染器用 `type:id`），`pinned` 表示玩家正选中它。 */
export interface CaptionCandidate {
  readonly key: string
  readonly type: WorldEntityType
  readonly box: CaptionBox
  readonly pinned?: boolean
}

function boxesOverlap(a: CaptionBox, b: CaptionBox): boolean {
  return Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 1
    && Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 1
}

/**
 * 从一堆候选名牌里挑出"该画字"的那些：按优先级贪心占位，与已留下的牌相交就藏。
 *
 * <p><b>为什么必须藏而不是挪</b>：缩放 1 时一格 30px，而一张最窄的牌 40px 宽、18px 高 ——
 * 横向相邻两格的两张牌**几何上就放不下**（实测 4 对压叠全是同一行、原点相差 30px）。
 * 挪位只会把冲突推到下一格，调字号是把缺陷藏进更小更难读的字里。
 *
 * <p>被 `pinned`（玩家选中）的那一张**一定留**，与它冲突的高优先牌反而让位 ——
 * 选中项被藏起来，玩家会以为点没生效。
 *
 * <p>结果与输入顺序无关（同优先级按 `key` 定序），所以同一帧重画不会让牌子闪来闪去。
 */
export function pickVisibleCaptions(candidates: readonly CaptionCandidate[]): Set<string> {
  const sorted = [...candidates].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned === true ? -1 : 1
    const rank = captionPriority(a.type) - captionPriority(b.type)
    if (rank !== 0) return rank
    return a.key < b.key ? -1 : a.key > b.key ? 1 : 0
  })
  const kept: CaptionCandidate[] = []
  for (const candidate of sorted) {
    if (candidate.box.width <= 0 || candidate.box.height <= 0) {
      continue
    }
    if (kept.some((other) => boxesOverlap(other.box, candidate.box))) {
      continue
    }
    kept.push(candidate)
  }
  return new Set(kept.map((candidate) => candidate.key))
}
