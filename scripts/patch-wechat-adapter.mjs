import { readFileSync, writeFileSync } from 'node:fs'

const [target] = process.argv.slice(2)
if (target === undefined) {
  console.error('用法：node scripts/patch-wechat-adapter.mjs <web-adapter.js>')
  process.exit(1)
}

let source = readFileSync(target, 'utf8')
/**
 * Cocos 3.8.7 的 DevTools 分支用 `window` / `global.document` 去查属性描述符。
 * 小游戏是 worker 环境：没有 `window` 这个全局绑定，`GameGlobal.document` 也可能还没建，
 * 于是 `Object.getOwnPropertyDescriptor(undefined, c)` 直接抛
 * "TypeError: Cannot convert undefined or null to object"（开发者工具实测，栈落在 dom-parser）。
 * 这里的修法有三条，缺一不可：
 *   ① 用 worker 里真实存在的实例 `i`（GameGlobal）而不是全局 `window` 兜底；
 *   ② document 不存在时先补一个空对象，避免第二段循环遍历 undefined；
 *   ③ `parent/top` 改成挂在实例上（`i.window.parent = ...`），不依赖全局绑定。
 */
const MARKER = 'try{i.parent=i.top=i.window}catch(e){}'
const REPLACEMENT = 'i.window||(i.window=r),i.document||(i.document={});var c;'
  + 'for(c in r)try{Object.defineProperty(i.window,c,{value:r[c],configurable:!0})}catch(e){}'
  + 'for(c in r.document)try{Object.defineProperty(i.document,c,'
  + '{value:r.document[c],configurable:!0})}catch(e){}'
  // DevTools 下 GameGlobal 就是那个 Window，top/parent 常常是只读 getter：
  // 直接赋值会抛 "Cannot set property top of #<Window> which has only a getter"。
  // 与官方对 window 用 Object.defineProperty + descriptor 检查同一个思路：写不进去就跳过。
  + 'try{i.parent=i.top=i.window}catch(e){}'
  + 'try{i.window.parent=i.window.top=i.window}catch(e){}'
  + '}else{for(var d in r)i[d]=r[d];'
  + 'try{i.window=r,i.top=i.parent=i.window}catch(e){i.window=r}}'

/**
 * 旧版本补丁（已写进某些构建产物）没有把 parent/top 的赋值包起来，
 * DevTools 里会抛 "Cannot set property top"。这里按"旧串 → 新串"就地升级，
 * 幂等：升级后旧串不再存在。
 */
const LEGACY = 'i.parent=i.top=i.window,i.window.parent=i.window.top=i.window'
  + '}else{for(var d in r)i[d]=r[d];i.window=r,i.top=i.parent=i.window}'
const UPGRADED = 'try{i.parent=i.top=i.window}catch(e){}'
  + 'try{i.window.parent=i.window.top=i.window}catch(e){}'
  + '}else{for(var d in r)i[d]=r[d];'
  + 'try{i.window=r,i.top=i.parent=i.window}catch(e){i.window=r}}'

let changed = false
if (source.includes(MARKER)) {
  // 已打过补丁
} else if (source.includes(LEGACY)) {
  // 旧版补丁产出的中间态：就地升级（否则它会带 top 只读崩溃）
  source = source.replace(LEGACY, UPGRADED)
  changed = true
} else {
  // 按索引定位整块 if/else：不依赖精确引号或空白，避免"构建产物换一种压缩就匹配不上"
  const anchor = source.indexOf('"devtools"===o')
  if (anchor < 0) {
    throw new Error('未找到 Cocos 3.8.7 web-adapter 的 devtools 分支锚点："devtools"===o')
  }
  // 回退到该分支的 if 起点
  const start = source.lastIndexOf('if(', anchor)
  if (start < 0) {
    throw new Error('未找到 devtools 分支的 if 起点')
  }
  // 从起点做花括号配平，找到 if/else 整块的结束位置
  let depth = 0
  let end = -1
  let sawElse = false
  for (let i = start; i < source.length; i++) {
    const ch = source[i]
    if (ch === '{') {
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0 && sawElse) {
        end = i + 1
        break
      }
      if (depth === 0 && !sawElse) {
        // if 块结束；看后面是否跟 else
        const rest = source.slice(i + 1, i + 6)
        if (!rest.startsWith('else')) {
          end = i + 1
          break
        }
      }
    } else if (depth === 0) {
      if (source.startsWith('else', i)) {
        sawElse = true
      }
    }
  }
  if (end < 0) {
    throw new Error('devtools 分支花括号不配平，无法定位结束位置')
  }
  const HEAD = 'if("undefined"==typeof __devtoolssubcontext&&"devtools"===o){'
  source = source.slice(0, start) + HEAD + REPLACEMENT + source.slice(end)
  changed = true
}

if (changed) {
  writeFileSync(target, source, 'utf8')
  console.log(`[patch-wechat-adapter] 已修正 DevTools 全局属性目标：${target}`)
} else {
  console.log(`[patch-wechat-adapter] 兼容补丁已存在：${target}`)
}
