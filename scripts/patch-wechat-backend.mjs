// 职责：把微信小游戏产物指向指定后端（构建期写死的 localhost:8080 之外，一台设备无法用）。
// 依赖：node。用法：node scripts/patch-wechat-backend.mjs <buildDir> [httpOrigin]
//
// 为什么要有这一条：`baseUrl` / `wsUrl` 是 Cocos 场景里的**编辑器字段**，构建时被写死进
// `assets/main/index.js`（本轮实测：全包只有两处字面量 `http://localhost:8080` 与
// `ws://localhost:8080/ws`）。共享开发机上 8080 常被别人的旧构建占着 —— 于是"在开发者工具里
// 跑通"这句判断量的是**别人那台服务**（本轮就因此撞上四条端点 500）。而更根本的是：
// 真机与提审都不可能有 localhost，这个开关早晚要有，先让它落在构建脚本里而不是手改产物。
//
// urlCheck 一并关掉，且**只在显式给了非默认后端时**：开发者工具会校验请求域名是否在
// 公众平台的合法域名列表里，指到 http://127.0.0.1:8097 这类地址必然不在 —— 不关就是
// "请求全部被 IDE 拦掉"，症状看起来像客户端坏了。不传参时一个字节都不改。
import fs from 'node:fs'
import path from 'node:path'

const buildDir = process.argv[2]
const origin = process.argv[3] ?? ''
if (!buildDir) {
  console.error('用法：node scripts/patch-wechat-backend.mjs <buildDir> [httpOrigin]')
  process.exit(2)
}
if (!origin) {
  console.log('[patch-wechat-backend] 没给后端地址：保持产物原样（构建期写死的 http://localhost:8080）')
  process.exit(0)
}

const BAKED_HTTP = 'http://localhost:8080'
const BAKED_WS = 'ws://localhost:8080/ws'
const wsOrigin = origin.replace(/^http/, 'ws') + '/ws'

// 只扫 JS 与 JSON：包里的资源文件是二进制，按文本重写会把它改坏
function walkJs(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkJs(full, out)
    else if (/\.(js|json)$/.test(entry.name)) out.push(full)
  }
  return out
}

let hits = 0
for (const file of walkJs(buildDir)) {
  const text = fs.readFileSync(file, 'utf8')
  const next = text.split(BAKED_HTTP).join(origin).split(BAKED_WS).join(wsOrigin)
  if (next !== text) {
    hits += text.split(BAKED_HTTP).length - 1 + text.split(BAKED_WS).length - 1
    fs.writeFileSync(file, next)
  }
}
if (hits === 0) {
  // 换不动就必须报出来：静默通过等于"以为在打自己那台，其实还在打 8080"，
  // 而那正是本脚本要消灭的错法
  console.error(`[patch-wechat-backend][FAIL] 产物里找不到 ${BAKED_HTTP} 或 ${BAKED_WS}，`
    + '一处都没换到 —— 客户端仍会打构建期写死的那台，这次跑通不算数')
  process.exit(1)
}

const configPath = path.join(buildDir, 'project.config.json')
const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'))
cfg.setting = { ...cfg.setting, urlCheck: false }
fs.writeFileSync(configPath, JSON.stringify(cfg, null, 4))

console.log(`[patch-wechat-backend] 已换 ${hits} 处 → ${origin}（ws 为 ${wsOrigin}），并关闭 urlCheck`
  + '（IDE 的合法域名校验会拦非注册域名，不关的表现是"请求全被吞"）')
