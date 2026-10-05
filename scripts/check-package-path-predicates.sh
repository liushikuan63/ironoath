#!/usr/bin/env bash
# 职责：写死在量具里的**每一条 Java 包路径谓词**都必须命中真实存在的文件/目录，
#       且**每一族谓词的命中数必须 > 0**（命中数为 0 说明抽取正则自己失效，那是假绿不是干净）。
#
# 为什么开这道门（2026-10-06）：本仓的门禁靠**字面量路径**去读 Java 源 —— 现跑口径：
#   ① 17 条 `…/src/{main,test}/java/com/…` 路径（check-config-consumers.js 的 CFG_DIR、
#      check-no-bot-privilege.sh 的 BOT_PKG、gen.sh 与 check-contract-sync.sh 的生成输出目录 …）
#   ② 2 条 `-Dexec.mainClass=`（gen.sh:15、check-contract-sync.sh:21）
#   ③ 24 份契约的 `"x-java-package"`（生成器按它决定 DTO 落在哪个包）
# 这些字面量一旦和源码目录脱节（移动类、模块改名、将来真去改 Java 包名），
# 门会去扫一个**不存在的目录** ⇒ 拿到 0 个文件 ⇒ 判据空转 ⇒ **退 0 假绿**。
# 假绿比编译红危险：编译红人人看得见，假绿会被写成「N 道门全过」的交接证据。
# ⇒ 这正是 AGENTS.md §四 第 2 条「判定写了没接上」这一族，本门把它做成机制。
#
# ⚠️ 「命中数 > 0」不是附带说明而是这道门的**主判据之一**：
#    扫描目录被移走、正则写错、排除条件过宽，都会让"全部存在"变成"一条都没查"。
#    本仓 harness 反复钉的就是这个形状（汇总全 0 ≠ 跑过了）。
#
# 可选输入口（**不设时逐字节等同改动前**，check-gates-can-fail.sh 靠它做零污染三读数）：
#   PKGPATH_SCAN_DIRS     空格分隔的扫描目录清单，默认 `scripts tools`
#   PKGPATH_CONTRACT_DIR  契约目录，默认 `contract`
#   PKGPATH_SERVER_DIR    Java 源码树根，默认 `server`（② ③ ④ 三条判据的落点都从它枚举）
set -uo pipefail
cd "$(dirname "$0")/.."

SCAN_DIRS="${PKGPATH_SCAN_DIRS:-scripts tools}"
CONTRACT_DIR="${PKGPATH_CONTRACT_DIR:-contract}"
SERVER_DIR="${PKGPATH_SERVER_DIR:-server}"

fail=0
err() { echo "[pkg-path][FAIL] $*" >&2; fail=$((fail + 1)); }

# 已知顶层目录：用于把 `process.argv[1] + "/server/…/java/com/…"` 这类拼接串归一成仓库相对路径。
TOP_LEVELS=(server client contract tools scripts docs)
normalize() {
  local p="${1#./}" head t
  p="${p#/}"
  while [ -n "$p" ]; do
    head="${p%%/*}"
    for t in "${TOP_LEVELS[@]}"; do
      [ "$head" = "$t" ] && { printf '%s' "$p"; return 0; }
    done
    case "$p" in */*) p="${p#*/}" ;; *) break ;; esac
  done
  return 1
}

# ── 前置：扫描目录与契约目录必须存在（fail-closed，不静默跳过） ──────────────
for d in $SCAN_DIRS "$CONTRACT_DIR" "$SERVER_DIR"; do
  if [ ! -d "$d" ]; then
    echo "[pkg-path][FAIL] 扫描目录不存在：$d（fail-closed，不静默跳过）" >&2
    exit 1
  fi
done

# Java 源码根清单（排除 target/ 产物）：② 与 ③ 与包根唯一性都要用它
ROOTS=()
while IFS= read -r r; do
  [ -n "$r" ] && ROOTS+=("$r")
done < <(find "$SERVER_DIR" -type d -path "*/src/*/java" -not -path "*/target/*" 2>/dev/null | sort)
if [ "${#ROOTS[@]}" -eq 0 ]; then
  echo "[pkg-path][FAIL] 一个 Java 源码根都没找到（server 布局变了？谓词失去落点即判红）" >&2
  exit 1
fi

# ── ① 包路径字面量：抽出 (路径, 出处) 配对，逐条查存在性 ────────────────────
PAIRS="$(mktemp)"; trap 'rm -f "$PAIRS"' EXIT
PATH_RE='[A-Za-z0-9_./-]*/java/(com|org|net)/[A-Za-z0-9_./-]+'
while IFS= read -r f; do
  [ -n "$f" ] || continue
  grep -IoE "$PATH_RE" "$f" | while IFS= read -r raw; do
    [ -n "$raw" ] && printf '%s\t%s\n' "$raw" "$f"
  done
done < <(grep -rlIE "$PATH_RE" $SCAN_DIRS 2>/dev/null | sort) > "$PAIRS"

UNRESOLVED=0
while IFS= read -r raw; do
  [ -n "$raw" ] || continue
  normalize "$raw" >/dev/null 2>&1 || UNRESOLVED=$((UNRESOLVED + 1))
done < <(cut -f1 "$PAIRS" | sort -u)
if [ "$UNRESOLVED" -gt 0 ]; then
  err "$UNRESOLVED 条包路径谓词无法归一成仓库相对路径（首段不属于 ${TOP_LEVELS[*]}）—— 无法判定它指哪，不静默放过："
  while IFS= read -r raw; do
    normalize "$raw" >/dev/null 2>&1 || {
      src="$(awk -F'\t' -v p="$raw" '$1==p{print $2; exit}' "$PAIRS")"
      echo "    $raw（出处 $src）" >&2
    }
  done < <(cut -f1 "$PAIRS" | sort -u)
fi

PATH_TOTAL=0
PATH_MISSING=0
while IFS= read -r raw; do
  [ -n "$raw" ] || continue
  rel="$(normalize "$raw")" || continue
  PATH_TOTAL=$((PATH_TOTAL + 1))
  if [ ! -e "$rel" ]; then
    PATH_MISSING=$((PATH_MISSING + 1))
    src="$(awk -F'\t' -v p="$raw" '$1==p{print $2; exit}' "$PAIRS")"
    err "谓词指向不存在的路径：$rel（出处 $src）⇒ 那道门会扫空目录然后退 0（假绿）"
    continue
  fi
  if [ -d "$rel" ] && [ -z "$(ls -A "$rel" 2>/dev/null)" ]; then
    PATH_MISSING=$((PATH_MISSING + 1))
    err "谓词指向**空目录**：$rel ⇒ 门拿到 0 个文件，判据空转"
  fi
done < <(cut -f1 "$PAIRS" | sort -u)
if [ "$PATH_TOTAL" -eq 0 ]; then
  err "在 [$SCAN_DIRS] 里一条包路径谓词都没抽到 —— 谓词失效时判红而不是判绿"
fi

# ── ② exec.mainClass：必须落到某个源码根下的真实 .java ──────────────────────
MAIN_TOTAL=0
while IFS= read -r fqcn; do
  [ -n "$fqcn" ] || continue
  MAIN_TOTAL=$((MAIN_TOTAL + 1))
  rel="${fqcn//./\/}.java"
  hit=""
  for r in "${ROOTS[@]}"; do
    [ -f "$r/$rel" ] && { hit="$r/$rel"; break; }
  done
  if [ -z "$hit" ]; then
    err "-Dexec.mainClass=$fqcn 在 ${#ROOTS[@]} 个 Java 源码根下都找不到 $rel ⇒ 生成/构建脚本会指向不存在的类"
  fi
done < <(grep -rhoIE 'exec\.mainClass=[A-Za-z0-9_.]+' $SCAN_DIRS 2>/dev/null |
         sed 's/^exec\.mainClass=//' | sort -u)
if [ "$MAIN_TOTAL" -eq 0 ]; then
  err "在 [$SCAN_DIRS] 里一条 exec.mainClass 谓词都没抽到 —— 谓词失效时判红而不是判绿"
fi

# ── ③ 契约 x-java-package：声明的包必须在树里有对应目录且非空 ───────────────
XPKG_TOTAL=0
while IFS= read -r pkg; do
  [ -n "$pkg" ] || continue
  XPKG_TOTAL=$((XPKG_TOTAL + 1))
  rel="${pkg//./\/}"
  hit=""
  for r in "${ROOTS[@]}"; do
    [ -d "$r/$rel" ] && { hit="$r/$rel"; break; }
  done
  if [ -z "$hit" ]; then
    err "契约声明的 x-java-package=$pkg 在树里没有对应目录（$rel）⇒ 生成物的包与路径脱节"
  elif [ -z "$(ls -A "$hit" 2>/dev/null)" ]; then
    err "契约声明的 x-java-package=$pkg 对应目录是空的：$hit ⇒ 生成步骤没跑或输出目录改名了"
  fi
done < <(grep -rhoE '"x-java-package"[[:space:]]*:[[:space:]]*"[^"]+"' "$CONTRACT_DIR" 2>/dev/null |
         sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/' | sort -u)
if [ "$XPKG_TOTAL" -eq 0 ]; then
  err "在 $CONTRACT_DIR 里一个 x-java-package 都没读到 —— 契约形状变了，判红而不是判绿"
fi

# ── ④ Java 包根唯一：半改状态（旧包与新包并存）必须当场红 ───────────────────
PKGROOTS="$(for r in "${ROOTS[@]}"; do
  find "$r" -mindepth 2 -maxdepth 2 -type d 2>/dev/null
done | sed -E 's|^.*/src/[^/]+/java/||' | sort -u)"
PKGROOT_N="$(printf '%s' "$PKGROOTS" | grep -c . || true)"
if [ "$PKGROOT_N" -ne 1 ]; then
  err "Java 源码根下的包根有 $PKGROOT_N 个（应当只有 1 个）：$(printf '%s' "$PKGROOTS" | tr '\n' ' ')⇒ 改名只做了一半，两侧门会各扫各的"
fi

if [ "$fail" -gt 0 ]; then
  echo "[pkg-path] 不合格 $fail 条（谓词命中数：路径 $PATH_TOTAL / mainClass $MAIN_TOTAL / x-java-package $XPKG_TOTAL / 源码根 ${#ROOTS[@]}）" >&2
  exit 1
fi

echo "[pkg-path] 包路径谓词全部命中：$PATH_TOTAL 条路径 · $MAIN_TOTAL 条 exec.mainClass · $XPKG_TOTAL 个 x-java-package · Java 包根唯一 $(printf '%s' "$PKGROOTS" | tr -d '\n')（源码根 ${#ROOTS[@]} 个，扫描 [$SCAN_DIRS]）"
