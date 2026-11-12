// 职责：解析「收口清单 #N」交叉引用，并核对它们是否真的落在 收口清单.md 里。
//
// 为什么值得做成门：开发包/验收矩阵/上线清单里到处写「修收口清单 #767」这类引用。
// 台账号称只增不删，所以这类引用一旦指向一个不存在的编号，就说明**引用写错了**或
// **那一条根本没登记**——两种都是下一轮会把同一件事重新发现一遍的根因。
//
// 实测（2026-11-13）：第一版判据只认表格行 `| N |`，于是把 7 条实际以编号段
// （`- **#2207 …**`）登记的条目误判成"悬空"——所以本库必须同时认两种形状。
// 台账现有形状：表格行 784 + 编号段 899。
//
// 被删改的历史引用用 `~~…~~` 标出来（就地补注的写法），这类引用**不算活引用**：
// 它记录的是"曾经写错过"，不是当前主张。

'use strict'

/** 台账里的编号：表格行 `| 2304 | …` 与编号段 `- **#2207 …**` 两种形状。 */
function ledgerIds(text) {
  const ids = new Set()
  for (const line of text.split('\n')) {
    const row = /^\|\s*(\d{1,4})\s*\|/.exec(line)
    if (row !== null) ids.add(Number(row[1]))
    const bullet = /^-\s*\*\*#(\d{1,4})(?!\d)/.exec(line)
    if (bullet !== null) ids.add(Number(bullet[1]))
  }
  return ids
}

/** 一份文档里「收口清单 …#N」的活引用。
 *
 * 更正痕迹只剥掉 `~~#N~~` 这一小段：早先按 `~~[^~]*~~` 整段剥离，会把跨行的划掉内容
 * 一起吃掉、把相隔很远的「收口清单」和「#767」拼成一条**根本不存在的引用**（2026-11-13 实测
 * 就因此多报了一条）。只剥编号本身，既不拼接相邻文本，也仍能让「~~#767~~ **#759**」只算 759。
 */
function ledgerRefs(text) {
  const live = text.replace(/~~#\d{1,4}~~/g, '')
  const refs = []
  const re = /收口清单[^\n|]{0,12}#(\d{3,4})(?!\d)/g
  for (const line of live.split('\n')) {
    let hit = re.exec(line)
    while (hit !== null) {
      refs.push(Number(hit[1]))
      hit = re.exec(line)
    }
    re.lastIndex = 0
  }
  return refs
}

/** 台账形状的两个现数，用于报"现跑值"与反空转。 */
function ledgerShape(text) {
  let rows = 0
  let bullets = 0
  for (const line of text.split('\n')) {
    if (/^\|\s*\d{1,4}\s*\|/.test(line)) rows += 1
    if (/^-\s*\*\*#\d{1,4}(?!\d)/.test(line)) bullets += 1
  }
  return { rows, bullets }
}

module.exports = { ledgerIds, ledgerRefs, ledgerShape }
