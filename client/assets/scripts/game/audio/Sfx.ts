/**
 * 职责：音效的**数据与判定**——哪个键放哪张clip、该不该放、静音状态怎么从本机字符串读回来。
 * 依赖：无（引擎无关，可在 node:test 里跑 —— B00 铁律 2）。播放本身在 `scene/AudioService.ts`。
 *
 * <p><b>为什么把"该不该放"抽成纯函数</b>：微信小游戏里一次快速连点会真的叠出七八层同一声，
 * 那是最容易被玩家骂、又最难在单测里发现的缺陷。节流条件写在引擎代码里就只有真机能验，
 * 写在这里 ⇒ 一条断言就能钉住。
 *
 * <p><b>为什么只有三个角色</b>：`art-src/素材缺口清单.md` A14 原表列了六类语义提示
 * （确认/拒绝/建造完成/行军到达/战斗结果/邮件）+ 一条内城环境音，但源库里那六个 OGG
 * **全是 UI 点击声**（click / switch / tap 三种各两个变体），把它们分别贴到"建造完成"和"邮件"上，
 * 玩家听到的是同一个咔哒声 —— 那是用六个名字掩盖"其实只有一种声音"。所以这里只登记
 * **真的分得开**的三个角色（点哪都有 / 换页 / 有话要说），剩下两档（领奖与语义提示、环境音）
 * 需要另外取材，不假装已经做完。
 * <p>六个源文件里进包的是四个：`switch-b` 与 `click-a` 没有能分开的角色，
 * 按仓库纪律不预接没人消费的资源。
 */

/** 有真实区分度的三个音效角色。 */
export type SfxKey = 'tap' | 'nav' | 'alert'

/** SfxKey → resources 包内的路径（不带扩展名）。放在分包里，不进首包。 */
export const SFX_CLIP: Readonly<Record<SfxKey, string>> = {
  tap: 'audio/ui-tap',
  nav: 'audio/ui-switch',
  alert: 'audio/ui-click-alt',
}

/** 连点时交替用的两张 clip：同一声连放八次是"机关枪"，两个变体轮放才像物理按钮。 */
const TAP_FIRST = 'audio/ui-tap'
const TAP_SECOND = 'audio/ui-tap-alt'

/** 两次音效之间的最小间隔（毫秒）。小于这个值后一声直接丢弃，不排队。 */
export const SFX_MIN_GAP_MS = 90

/**
 * 取第 n 次点击该用哪张 clip。
 *
 * <p>为什么按次数而不是随机：随机会让"连点两下声音不一样"变成不可复现的现象，
 * 而交替是可以在单测里钉住的（第 1 下 A、第 2 下 B）。
 * <p>为什么不写成数组下标：本仓库开了 noUncheckedIndexedAccess，下标取回来是
 * `string | undefined`，得为一个人根本不会发生的越界再兜一层；答案只有两个，三元更贴近事实。
 */
export function tapVariant(index: number): string {
  return Math.abs(index) % 2 === 0 ? TAP_FIRST : TAP_SECOND
}

/**
 * 这一声该不该放。
 *
 * <p>`last === null` 表示还没放过（第一声一定放）。间隔不足时**丢弃而不是延后播放**：
 * 延后会让玩家听到"点了半天之前那下"，比少一声更怪。
 */
export function shouldPlay(nowMs: number, lastMs: number | null, gapMs: number = SFX_MIN_GAP_MS): boolean {
  if (lastMs === null) {
    return true
  }
  // 时间源在微信与浏览器上不同源，也可能被系统对时往回拨。回跳时**必须放行**：
  // 否则 `now - last` 长期为负，音效会从此永久不出声，而那看起来像"音频模块坏了"。
  if (nowMs < lastMs) {
    return true
  }
  return nowMs - lastMs >= gapMs
}

/** 本机存储里读回来的静音标记。缺键、脏值一律按"有声"处理 —— 新号不该是静音的。 */
export function readMuted(stored: string | null): boolean {
  return stored === '1'
}

/** 写进本机存储的静音标记（与 {@link readMuted} 成对，别在这里发明第三种表示）。 */
export function writeMuted(muted: boolean): string {
  return muted ? '1' : '0'
}

/** 静音键。与 deviceId 同一条通路（`sys.localStorage`），不同前缀，避免互相覆盖。 */
export const MUTE_STORAGE_KEY = 'ironoath.audioMuted'
