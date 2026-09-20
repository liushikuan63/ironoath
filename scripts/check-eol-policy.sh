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
set -euo pipefail
cd "$(dirname "$0")/.."

git ls-files --eol | awk '
BEGIN { FS = "\t"; bad = 0 }
{
  path = $2
  if (path == "") next
  n = split($1, f, /[ \t]+/)
  idx = f[1]
  attr = ""
  for (i = 3; i <= n; i++) attr = attr " " f[i]
  if (idx != "i/lf" && idx != "i/mixed") next
  if (attr ~ /-text/) next
  if (attr ~ /eol=lf/) next
  bad++
  if (bad <= 15) printf "  %s | %s |%s\n", idx, path, attr
}
END {
  if (bad > 0) {
    printf "[check-eol-policy] %s 个文本文件没被强制 eol=lf（上面列前 15 个）\n", bad
    print "  修法：在 .gitattributes 里补规则；本仓库的口径是 `* text=auto eol=lf` + 给真正的资产显式 -text，"
    print "  而不是继续按扩展名点名 —— 点名这张表今天已经漏了 767 个，下一门新语言又会漏。"
    exit 1
  }
  print "[check-eol-policy] 每个文本 blob 都被强制 eol=lf（全新检出与本机同源）。"
}'
