#!/usr/bin/env bash
# 职责：CI 静态检查 —— **每一个"文本 blob"都必须在 `.gitattributes` 里被显式强制 `eol=lf`**。
#
# 为什么要这道门（2026-09-21 现跑）：`.gitattributes` 头部写的政策是"仓库内一律 LF 入库，检出也强制 LF"，
# 但它是一张**按扩展名点名的清单**（json/ts/java/sh/md/meta/mjs）。清单外的一律跟着
# `core.autocrlf` 走 —— 本机 autocrlf=true ⇒ **同一份文件在"我的机器"和"全新检出"上是两副面孔**。
# 立这道门时实测漏了 **767 个文本文件**：`scripts/*.js`（17 个门脚本自己）、`*.py`（图标可读性那两门的实现）、
# `*.yml`（含 `.github/workflows/ci.yml`）、`pom.xml`、`Makefile`、`.gitignore`、连 `.gitattributes` 自己都在里面。
#
# 这不是洁癖：2026-09-19 那批缺陷就是这条形状 —— 配置表夹具按跨行字面量锚点改内容，
# 主工作区（LF）永远复现不了，全新检出（CRLF）一个字符都匹配不上，症状看着像业务坏了。
# 当时补的是"漏掉的那几个扩展名"，**没补"还会再漏"这件事**，所以今天又漏了 767 个。
#
# 判什么：`git ls-files --eol` 里 index 侧是文本（`i/lf` / `i/mixed`）而 attr 侧没有 `eol=lf` 的，一律红。
#   `i/-text`（二进制）与 `i/none`（没有换行可错）放过。
# 一条都不许多：`i/mixed`（同一份文件里 LF 与 CRLF 混着入库）也直接算红 —— 那种文件两侧都不对。
#
# <p><b>2026-09-23 补的第二维：光查"属性有没有覆盖"查不出"blob 里还存着 CRLF"</b>。`tools/verify-hero-empty-runtime.mjs`
# 是这道门立起来之前就入库的，索引侧那份 blob 有 165 个 CRLF，而 `git ls-files --eol` 把它报成 **`i/-text`**
# （字节里没有 NUL，实测 9046 字节 0 个 NUL）—— 旧判据第一句 `idx != "i/lf" && idx != "i/mixed"` 就 next，
# **`i/-text` 恰好被跳过**，于是它带着 CRLF 在绿色门后面一直活着。结论：**这一维不能问 `ls-files --eol`，要直接读 blob 字节**。
# 源码族按扩展名点名（漏一份就是漏一年），但源码族里有 CRLF 本身就是缺陷 —— 与上面"属性覆盖"那条不重复。
set -euo pipefail
cd "$(dirname "$0")/.."

# 第二维：源码族 blob 里不许出现 CR 字节（`-I` 让 git 自己跳过二进制，vendored 的 .txt/.ai 因此不会被牵连）
# ⚠ `git grep` 零命中时退 1，而本脚本开着 `set -e` + `pipefail` —— 直接写 `$(git grep … | wc -l)` 会让
# "干净"变成"整条脚本静默退出 1、一个字都没打印"（2026-09-23 实测到：RED 正常，绿跑只留一个空文件 + 退 1）。
# 所以退出码要单独接住：>1 才是量具没跑成，那种情况**不许**报通过。
CR=$(printf '\r')
hits=$(git grep --cached -Il "$CR" -- '*.mjs' '*.cjs' '*.js' '*.ts' '*.sh' '*.py' '*.yml' '*.json' '*.md') && rc=0 || rc=$?
if [ "$rc" -gt 1 ]; then
  echo "[check-eol-policy] git grep 退 $rc —— 这不是零命中，是量具没跑成（不在 git 工作区？路径写错？）。"
  echo "  这**不是通过**，不许拿这条红字交差。"
  exit 1
fi
stored=$(printf '%s\n' "$hits" | grep -c . || true)
if [ "$stored" -gt 0 ]; then
  echo "[check-eol-policy] $stored 个源码 blob 入库时带 CRLF（前 15 个）："
  # 复用上面那次命中的结果，不再跑第二遍：`git grep … | head` 在命中很多时会因 SIGPIPE 退 141，
  # 在 `set -e` 下等于门自己崩掉（报错信息反而丢了）
  printf '%s\n' "$hits" | head -15 | sed 's/^/  /'
  echo '  修法：把文件整份写成 LF 再 git add <那一个路径>；'
  echo '  ⚠ git add --renormalize 对这种 blob 不动（它按属性判定，而 git 把这份 blob 报成 -text），'
  echo "  改完用 git show :<路径> | tr -cd '\\\\r' | wc -c 复验是 0。"
  exit 1
fi

git ls-files --eol | awk -v have_git="${1:-yes}" '
BEGIN { FS = "\t"; bad = 0; seen = 0 }
{
  path = $2
  if (path == "") next
  n = split($1, f, /[ \t]+/)
  idx = f[1]
  attr = ""
  for (i = 3; i <= n; i++) attr = attr " " f[i]
  if (idx == "i/-text" || idx == "i/none") next
  if (idx != "i/lf" && idx != "i/mixed") next
  if (attr ~ /-text/) next
  seen++
  if (attr ~ /eol=lf/) next
  bad++
  if (bad <= 15) printf "  %s | %s |%s\n", idx, path, attr
}
END {
  # 一条都没读到就报"通过"，是这道门最坏的失败方式（2026-09-21 在 Linux 容器里实测到：
  # 没有 .git 时 `git ls-files` 直接 fatal，awk 收到空输入 ⇒ 打出绿字，只靠 pipefail 才没溜过去）。
  if (seen == 0) {
    print "[check-eol-policy] 一个文本 blob 都没读到 —— 多半是不在 git 工作区里跑（`git ls-files` 空或 fatal）。"
    print "  这**不是通过**，不许拿这条绿字交差。"
    exit 1
  }
  if (bad > 0) {
    printf "[check-eol-policy] %d 个文本文件里 %s 个没被强制 eol=lf（上面列前 15 个）\n", seen, bad
    print "  修法：本仓库的口径是 `* text=auto eol=lf` + 给真正的资产显式 -text，"
    print "  而不是继续按扩展名点名 —— 点名这张表今天已经漏了 767 个，下一门新语言又会漏。"
    exit 1
  }
  printf "[check-eol-policy] %d 个文本 blob 全部被强制 eol=lf，且入库换行符全为 LF（全新检出与本机同源）。\n", seen
}'
