#!/usr/bin/env bash
# 职责：检查 Mongo 存档的 `save` 是否覆盖了它那份 Document 的每个字段。
# 依赖：node。
#
# 为什么值得做成 CI 一项而不是靠人记住：
# Mongo 实现的 `save` 是**字段白名单**（`Update().set("a", ...)` 一行一个字段），
# 而内存实现直接存对象。于是「新加字段却忘了补 $set」这件事：
# **没有一条用例会红、dev 环境一切正常**，只有在真 Mongo 上静默丢档 ——
# 症状是"开关打开了下次读回来是关的""限购账本每次重读都归零"这类**像坏了一样**的表现。
# B25-S2 的自动续训策略与 B19-S3 的礼包限购账本各丢过一次，这一门就是那两次的产物。
set -euo pipefail
cd "$(dirname "$0")/.."

node scripts/check-mongo-set-coverage.js
