import { readFileSync, writeFileSync } from 'node:fs'

const [target] = process.argv.slice(2)
if (target === undefined) {
  console.error('用法：node scripts/patch-wechat-adapter.mjs <web-adapter.js>')
  process.exit(1)
}

let source = readFileSync(target, 'utf8')
const patches = [
  {
    from: 'if("undefined"==typeof __devtoolssubcontext&&"devtools"===o){for(var c in r){',
    to: 'if("undefined"==typeof __devtoolssubcontext&&"devtools"===o){'
      + 'i.document||(i.document={});for(var c in r){',
    marker: 'i.document||(i.document={});for(var c in r){',
  },
  {
    from: 'var l=Object.getOwnPropertyDescriptor(i,c);',
    to: 'var l=Object.getOwnPropertyDescriptor(window,c);',
    marker: 'var l=Object.getOwnPropertyDescriptor(window,c);',
  },
]

let changed = false
for (const patch of patches) {
  if (source.includes(patch.marker)) {
    continue
  }
  if (!source.includes(patch.from)) {
    throw new Error(`未找到 Cocos 3.8.7 web-adapter 兼容点：${patch.from}`)
  }
  source = source.replace(patch.from, patch.to)
  changed = true
}

if (changed) {
  writeFileSync(target, source, 'utf8')
  console.log(`[patch-wechat-adapter] 已修正 DevTools 全局属性目标：${target}`)
} else {
  console.log(`[patch-wechat-adapter] 兼容补丁已存在：${target}`)
}
