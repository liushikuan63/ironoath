#!/usr/bin/env bash
# 道：注释里断言「服务端没有某个端点」时，那个端点不许真的存在。
#
# 为什么要有这一道：`/nation/relations` 曾被四处注释写成「服务端没有只读的关系端点」，
# 而它一直挂在 `NationController` 上、且是外交页唯一的数据源。这类过期注释不会让任何测试变红，
# 却把人和量具都带到反方向（探针覆盖面判据就曾被其中一句注释算成「这条路径有人驱动」，见 #2522）。
#
# 判据（四层，谓词失效判红不判绿）：
#   1. 扫描目录存在；
#   2. 扫到的候选文件数不低于下限（默认 200：目录写错会退化成 0 个文件，那时必须红而不是绿）；
#   3. 客户端绑定清单能解析出来且不低于下限（默认 100 条）；
#   4. **没有任何一条「服务端没有 `X`」的注释，其 X 出现在绑定清单里**；
#      命中则逐条打印 文件:行 与那句话。
#
# 走环境变量口，便于零污染自测：
#   COMMENT_CLAIMS_DIRS  扫描目录（空格分隔，默认 client/assets/scripts tools）
#   COMMENT_CLAIMS_MIN_FILES  候选文件数下限（默认 200）
#   COMMENT_CLAIMS_MIN_BOUND  绑定路径数下限（默认 100）
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v node >/dev/null 2>&1; then
  echo "[comment-endpoint-claims][FAIL] 找不到 node，无法解析绑定清单" >&2
  exit 1
fi

DIRS="${COMMENT_CLAIMS_DIRS:-client/assets/scripts tools}"
MIN_FILES="${COMMENT_CLAIMS_MIN_FILES:-200}"
MIN_BOUND="${COMMENT_CLAIMS_MIN_BOUND:-100}"

RESULT="$(node -e '
const path = require("path")
const fs = require("fs")
const { staleAbsenceClaims } = require("./scripts/lib/comment-endpoint-claims.js")
const { clientBoundPaths } = require("./scripts/lib/endpoint-paths.js")
const dirs = process.argv[1].split(" ").filter(Boolean)
const missing = dirs.filter(d => !fs.existsSync(d))
if (missing.length) { console.log("MISSING_DIRS=" + missing.join(",")); process.exit(3) }
const bound = new Set(clientBoundPaths())
const { hits, scanned } = staleAbsenceClaims(dirs, bound)
console.log("SCANNED=" + scanned)
console.log("BOUND=" + bound.size)
for (const h of hits) console.log("HIT=" + h.file + ":" + h.line + "\t" + h.path + "\t" + h.text)
' "$DIRS" || true)"

if grep -q '^MISSING_DIRS=' <<<"$RESULT"; then
  echo "[comment-endpoint-claims][FAIL] 扫描目录不存在：$(grep '^MISSING_DIRS=' <<<"$RESULT" | cut -d= -f2-)" >&2
  exit 1
fi

SCANNED="$(grep '^SCANNED=' <<<"$RESULT" | cut -d= -f2)"
BOUND="$(grep '^BOUND=' <<<"$RESULT" | cut -d= -f2)"

if [ "${SCANNED:-0}" -lt "$MIN_FILES" ]; then
  echo "[comment-endpoint-claims][FAIL] 只扫到 ${SCANNED} 个文件，低于下限 ${MIN_FILES}：谓词失效（目录是否写错？）" >&2
  exit 1
fi
if [ "${BOUND:-0}" -lt "$MIN_BOUND" ]; then
  echo "[comment-endpoint-claims][FAIL] 客户端绑定路径只解析出 ${BOUND} 条，低于下限 ${MIN_BOUND}：谓词失效" >&2
  exit 1
fi

HITS="$(grep -c '^HIT=' <<<"$RESULT" || true)"
if [ "${HITS:-0}" -ne 0 ]; then
  echo "[comment-endpoint-claims][FAIL] 有 ${HITS} 处注释断言某个端点不存在，但它就在客户端绑定清单里：" >&2
  grep '^HIT=' <<<"$RESULT" | sed 's/^HIT=/  - /' >&2
  echo "  （绑定清单里的路径服务端一定有；这些注释是过期事实，改成它现在的样子）" >&2
  exit 1
fi

echo "[comment-endpoint-claims] ✓ ${SCANNED} 个文件、${BOUND} 条绑定路径，没有任何注释断言「服务端没有」一个真的存在的端点"
