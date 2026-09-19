/**
 * 职责：流亡迁城按钮的判定、文案与二次确认（B08 §5 反击工具箱第 4 条的客户端半边）。
 * 依赖：无（纯函数，不碰 cc、不碰传输）。
 *
 * <p><b>为什么要点进来先弹二次确认</b>：这是一次不可撤销的搬家 —— 落点是随机的、
 * 落地免战 12 小时、冷却 3 天，而且点了之后盟友找不到你在哪。误触一次的代价
 * 比一次付费礼包还大，所以确认文案要把三件事一次说全，而不是「确定吗？」。
 *
 * <p><b>判定顺序必须与服务端一致</b>：{@code ExileAppService.doExile} 是先查冷却、
 * 再查在外的队伍。客户端反过来排的话，会出现「按钮亮着、点了回冷却中」——
 * 玩家看到的是按钮骗人，而这类不一致没有任何一方会报错。
 */

/** 服务端下发的事实。{@code nextExileAt} 为 null 表示随时可以，不是 0。 */
export interface ExileFacts {
  readonly nextExileAt: number | null
  readonly troopsAway: number
  readonly requesting: boolean
  /** 服务端时刻（已按 TimeSync 校准）。用本地时刻会让倒计时提前或延后走完。 */
  readonly serverNow: number
}

export type ExileAvailability = 'ready' | 'requesting' | 'cooling' | 'troops-away'

export function exileAvailability(f: ExileFacts): ExileAvailability {
  if (f.requesting) {
    return 'requesting'
  }
  if (f.nextExileAt !== null && f.nextExileAt > f.serverNow) {
    return 'cooling'
  }
  if (f.troopsAway > 0) {
    return 'troops-away'
  }
  return 'ready'
}

/** 剩余时长文案：向上取整到分钟，小时以上再折叠成「N 天 M 小时」。 */
export function formatRemaining(ms: number): string {
  if (ms <= 0) {
    return '现在'
  }
  const totalMinutes = Math.ceil(ms / 60_000)
  if (totalMinutes < 60) {
    return `${totalMinutes} 分钟`
  }
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours < 24) {
    return minutes === 0 ? `${hours} 小时` : `${hours} 小时 ${minutes} 分`
  }
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

/** 按钮主文字。禁用态也要说清是「为什么」不行，而不是灰着不解释。 */
export function exileLabel(f: ExileFacts): string {
  switch (exileAvailability(f)) {
    case 'requesting':
      return '迁城中…'
    case 'cooling':
      return `冷却 ${formatRemaining((f.nextExileAt ?? 0) - f.serverNow)}`
    case 'troops-away':
      return '先召回队伍'
    case 'ready':
      return '流亡迁城'
  }
}

/** 按钮下方的说明行；ready 时返回空串（不占位，避免一屏常驻废话）。 */
export function exileHint(f: ExileFacts): string {
  switch (exileAvailability(f)) {
    case 'cooling':
      return '上次搬家还在冷却中，冷却是 3 天滚动窗口'
    case 'troops-away':
      return '有队伍在门外，搬家会让它回到一个已经没有你的格子，先召回'
    case 'requesting':
      return '正在申请新的安家地点…'
    case 'ready':
      return ''
  }
}

/**
 * 二次确认文案。三件事必须齐全：落点随机、免战多久、多久之后才能再搬。
 * 少任何一件，玩家都会在搬完之后主张「我没被告知过」。
 */
export function exileConfirmText(f: ExileFacts): string {
  const base = '将随机迁往一处空地，落地后免战 12 小时，3 天内不能再次迁城。盟友会暂时找不到你。'
  const hint = exileHint(f)
  return hint === '' ? base : `${base}${hint}`
}

/** 是否可以发起（场景据此决定按钮是灰的还是能按的）。 */
export function exileCanRequest(f: ExileFacts): boolean {
  return exileAvailability(f) === 'ready'
}
