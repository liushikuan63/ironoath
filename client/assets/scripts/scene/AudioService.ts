/**
 * 职责：音效的**播放侧**——把 `game/audio/Sfx` 的四个角色接到真实引擎与真实平台上。
 * 依赖：cc（场景层，允许 import 'cc' —— B00 铁律 2 的另一半）。
 *
 * <p><b>为什么只有一个监听点</b>：全仓库的按钮都是自绘节点 + `node.on('touch-start')`，
 * 六十余处绑定散在二十个视图里，没有一个统一入口。逐个去接音效会做出两件坏事：
 * 漏掉几个（玩家在某些面板点了没声，而"某些"没人记得清），以及把音效逻辑复制六十遍。
 * 所以这里挂在**全局输入**上（`input.on(TOUCH_START)`）：它看到的是每一次触摸，
 * 与节点树谁处理了无关。
 *
 * <p><b>三条平台约束都写在这里，不写进视图</b>：
 * ① **首次手势之后才出声** —— 浏览器与微信都禁止在用户交互前播音频，提前播会被静默丢弃，
 *    Chrome 还会把 AudioContext 停在 `suspended`。所以 `armed` 之前一律不放，
 *    第一次触摸本身也不响（它正是解锁那一下）。
 * ② **失焦暂停、回前台不叠播** —— 切后台时把节流基准推到"回前台的那一刻"，
 *    否则后台攒下的时间差会让回前台的第一声之后紧跟一串补播。
 * ③ **静音是本机偏好，不是账号数据** —— 换设备应该重新按玩家的耳朵来，
 *    所以走 `sys.localStorage`（与 deviceId 同一条通路），不上服务端。
 */

import { AudioClip, Game, Input, Node, AudioSource, game, input, resources, sys } from 'cc'
import {
  MUTE_STORAGE_KEY, SFX_CLIP, SfxKey, readMuted, shouldPlay, tapVariant, writeMuted,
} from '../game/audio/Sfx'

/** 已加载好的 clip。没加载完就点了 ⇒ 这一声直接跳过（不排队补播，理由见 Sfx.shouldPlay）。 */
const clips = new Map<string, AudioClip>()
const loading = new Set<string>()
let source: AudioSource | null = null
let lastPlayedMs: number | null = null
let tapIndex = 0
/** 首次手势之前一律不出声。 */
let armed = false
let muted = false
let installed = false

/**
 * 装上音效层。由 `GameBootstrap` 在装配阶段调一次，重复调用无效果。
 *
 * @param host 挂 `AudioSource` 的节点 —— 必须属于**当前活跃场景**，
 *             否则组件不会被调度，`playOneShot` 一声都不出（而且不报错）。
 */
export function installAudio(host: Node): void {
  if (installed) {
    return
  }
  installed = true
  muted = readMuted(readStored())
  const node = new Node('AudioService')
  host.addChild(node)
  source = node.addComponent(AudioSource)
  source.playOnAwake = false
  source.loop = false
  // 两个 tap 变体不在 SFX_CLIP 的角色里（tap 走 tapVariant），要单独预热，
  // 否则第二声开始才会加载完 —— 玩家听到的正是"第一二下不一致"
  for (const clip of [SFX_CLIP.nav, SFX_CLIP.alert, tapVariant(0), tapVariant(1)]) {
    loadClip(clip)
  }
  bindGlobalTouch()
  // 只读诊断出口挂在这里而不是 GameBootstrap：这样"音效层的状态"与"音效层"同一个归属，
  // 量具不用知道装配层怎么写的。**只挂读函数、不挂状态本身**，页面改不动它。
  // ⚠️ 命名空间前缀 `__ironoath`，避免与别的全局撞名（项目 AGENTS.md §五不许把内部 id 印给玩家，
  // 这里虽不面向玩家，但同一精神：内部标识一律带私有前缀）。
  ;(globalThis as Record<string, unknown>).__ironoathAudioDiagnostics = audioDiagnostics
}

/** 换页/领奖这类"由代码发起"的声音也走这里，视图不需要知道音频存在。 */
export function playSfx(key: SfxKey): void {
  if (!armed || muted || source === null) {
    return
  }
  const now = Date.now()
  if (!shouldPlay(now, lastPlayedMs)) {
    return
  }
  // 只取一次路径：pathFor 会推进 tap 的交替计数，调两下就变成"每声跳一个变体"，
  // 交替也就没了（而且第 2、4、6 下永远查不到已加载的那张）
  const path = pathFor(key)
  const clip = clips.get(path)
  if (clip === undefined) {
    // 还没加载完：跳过这一声，顺手补一次加载。排队补播会放出"点了半天之前那下"，更怪
    loadClip(path)
    return
  }
  lastPlayedMs = now
  source.playOneShot(clip, 1.0)
}

/** 当前是否静音（设置页那一行的文案要用）。 */
export function isMuted(): boolean {
  return muted
}

/**
 * **只读**诊断快照：把"这一声为什么没出来"需要的那几个值一次读全。
 *
 * <p>为什么需要（2026-10-04）：`verify-audio-runtime` 报「连点五次一次都没发声（停在 0）」，
 * 而 `playSfx`（见上）在三种情况下**静默 return、不排队**：`!armed` / `muted` / `source === null`。
 * 这几个值原本都是模块内局部变量，页面里读不到 ⇒ 量具只能看着"0 次发声"猜。
 * 已排除的：点击没送达页面（canvas 底层 pointer 计数 = 12）、页面报错、资源缺失、远程 bundle。
 * ⇒ 这个出口把剩下的三个候选一次分开。
 *
 * <p>**纯读**：不改任何状态，也不被播放路径调用；`clipKeys` 排过序，返回新数组。
 */
export interface AudioDiagnostics {
  /** 首次手势之后才 true —— 见 bindGlobalTouch：第一次触摸只解锁，不配音效。 */
  armed: boolean
  muted: boolean
  /** `cc.AudioSource` 是否装上了（装不上时一声不出且不报错）。 */
  hasSource: boolean
  /** 服务自己这张 clip 表的键，**已加载好**的那些 —— 探针读资源缓存 ≠ 这张表已就绪。 */
  clipKeys: string[]
  /** 正在加载中的 clip 键。 */
  loadingKeys: string[]
  /** 上一声成功播放的时刻；null = 这一局还没响过。 */
  lastPlayedMs: number | null
  /** 当前连点计数（tapVariant 的 n）。 */
  tapIndex: number
}

export function audioDiagnostics(): AudioDiagnostics {
  return {
    armed,
    muted,
    hasSource: source !== null,
    clipKeys: [...clips.keys()].sort(),
    loadingKeys: [...loading].sort(),
    lastPlayedMs,
    tapIndex,
  }
}

/** 切换静音并落本机存储。返回切换后的状态，让调用方直接拿去刷新界面。 */
export function setMuted(next: boolean): boolean {
  muted = next
  try {
    sys.localStorage.setItem(MUTE_STORAGE_KEY, writeMuted(next))
  } catch (error) {
    // 存不下来只是下次启动回到原状态，不该让"点静音"这一下没反应
    console.warn('[audio] 静音状态写不进本机存储（本次仍然生效）', error)
  }
  return muted
}

/** tap 是唯一起用变体的角色：连点两下同一段采样听起来像卡带。 */
function pathFor(key: SfxKey): string {
  if (key === 'tap') {
    return tapVariant(tapIndex++)
  }
  return SFX_CLIP[key]
}

function loadClip(path: string): void {
  if (clips.has(path) || loading.has(path)) {
    return
  }
  loading.add(path)
  resources.load(path, AudioClip, (error, clip) => {
    loading.delete(path)
    // Cocos 成功时把第一个参数留成 undefined（不是 null），所以这里只能用真值判断：
    // 写成 `error !== null` 会把**每一次成功**都判成失败，四张 clip 全部丢进告警分支，
    // 症状是"音频模块装好了、一声不出、日志里全是加载失败"。
    if (error || !clip) {
      // 音频缺失不影响可玩性，但必须留痕：否则"怎么没声音"会变成一轮无方向排查
      console.warn(`[audio] clip 加载失败：${path}`, error)
      return
    }
    clips.set(path, clip)
  })
}

function readStored(): string | null {
  try {
    return sys.localStorage.getItem(MUTE_STORAGE_KEY)
  } catch (error) {
    console.warn('[audio] 本机存储不可用，本次按有声处理', error)
    return null
  }
}

/** 全局触摸接线单独拆出来：一次点击只登记一次监听，装不上就该在日志里看见。 */
function bindGlobalTouch(): void {
  input.on(Input.EventType.TOUCH_START, () => {
    if (!armed) {
      // 第一次触摸是"解锁音频"那一下，不配音效：平台在这一刻才允许出声，
      // 强行放会有一声被丢，玩家听到的是"第一下没反应，后面才有"
      armed = true
      return
    }
    playSfx('tap')
  })
  game.on(Game.EVENT_HIDE, () => {
    // 回前台后第一声之前要有完整间隔：把基准推到"现在"，后台期间不攒补播
    lastPlayedMs = Date.now()
  })
}
