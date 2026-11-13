// 职责：找出「注释断言某个只读端点不存在」的地方，并判断那个端点其实存不存在。
// 依赖：node 的 fs/path，无第三方库。
//
// 为什么要这件东西：`/nation/relations` 曾经被四处注释写成「服务端没有只读的关系端点」，
// 而它从某次提交起就挂在 `NationController` 上、还是外交页唯一的数据源。这类注释不会让任何测试变红，
// 却会把下一个读代码的人（以及下一份量具）带到反方向 —— 探针覆盖面判据就曾被其中一句注释
// 误判成「这条路径有人驱动」（见收口清单 #2522）。
//
// 口径：只在**注释行**上判定，且只认「服务端没有 / 未提供 / 不提供」这类断言句里的反引号路径；
// 命中之后再问「这条路径在客户端绑定清单里吗」—— 在，就说明服务端确实有它，断言是过期事实。
// 反向（断言不存在的路径确实不存在）不报，那是正常叙述。
const fs = require('fs')
const path = require('path')

/** 断言「服务端没有」的说法。列几种同义写法，避免只认一个词。 */
const ABSENCE = /服务端(?:没有|没提供|未提供|不提供|并无)/

/** 从一行里抠出所有反引号里的路径（`/a/b` 这种）。 */
function backtickedPaths (line) {
  const out = []
  const re = /`(\/[A-Za-z0-9_\-/]+)`/g
  let m
  while ((m = re.exec(line)) !== null) out.push(m[1])
  return out
}

function walkFiles (dir, out = []) {
  let entries
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch (e) {
    return out
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'build' || entry.name === 'library') continue
      walkFiles(full, out)
    } else if (/\.(ts|mjs|js)$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

/**
 * 扫若干目录，返回 [{ file, line, path, text }]：注释里断言不存在、但客户端绑定清单里存在的路径。
 *
 * @param {string[]} dirs 要扫的目录（相对仓库根或绝对路径都行）
 * @param {Set<string>} bound 客户端绑定的路径集合（服务端一定有它们）
 */
function staleAbsenceClaims (dirs, bound) {
  const hits = []
  let scanned = 0
  for (const dir of dirs) {
    for (const file of walkFiles(dir)) {
      scanned += 1
      const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
      for (let i = 0; i < lines.length; i += 1) {
        const line = lines[i]
        if (!ABSENCE.test(line)) continue
        // 只认注释行：该行里 `//` 或 `*` 出现在断言之前，或整行是块注释体
        const trimmed = line.trim()
        const isComment = trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
        if (!isComment) continue
        // 只认**断言之后**出现的路径：同一行前半句常提到别的端点（例如「`/nation/diplomacy` 的那张表
        // —— 服务端没有只读的关系端点」），把它算进来会变成假红。
        const at = line.search(ABSENCE)
        if (at < 0) continue
        for (const p of backtickedPaths(line.slice(at))) {
          if (bound.has(p)) {
            hits.push({ file, line: i + 1, path: p, text: trimmed })
          }
        }
      }
    }
  }
  return { hits, scanned }
}

module.exports = { staleAbsenceClaims, backtickedPaths, ABSENCE }
