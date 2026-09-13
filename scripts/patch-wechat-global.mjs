#!/usr/bin/env node
/**
 * 职责：给微信小游戏产物补上 `global` 别名（Node 全局名 → 小游戏全局对象）。
 * 依赖：无。
 *
 * <p><b>为什么必须补</b>：小游戏环境只有 `GameGlobal`（以及 `globalThis`），没有 Node 的 `global`。
 * 而引擎/依赖里存在<b>直接引用</b> `global` 的分支（不是 `typeof global` 那种安全探测），
 * 表现就是开发者工具里第一屏直接抛：
 * <pre>WAGame.js:1 ReferenceError: global is not defined</pre>
 * 这一行必须在任何其他代码之前执行 —— 放到 `__initApp` 里已经太晚（Android 上
 * `__initApp` 会被 `requestAnimationFrame` 延后到第二帧，而报错发生在更早的模块初始化阶段）。
 *
 * <p>用 `globalThis.global = globalThis.global || globalThis` 而不是 `global = globalThis`：
 * 前者在已经提供 `global` 的运行时不改变既有值（幂等），也能被 `typeof global` 探测到。
 */

import { readFileSync, writeFileSync } from 'node:fs'

const [target] = process.argv.slice(2)
if (target === undefined) {
  console.error('用法：node scripts/patch-wechat-global.mjs <game.js>')
  process.exit(1)
}

const SHIM = 'globalThis.global = globalThis.global || globalThis;\n'
const source = readFileSync(target, 'utf8')

if (source.startsWith(SHIM)) {
  console.log(`[patch-wechat-global] 兼容补丁已存在：${target}`)
  process.exit(0)
}

writeFileSync(target, SHIM + source, 'utf8')
console.log(`[patch-wechat-global] 已补 global 别名：${target}`)
