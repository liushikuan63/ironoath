// 职责：见同名 check-client-iter-spread.sh —— 客户端源码里不得对**迭代器**做 spread。
//
// 为什么这条必须静态兜住：Cocos 的构建（SWC loose 假设）把 `[...map.values()]` 编成
// `[].concat(map.values())` —— concat 不展开迭代器，运行时拿到的是"装着迭代器的一元数组"，
// 于是后续的 sort/map 全对着迭代器做，症状是**整个面板画不出来**。
// 而 `test-client.sh` 走 tsc（真展开），所以这类缺陷在 node:test 里永远全绿，
// 只在浏览器/真机上炸出来。同族先例见 `client/assets/scripts/game/store/Store.ts`
// （Set 的同一个坑，在那里改成了 Array.from）。
'use strict'

const fs = require('fs')
const path = require('path')

const ROOT = 'client/assets/scripts'

// 只认"表达式里明确是迭代器"的四种写法：数组的 spread 没有问题（concat 对数组是真展开），
// 变量是不是 Map/Set 无法在语法层判断 —— 这条检查覆盖的是最常见的形状，不是全部。
const RULES = [
  { label: '迭代器 spread（.values()）', re: /\[\.\.\.[^\]\n]*\.values\(\)/ },
  { label: '迭代器 spread（.keys()）', re: /\[\.\.\.[^\]\n]*\.keys\(\)/ },
  { label: '迭代器 spread（.entries()）', re: /\[\.\.\.[^\]\n]*\.entries\(\)/ },
  { label: '迭代器 spread（new Set / new Map）', re: /\[\.\.\.\s*new (Set|Map)\s*\(/ },
]

function collectFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      collectFiles(full, out)
    } else if (entry.name.endsWith('.ts')) {
      out.push(full)
    }
  }
}

const files = []
if (fs.existsSync(ROOT)) {
  collectFiles(ROOT, files)
}
if (files.length === 0) {
  // 反空转：一个文件都没扫到（路径改了/目录没了）时按失败处理，而不是"没找到就没问题"
  console.error(`[check-client-iter-spread][FAIL] ${ROOT} 下一个 .ts 都没扫到：路径过期了`)
  process.exit(1)
}

const hits = []
for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  lines.forEach((line, index) => {
    if (line.trim().startsWith('//')) {
      return
    }
    for (const rule of RULES) {
      if (rule.re.test(line)) {
        hits.push(`${file}:${index + 1}  ${rule.label}  ${line.trim().slice(0, 90)}`)
      }
    }
  })
}

if (hits.length > 0) {
  console.error('[check-client-iter-spread][FAIL] 客户端源码里对迭代器做了 spread：')
  for (const hit of hits) {
    console.error('  ' + hit)
  }
  console.error('')
  console.error('  替换写法：Array.from(map.values()) / Array.from(map.entries()) / Array.from(set)')
  console.error('  原因：Cocos 的转译把 iterable 的 spread 编成 [].concat(x)（不展开），')
  console.error('        而 node:test 走 tsc 会真展开 —— 也就是说这条缺陷在单测里永远全绿。')
  process.exit(1)
}
console.log(`[check-client-iter-spread] ${files.length} 个客户端源文件里没有对迭代器的 spread。`)
