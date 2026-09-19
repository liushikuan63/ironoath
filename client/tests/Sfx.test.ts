/**
 * 职责：音效数据层的判定 —— 节流、连点变体、静音读写、角色到 clip 的映射是否自洽。
 * 依赖：node:test / node:assert / node:fs（clip 对账要读磁盘）。
 *
 * <p>为什么每条都值得红：
 * ① 节流条件写错（比如把"间隔不足"判成"该放"）只有真机能听见，单测是唯一能钉住它的地方；
 * ② 变体随机化会让"连点两下不一样"变成不可复现的现象，所以必须是按次数交替；
 * ③ 静音读写不对称（写 'true' 读 '1'）的表现是"静音存不下来，下次启动又有声"——
 *    提审时这是一条会被录屏的缺陷。
 */

import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  SFX_CLIP, SFX_MIN_GAP_MS, MUTE_STORAGE_KEY,
  readMuted, writeMuted, shouldPlay, tapVariant,
} from '../assets/scripts/game/audio/Sfx'

test('三个角色各自指向不同的 clip：同一段声音挂两个名字就是"其实只有一种声音"', () => {
  const clips = Object.values(SFX_CLIP)
  assert.equal(clips.length, 3)
  assert.equal(new Set(clips).size, clips.length, `有角色共用同一张 clip：${clips.join(', ')}`)
  for (const clip of clips) {
    assert.ok(clip.startsWith('audio/'), `clip 必须落在 resources 的 audio 目录下：${clip}`)
  }
})

test('连点按次数交替取变体，第 1/3/5 下与第 2/4 下分别是同一张（可复现，不是随机）', () => {
  assert.equal(tapVariant(0), tapVariant(2))
  assert.notEqual(tapVariant(0), tapVariant(1))
  // 首下必须等于 SFX_CLIP.tap：否则"预加载 tap"与"实际放出来的第一下"是两张图，
  // 第一声会因为没有预加载而延迟 —— 玩家感知的正是第一下。
  assert.equal(tapVariant(0), SFX_CLIP.tap)
  assert.equal(tapVariant(-1), tapVariant(1), '负数下标不该越界取到 undefined')
})

test('节流：首声必放，间隔不足丢弃而不是排队', () => {
  assert.equal(shouldPlay(1_000, null), true)
  assert.equal(shouldPlay(1_000 + SFX_MIN_GAP_MS, 1_000), true)
  assert.equal(shouldPlay(1_000 + SFX_MIN_GAP_MS - 1, 1_000), false)
  // 时间源回跳（微信/浏览器不同源、系统对时往回拨）时**放行**：
  // 判成"不放"会让音效从此永久不出声，而那看起来像音频模块坏了
  assert.equal(shouldPlay(900, 1_000), true, '时钟回跳不能把音效永久锁死')
})

test('静音读写成对，脏值一律按"有声"处理（新号不该一上来就是静音的）', () => {
  assert.equal(readMuted(writeMuted(true)), true)
  assert.equal(readMuted(writeMuted(false)), false)
  for (const dirty of [null, '', 'true', 'yes', '0', '2']) {
    assert.equal(readMuted(dirty), false, `脏值 ${JSON.stringify(dirty)} 不该被读成静音`)
  }
  assert.ok(MUTE_STORAGE_KEY.startsWith('ironoath.'), '本机键要带项目前缀，别与 deviceId 抢')
})

test('登记到的每一张 clip 在磁盘上真的有文件（键表 → 分包）', () => {
  // 与 ArtFamilies 那条同一条纪律：有键没文件 ⇒ 面板上是一个永远不出声的位，
  // 而且只有玩家听得见"点了没反应"。
  const resourcesDir = path.join(repoRoot(), 'client', 'assets', 'resources')
  const wanted = Object.values(SFX_CLIP).concat([tapVariant(0), tapVariant(1)])
  for (const clip of wanted) {
    assert.ok(fs.existsSync(path.join(resourcesDir, `${clip}.ogg`)),
      `分包里没有 ${clip}.ogg —— 这个角色的音效永远不会响`)
  }
})

/** 测试默认跑在 client/ 下（test-client.sh 会 cd），但为别的 cwd 也能跑，逐级向上找仓库根。 */
function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'hero.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/hero.json，无法做 clip 对账')
}
