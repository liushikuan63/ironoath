#!/usr/bin/env bash
# 职责：B14/B23 的禁止项「不要 @Scheduled」（B00 Java 五大技术陷阱第 2 条）—— 服务端不跑定时器，
#      快照/换日/清理一律走惰性路径（读或写的时候顺手做）。
#
# 为什么这条要机械化：本仓库所有"随时间推进"的东西（资源结算、建造完成、邮件过期、战报清理、
# 活动轮换、每日快照）都靠惰性驱动 —— 谁顺手加一个 @Scheduled，它不会报错、不会让任何用例变红，
# 只会让"结算时刻由请求决定"这条不变量静默失效（多实例下还会每个实例各跑一遍）。
#
# 判据：生产源码里不出现 @Scheduled / @EnableScheduling 注解。
# 注释里提到它们（大量文档都写着"禁止 @Scheduled"）不算违反：只扫不以 * 或 // 开头的行。
set -euo pipefail
cd "$(dirname "$0")/.."

HITS=$(grep -rn "@Scheduled\|@EnableScheduling" server --include="*.java" 2>/dev/null \
  | grep -v "/test/" \
  | while IFS= read -r line; do
      # 去掉行尾注释，再丢掉块注释正文行：两种情况都是在"提到"注解，不是在使用它
      code="${line%%//*}"
      trimmed="$(printf '%s' "${code#*:}" | sed 's/^[0-9]*://' | sed 's/^[[:space:]]*//')"
      case "$trimmed" in
        \**|/\**) continue ;;
      esac
      case "$code" in
        *@Scheduled*|*@EnableScheduling*) printf '%s\n' "$line" ;;
      esac
    done || true)

if [ -n "$HITS" ]; then
  echo "[check-no-scheduled][FAIL] 生产代码里出现了定时器注解："
  echo "$HITS"
  echo ""
  echo "  B00 陷阱 2 / B14 禁止项：服务端不跑 @Scheduled。要推进时间就挂在读或写的路径上"
  echo "  （先例：资源结算、建造完成、邮件过期、战报清理、活动轮换）。"
  exit 1
fi
echo "[check-no-scheduled] 生产代码里没有定时器注解（惰性驱动这条不变量成立）。"
