#!/usr/bin/env bash
# 第 58 道：服务端推的社交事件取值必须在契约 SocialEventType 枚举里。
#
# 为什么需要（#2531 实证，不是假想）：`SocialAppService#squadTransfer` 推
# SQUAD_LEADER_CHANGED，而枚举里没有它。字符串存得进库（Mongo 不查类型）、
# 既有测试也只断言「事件列表里有这个字符串」，所以整条链路一路绿；真正的爆炸
# 发生在**新队长下一次读 /social/summary** 时——Jackson 序列化未读事件抛
# `No enum constant …SocialEventType.SQUAD_LEADER_CHANGED`，整个摘要回 1001、
# data 为 null：小队看不到、红点看不到，而「把事件读掉」恰好也要经过那个面板。
#
# 判据四层（谓词失效一律判红，不判绿）：
#   ① 契约文件存在且能解出枚举，枚举取值数 ≥ SOCIAL_EVENT_MIN_ENUM
#   ② 服务端手写代码里数得到的 event("X") 字面量数 ≥ SOCIAL_EVENT_MIN_LITERALS
#   ③ 每个字面量都在枚举里
#   ④ 命中数不设上限：多扫到是好事，扫不到才危险

set -euo pipefail

cd "$(dirname "$0")/.."

SCHEMA="${SOCIAL_EVENT_SCHEMA:-contract/proto/social.schema.json}"
SRC="${SOCIAL_EVENT_SRC:-server}"
MIN_LITERALS="${SOCIAL_EVENT_MIN_LITERALS:-5}"
MIN_ENUM="${SOCIAL_EVENT_MIN_ENUM:-10}"

if ! command -v node >/dev/null 2>&1; then
  echo "[social-event-literals][FAIL] 需要 node 才能解析契约 JSON"
  exit 1
fi

if [ ! -f "$SCHEMA" ]; then
  echo "[social-event-literals][FAIL] 契约文件不存在：$SCHEMA"
  exit 1
fi

node - "$SCHEMA" "$SRC" "$MIN_LITERALS" "$MIN_ENUM" <<'NODE'
const fs = require('fs')
const { audit } = require('./scripts/lib/social-event-literals.js')

const schemaPath = process.argv[2]
const srcDir = process.argv[3]
const minLiterals = Number(process.argv[4])
const minEnum = Number(process.argv[5])

let result
try {
  result = audit({ schemaPath, dirs: [srcDir] })
} catch (error) {
  console.log('[social-event-literals][FAIL] 契约解析失败：' + error.message)
  process.exit(1)
}

const { allowed, literals, missing } = result

// ① 枚举本身要先可信
if (allowed.length < minEnum) {
  console.log(
    '[social-event-literals][FAIL] 枚举取值只数到 ' +
      allowed.length +
      ' 个，低于下限 ' +
      minEnum +
      '：判据失效（枚举被清空或键名改了），不能判绿',
  )
  process.exit(1)
}

// ② 字面量也要数得到，否则说明扫描谓词失效
if (literals.size < minLiterals) {
  console.log(
    '[social-event-literals][FAIL] 服务端只扫到 ' +
      literals.size +
      ' 个 event("…") 字面量，低于下限 ' +
      minLiterals +
      '：扫描谓词失效（目录或写法变了），不能判绿',
  )
  process.exit(1)
}

// ③ 每个字面量都必须是枚举成员
if (missing.length > 0) {
  console.log('[social-event-literals][FAIL] 服务端推了契约枚举里没有的事件取值：')
  for (const row of missing) {
    console.log('  - ' + row.name + '  ← ' + row.files.join(' '))
  }
  console.log('  修法：在 ' + schemaPath + ' 的 SocialEventType 枚举里补上，再跑 npm run gen。')
  process.exit(1)
}

console.log(
  '[social-event-literals] ✓ 服务端 ' +
    literals.size +
    ' 个 event("…") 取值全部在契约枚举内（枚举 ' +
    allowed.length +
    ' 个）',
)
NODE
