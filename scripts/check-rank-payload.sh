#!/usr/bin/env bash
# 职责：B23 验收 6 的判据 —— 榜分页的单响应**在最坏情况下**也不超过 PERF_PAYLOAD_MAX_BYTES。
# 用法：bash scripts/check-rank-payload.sh（check.sh 会调它）
#
# 为什么不是"跑一次接口量一量字节"：那样量到的永远是**当天那条榜的真实内容** ——
# 空榜几十字节，怎么量都是绿的；等榜上真有人了才发现超了，而那时改的是一张正在被看的榜。
# 所以这里量的是**上界**：按表里的每页条数 × 一条最坏行（长昵称、长 id、十位数值，
# UTF-8 下中文一字三字节）加信封，只要这个上界在预算内，任何真实响应都必然在预算内。
#
# 判据可失败之处：把 RANK_PAGE_SIZE_MAX 从 20 改成 40（或把 PERF_PAYLOAD_MAX_BYTES 调小）
# 这个脚本就退 1 并打出两个数 —— 它跟着表走，不是写死的数字。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

RESULT="$(node - "$ROOT_DIR" <<'NODE'
const fs = require('fs')
const root = process.argv[2]
const cfg = JSON.parse(fs.readFileSync(`${root}/contract/config/global.json`, 'utf8'))
const rowOf = id => (cfg.rows || []).find(r => r.id === id)
const cap = rowOf('RANK_PAGE_SIZE_MAX')
const budget = rowOf('PERF_PAYLOAD_MAX_BYTES')
if (!cap || !budget) {
  console.error('[check-rank-payload] global 表里缺 RANK_PAGE_SIZE_MAX 或 PERF_PAYLOAD_MAX_BYTES：')
  console.error('  先补表（或修本脚本的行结构），否则这条判据是在量空气')
  process.exit(2)
}
const pageSize = Number(cap.value)
const budgetBytes = Number(budget.value)

// 一条最坏行：昵称取表里 why 提到的 24 字（中文 ⇒ UTF-8 3 字节），
// id 取 32 字符（playerId / allianceId 的实际量级），数值取 10 位（十亿级战力），
// tag 取 5 字符（联盟缩写上限），再加 JSON 的键名开销
const worstRow = {
  rank: 9999,
  id: 'x'.repeat(32),
  name: '汉'.repeat(24),
  value: 9999999999,
  tag: 'TAGXX',
}
// 信封按契约里 RankListResp 的字段实打实拼一遍（不是估一个数），
// 再留 512 字节给 serverNow/traceId 这类外层包装
const envelope = {
  code: 0, msg: '成功', data: {
    type: 'ALLIANCE',
    entries: Array.from({ length: pageSize }, () => worstRow),
    myRank: 9999, myValue: 9999999999, page: 99, pageSize, hasMore: true,
  },
  serverNow: 1_789_000_000_000, traceId: 'x'.repeat(32),
}
const worst = Buffer.byteLength(JSON.stringify(envelope), 'utf8') + 512
const perRow = Math.round((worst - 512) / pageSize)
console.log(`[check-rank-payload] 每页 ${pageSize} 条 × 单行约 ${perRow}B + 信封 ⇒ 最坏响应 ${worst}B`
  + `（预算 ${budgetBytes}B，来源 global.${budget.id}）`)
if (worst > budgetBytes) {
  console.error(`[check-rank-payload] 超出预算 ${worst - budgetBytes}B：`
    + '要么把 RANK_PAGE_SIZE_MAX 调小，要么把 PERF_PAYLOAD_MAX_BYTES 调大（后者要先想清楚客户端扛不扛）')
  process.exit(1)
}
const headroom = Math.round((1 - worst / budgetBytes) * 100)
console.log(`[check-rank-payload] 最坏情况仍在预算内（余量 ${headroom}%）。`)
NODE
)"
STATUS=$?
echo "$RESULT"
if [ "$STATUS" = "2" ]; then
  echo "[check-rank-payload] 前置不满足：先修表或修脚本。"
  exit 1
fi
exit "$STATUS"
