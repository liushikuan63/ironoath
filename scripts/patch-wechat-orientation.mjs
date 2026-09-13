#!/usr/bin/env node
/**
 * 职责：把 wechatgame 构建产物的游戏方向归一化为横屏（landscape）。
 * 依赖：无（只读写出参路径的 game.json）。
 *
 * <p>CocosCreator 的命令行 --build 只接受顶层参数，微信平台的方向在
 * `packages.wechatgame.orientation` 这个嵌套节点里，CLI 传不进去；而构建面板的
 * 选择又不会进仓库的 `client/settings`，所以它是一次"手工记忆"。
 * 这里把它变成一个构建后的确定步骤：产物生成后跑一次，结果只由脚本决定。
 *
 * <p>为什么必须横屏：设计分辨率是 960×640（`client/settings/v2/packages/project.json`），
 * 内城 6×6 网格与地图 3×3 视野都按横屏布局；竖屏启动会把画面压进竖屏画布，
 * 而这个问题在 web-mobile 构建里完全看不到。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const target = resolve(process.argv[2] ?? 'client/build/wechatgame/game.json')
const raw = readFileSync(target, 'utf8')
const game = JSON.parse(raw)

if (game.deviceOrientation === 'landscape') {
  console.log(`[patch-wechat-orientation] 已是 landscape，无需修改：${target}`)
  process.exit(0)
}

game.deviceOrientation = 'landscape'
// 保留原文缩进（Cocos 默认 4 空格），只替换方向字段，减少无意义 diff。
writeFileSync(target, `${JSON.stringify(game, null, 4)}\n`, 'utf8')
console.log(`[patch-wechat-orientation] 已改为 landscape：${target}`)
