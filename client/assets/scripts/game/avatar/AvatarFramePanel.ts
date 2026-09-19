/**
 * 职责：外观页（B24 块③ 头像框）的展示数据组装。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>外观不参与任何数值</b>（B15 §一 第 7 条 + C00 公理一）：本模块与协议里都没有战力/属性字段，
 * 「戴上框」不会让任何数字变大。这一条不是靠注释保证的，是靠契约里没有那几位 + 服务端
 * 的判别性用例（只做外观操作时逐字段相等）。
 *
 * <p><b>能不能戴只信服务端</b>（铁律 2）：`owned` / `worn` 两位都由服务端算 ——
 * 「拥有」是永久事实（卸下之后仍是 true），「佩戴」是当下选择。客户端不自己推：
 * 它推不出「这一枚是买的还是发的」，而推错的后果是给出一颗点了必然报错的按钮。
 *
 * <p><b>未拥有的框照样列出来</b>：与商店把等级不够的货列出来同一条口径 ——
 * 让玩家看见有什么可收集，正是外观存在的意义。
 */

import type { AvatarFrameListResp, AvatarFrameView } from '../../net/generated/Protocol'

/** 没戴任何框时预览圈用的中性色（暗铜灰，与面板配色同源）。 */
const NEUTRAL_COLOR = '#6B6257'

/** 颜色列的兜底：表里写坏了也不画一个透明框（看不见的框 = 玩家以为没戴上）。 */
const COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/

/** 一枚框在列表里的那一行。 */
export interface AvatarFrameRow {
  readonly frameId: string
  readonly name: string
  /** 稀有度，原样显示（`N/R/SR/SSR`，与武将面板同一套写法） */
  readonly rarityText: string
  /** 归一化后的 `#RRGGBB`；表里写坏时退回中性色 */
  readonly color: string
  readonly owned: boolean
  readonly worn: boolean
  /** 「佩戴中」/「已拥有」/「未拥有」 */
  readonly stateText: string
  /** 按钮文案；未拥有时是 null（那颗按钮不该存在 —— 点了也没用） */
  readonly actionText: string | null
  /** 点击要做的动作；未拥有时是 null */
  readonly action: 'wear' | 'unwear' | null
}

/** 整块外观页视图。 */
export interface AvatarFramePanelView {
  readonly rows: readonly AvatarFrameRow[]
  /** 正戴着的那一枚；没戴是 null */
  readonly wornFrameId: string | null
  /** 预览圈旁边的说明：「佩戴中：赛季征战框」/「未佩戴头像框」 */
  readonly wornText: string
  /** 预览圈的颜色（没戴 = 中性色） */
  readonly previewColor: string
  /** 预览头像上那个字（昵称首字；昵称为空时是「君」）—— 框要围着什么东西才看得出是框 */
  readonly initialText: string
  /** 「已拥有 1 / 2」 */
  readonly ownedCountText: string
  /** 一枚都还没拿到时那一句；有拥有的框时为 null */
  readonly emptyText: string | null
  /** 列表还没拉回来时那一句；拉到了为 null */
  readonly noticeText: string | null
}

/**
 * 预览头像上那个字：昵称首字。用 `Array.from` 而不是 `[0]` —— 昵称允许 emoji 与生僻字，
 * 按 UTF-16 码元取第一个字符会把一个 emoji 劈成半个，画出来是一个方框（与客户端
 * 别处「不用迭代器 spread」的理由同源：目标运行时不保证展开语法）。
 */
export function avatarInitialOf(nickName: string): string {
  const chars = Array.from(nickName.trim())
  return chars.length > 0 ? (chars[0] ?? '君') : '君'
}

/** 颜色归一化。表里写坏了退回中性色 —— 一个透明框看起来就像"没戴上"。 */
export function normalizeFrameColor(raw: string | null | undefined): string {
  return raw !== null && raw !== undefined && COLOR_PATTERN.test(raw) ? raw.toUpperCase() : NEUTRAL_COLOR
}

/** 一行现在是什么状态。三种状态各有各的话，不合并成「可用/不可用」。 */
export function frameStateText(frame: AvatarFrameView): string {
  if (frame.worn) {
    return '佩戴中'
  }
  return frame.owned ? '已拥有' : '未拥有'
}

/**
 * 这一行能做什么。
 *
 * <p>没拥有的返回 null 而不是「去商店」：`AvatarFrameView` 里没有「在哪买」这一位，
 * 而表里第二枚（拓荒者框）根本不在商店 —— 给每个未拥有的框都写「去商店」会造出一句假指向，
 * 那正是 B24 验收 5 在修的毛病。引导放在页脚那句通用的话里。
 */
export function frameActionOf(frame: AvatarFrameView): 'wear' | 'unwear' | null {
  if (!frame.owned) {
    return null
  }
  return frame.worn ? 'unwear' : 'wear'
}

/** 一行。 */
export function buildAvatarFrameRow(frame: AvatarFrameView): AvatarFrameRow {
  const action = frameActionOf(frame)
  return {
    frameId: frame.frameId,
    name: frame.name,
    rarityText: frame.rarity,
    color: normalizeFrameColor(frame.placeholderColor),
    owned: frame.owned,
    worn: frame.worn,
    stateText: frameStateText(frame),
    actionText: action === null ? null : (action === 'unwear' ? '卸下' : '佩戴'),
    action,
  }
}

/**
 * 组装整块外观页。
 *
 * @param resp  GET /player/frames 的响应；null = 还没拉回来（画一句说明，不画一个假列表）
 * @param nickName 玩家昵称（用于预览圈中央那个字）；空串时退回「君」
 */
export function buildAvatarFramePanel(resp: AvatarFrameListResp | null,
  nickName = ''): AvatarFramePanelView {
  const initialText = avatarInitialOf(nickName)
  if (resp === null || resp === undefined) {
    return {
      rows: [], wornFrameId: null, wornText: '未佩戴头像框', previewColor: NEUTRAL_COLOR,
      initialText, ownedCountText: '', emptyText: null,
      noticeText: '外观列表还没拉回来，稍后再试',
    }
  }
  const rows = resp.frames.map((frame) => buildAvatarFrameRow(frame))
  const worn = rows.find((row) => row.worn) ?? null
  const ownedCount = rows.filter((row) => row.owned).length
  return {
    rows,
    wornFrameId: worn?.frameId ?? null,
    wornText: worn === null ? '未佩戴头像框' : `佩戴中：${worn.name}`,
    previewColor: worn?.color ?? NEUTRAL_COLOR,
    initialText,
    ownedCountText: `已拥有 ${ownedCount} / ${rows.length}`,
    // 一枚都没有时才说这句：它是给"第一次点进来、什么都没有"的人的解释，
    // 已经有框的人不需要被再教育一次
    emptyText: ownedCount === 0 && rows.length > 0
      ? '还没有拿到任何头像框 —— 商店里能兑换到的框，买下之后会出现在这里'
      : null,
    noticeText: null,
  }
}

/** 佩戴 / 卸下要发的请求体（requestId 由传输层注入）。没拥有时返回 null，调用方不发请求。 */
export function wearBodyOf(row: AvatarFrameRow): { frameId: string | null } | null {
  if (row.action === null) {
    return null
  }
  return { frameId: row.action === 'unwear' ? null : row.frameId }
}

/** 操作之后给玩家的那句话（用服务端回执里的名字，不用本地那份可能过期的一行）。 */
export function wearResultText(frames: readonly AvatarFrameView[]): string {
  const worn = frames.find((frame) => frame.worn)
  return worn === undefined ? '已卸下头像框' : `已戴上「${worn.name}」`
}
