#!/usr/bin/env bash
# 职责：CI 静态检查 —— 目标搜索的半径这条通道必须两端都还在，不许悄悄退回「有注入口、没人注入」。
# 症状族：TargetSearchView.setRadiusBounds 从落地起一直是**零调用点** ⇒ 半径恒 0 ⇒
#   服务端夹成 1 格 ⇒ 上下左右四个邻居 ⇒ 面板永远空着。而界面上一切看起来正常：
#   两颗 ± 键静静地在、表头还写着「搜索半径 0 格」，没有任何一处报错（台账 #353）。
# 判定：① 客户端非生成代码里至少有一处 `.setRadiusBounds(` 调用 —— 少一处就是这条通道又断了；
#   ② 契约的 SearchTargetsResp 里那三个数（radiusMin / radiusDefault / radiusMax）还在 ——
#     删掉它们等于把服务端唯一的下发口拆了，而客户端只会安静地回到「不知道半径」。
#   两处都要求命中数 > 0：**扫不到任何匹配就是红，不是通过**（与 check-eol-policy 同一条规矩，
#   否则脚本在非 git 目录、或 grep 拼错路径时会一路绿灯）。
# 依赖：grep（node 不需要，普通 Linux 也跑得动 —— 见台账 #340 那一族的两副面孔）。
set -euo pipefail
cd "$(dirname "$0")/.."

call_sites=$(grep -rn "\.setRadiusBounds(" client/assets/scripts --include=*.ts 2>/dev/null \
  | grep -v "/net/generated/" | wc -l || true)
schema_fields=$(grep -c "\"radiusMin\"\|\"radiusDefault\"\|\"radiusMax\"" \
  contract/proto/world.schema.json || true)

fail=0
if [ "$call_sites" -lt 1 ]; then
  echo "[check-search-radius] 红：客户端没有任何一处调用 setRadiusBounds —— 半径会退回恒 0，目标搜索永远空手（台账 #353）"
  fail=1
fi
if [ "$schema_fields" -lt 3 ]; then
  echo "[check-search-radius] 红：SearchTargetsResp 里的 radiusMin/radiusDefault/radiusMax 不齐（命中 $schema_fields，应为 3）—— 上下界的下发通道被拆了"
  fail=1
fi
if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "[check-search-radius] 半径上下界的两端都在：客户端有 $call_sites 处注入调用，契约带齐三个数。"
