#!/usr/bin/env node
/**
 * 职责：把 wechatgame 构建产物的 project.config.json 归一化成可被 IDE 打开的小游戏工程。
 * 依赖：无（只读写出参路径的 JSON）。
 *
 * <p>为什么要独立成文件：这段逻辑原先内嵌在 shell 的 `node -e '…'` 里，
 * 单引号与 JSON 里的引号一撞就被 shell 吃掉，表现是构建末尾抛一行莫名其妙的
 * Node 栈、配置却没改到。独立文件没有引号层，也不再有这类"只在某台机器上炸"的问题。
 *
 * <p>三条被实测证明必要的字段：
 * ① `appid`：Cocos 模板自带 `wx6ac3f5090a6b99c5`（游客小游戏号），新版 IDE 会拒绝打开；
 * ② `isGameTourist: false`：微信官方快速启动模板里带这一条，缺了它部分 IDE 版本
 *    会把真实小游戏号当游客态处理（拒开或不给扫码预览入口）；
 * ③ `libVersion: "latest"`：与官方模板一致，少一类"只有某台机器能打开"的差异。
 */

import { readFileSync, writeFileSync } from 'node:fs'

const [target, appid] = process.argv.slice(2)
if (target === undefined || appid === undefined) {
  console.error('[patch-wechat-config] 用法：node scripts/patch-wechat-config.mjs <project.config.json> <appid>')
  process.exit(1)
}

const config = JSON.parse(readFileSync(target, 'utf8'))
const changes = []

if (config.appid !== appid) {
  config.appid = appid
  changes.push(`appid=${appid}`)
}
if (config.isGameTourist !== false) {
  config.isGameTourist = false
  changes.push('isGameTourist=false')
}
if (config.libVersion !== 'latest') {
  config.libVersion = 'latest'
  changes.push('libVersion=latest')
}

if (changes.length === 0) {
  console.log(`[patch-wechat-config] 已是目标状态：${target}`)
} else {
  writeFileSync(target, `${JSON.stringify(config, null, 4)}\n`, 'utf8')
  console.log(`[patch-wechat-config] 已更新（${changes.join(', ')}）：${target}`)
}
