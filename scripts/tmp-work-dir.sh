#!/usr/bin/env bash
# 职责：给门禁脚本一个「可写、每次独有」的临时工作目录。
# 依赖：无（被 source）。
#
# <p><b>为什么不能用 mktemp</b>（这一条是本文件存在的唯一理由）：
# 本机 Git Bash 的 <code>/tmp</code> **不可写** ——
# <code>mktemp</code> 报 "Permission denied" 却**退出 0**（实测），
# 于是 <code>PAIRS="$(mktemp)"</code> 拿到的是**空串**，
# 后面所有读写都落在空路径上，症状是
# <code>cut: '': No such file or directory</code> 这种看不懂的报错，
# 而不是「临时文件建不出来」这句实话。
# 三个门（<code>pkg-path</code> / <code>core-wiring</code> / <code>client-orphans</code>）
# 长期红就是这个原因；而 <code>check-art-quantized</code> 更糟 ——
# 它**带着坏掉的临时文件照样绿**（自证用的样本文件压根没写出来）。
# 另有一条：Git Bash 的 <code>/tmp</code> 交给 Windows 侧的 JVM 会被解析到别处
# （<code>check-contract-sync.sh</code> 的注释记着那一笔假红）。
#
# <p><b>所以</b>：**仓库内相对路径 + 进程号唯一**（与 <code>check-contract-sync.sh</code> 同一形状），
# 退出时整个目录删掉。
#
# <p><b>用法</b>：
# <pre>
#   source scripts/tmp-work-dir.sh pkg-path
#   PAIRS="$TMP_DIR/pairs"     # 路径（不必先建，脚本里 &gt; 重定向会建）
# </pre>
# 本文件自己装 EXIT trap（删整个目录）⇒ **source 它的脚本不要再装自己的 trap**
# （后装的会顶掉先装的 ⇒ 目录残留）。
set -euo pipefail

# 自检：`bash scripts/tmp-work-dir.sh --self-test`
# 证明三件事：目录**建得出来且可写**、**每次独有**（并发跑不会互删）、**退出时删得掉**。
# 为什么要有它：本文件的全部价值就是「让那几个门拿到一个可写的临时文件」，
# 而那个坑的症状很隐蔽（mktemp 退出 0 却给空串 ⇒ 门在空路径上跑 ⇒ 报错看不懂，
# 或者更糟：门**照样绿**）。⇒ 这三件事要能自己证明。
if [ "${1:-}" = "--self-test" ]; then
  _st_prefix="selftest"
  _st_dir=".tmp-${_st_prefix}.$$"
  # 自检自己也要「失败也不留垃圾」⇒ 用 trap 兜住**每一条**退出路径
  # （第一条版本把清理放在最后，于是「断言失败 ⇒ exit 1」那条路留了半个目录）。
  trap 'rm -rf ".tmp-${_st_prefix}.$$" ".tmp-${_st_prefix}-b.$$" ".tmp-${_st_prefix}-sub.$$"' EXIT
  mkdir -p "$_st_dir"
  # ① 可写
  # 写进内容再验大小：`: >` 只建空文件，验「非空」要真的写进去
  printf 'probe' > "$_st_dir/probe"
  [ -s "$_st_dir/probe" ] || { echo "[tmp-work-dir][FAIL] 建出来的文件写不进去" >&2; exit 1; }
  # ② 每次独有：换个前缀再来一次，两边都必须在
  _st_dir2=".tmp-${_st_prefix}-b.$$"
  mkdir -p "$_st_dir2"
  [ -d "$_st_dir" ] && [ -d "$_st_dir2" ] || {
    echo "[tmp-work-dir][FAIL] 两个进程各自的目录没同时在" >&2; exit 1; }
  # ③ 退出时删得掉（用子 shell 试：子 shell 退出即触发它自己的 trap）
  ( _st_sub=".tmp-${_st_prefix}-sub.$$"
    mkdir -p "$_st_sub"
    trap 'rm -rf "$_st_sub"' EXIT
    [ -d "$_st_sub" ] || exit 1 )
  [ -d ".tmp-${_st_prefix}-sub.$$" ] && {
    echo "[tmp-work-dir][FAIL] 子 shell 退出后目录还在" >&2; exit 1; }
  rm -rf "$_st_dir" "$_st_dir2"
  echo "[tmp-work-dir] 自检通过：目录可写、每次独有、退出即删。"
  exit 0
fi

_tmp_prefix="${1:-gate}"
TMP_DIR=".tmp-${_tmp_prefix}.$$"
mkdir -p "$TMP_DIR"

# 清理：只在**主 shell** 里删。
# ⚠️ 为什么要判 BASH_SUBSHELL：命令替换（$(...)）跑在子 shell 里，
# 某些 bash 会在子 shell 退出时也跑 EXIT trap ⇒ 那样
# `PAIRS="$TMP_DIR/pairs"` 之后紧跟的第一次命令替换就把目录删了。
# 本机实测（Git Bash 的 bash）子 shell **不**触发 EXIT trap，
# 但这里仍然判一道：判错的代价是「目录被提前删掉」，判对的代价为零。
tmp_cleanup() {
  [ "${BASH_SUBSHELL:-0}" != "0" ] && return 0
  [ -n "${TMP_DIR:-}" ] && rm -rf "$TMP_DIR"
  return 0
}
trap tmp_cleanup EXIT
