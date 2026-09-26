#!/usr/bin/env python3
"""扫"服务端有端点、客户端没接"的缺口 —— 暂停与取消这两个缺口就是这么被找出来的，现在改成机械扫描。

> **先读这一句再往下用**：本仓库**已经有一份权威台账** `客户端发送口缺口清单.md` 与配套报告器
> `tools/report-client-send-paths.mjs`，它管的是 (b) 类（"GameApi 有方法、玩家点不到"），
> 而且会分"只有测试在用"与"只在 GameApi 内部被调"两种情形 —— **比本脚本准**。
> 2026-09-22 我先写本脚本、后才发现那份文档，于是犯了同一件事记两遍的错（详见审计 §二十八的自纠）。
> **(b) 类以那份台账为准**；本脚本只在 (a) 类（服务端有端点、GameApi 连方法都没有）上补充。

职责：`/city/pause`、`/city/cancel` 两个功能的缺口是 2026-09-22 **碰巧**发现的（补 B03 §2 时顺手一查），
说明"功能漏接"这类缺陷靠人眼抽查是靠不住的。本脚本把它变成一条命令，扫两类：

  (a) **服务端有、`GameApi.ts` 里没有调用目标**的端点 —— 客户端根本发不出这个请求；
  (b) **`GameApi` 上有方法、别的文件一处都没调** —— 客户端能发，但界面上没有入口
      （暂停/取消当初就是这一类的形态：`cityCancel` 早就在，只是没人调）。

用法：
    python tools/sweep-client-endpoints.py            # 列出全部候选
    python tools/sweep-client-endpoints.py --only-b   # 只看 (b) 类（漏入口，最像真缺陷的那类）

退出码：0 扫描完成；1 **自检没过**（抽取逻辑写坏 —— 那种情况下"零命中"全是假象）；
        2 前置不满足（找不到控制器目录或客户端脚本目录）。

两条自检（都必须成立，否则结论不可信）：
  ① `GameApi` 的调用目标里必须能找到 `/city/list`；
  ② 控制器里必须抽得到 `/ops/*` 前缀的端点。
2026-09-22 这两条各救过一次：一次是 `findall` 的捕获组取错（路径集合只剩 `'/'`），
一次是把"生成协议注释里的路径串"当成了调用（154/155 命中的假象）。

**(a) 类有假阳性，读的时候要知道**：调用目标只认 `this.<verb><类型>('/path'` 这一种写法，
所以走别的形态的端点会被误报 —— 实测 `/player/init`、`/time/sync`、`/pay/*`、`/world/viewport`、
`/rank/me`、`/season/settle` 都在 False Positive 之列（客户端当然在用它们）。
**(b) 类不受这个影响**（它比的是"方法名有没有被别的文件用过"），而**暂停/取消正是 (b) 类的形态** ——
所以这条扫描真正的价值在 (b)。
"""
import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTROLLERS = ROOT / 'server/game-web/src/main/java/com/ironoath/web/controller'
CLIENT = ROOT / 'client/assets/scripts'
GAME_API = CLIENT / 'game/session/GameApi.ts'

MAPPING = re.compile(r'@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
# 只认 `this.<verb><泛型>('/path'` 这种**调用目标**形态（注释里的路径串不算）
CALL = re.compile(r"this\.(?:mutate|read|get|post|write)<[^>]*>\(\s*'([^']+)'")
# `/ops/...` 这类只有服务端的端点，扫出来不算问题（列出来只为对照）
SERVER_ONLY = re.compile(r'^/(ops|bot)/')


def endpoints():
    out = []
    for path in sorted(CONTROLLERS.glob('*.java')):
        text = io.open(path, encoding='utf-8').read()
        match = CLASS_MAPPING.search(text)
        prefix = match.group(1) if match else ''
        for verb, sub in MAPPING.findall(text):
            out.append((verb.upper(), prefix + sub, path.name))
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--only-b', action='store_true', help='只看"有方法、没入口"那一类')
    args = parser.parse_args()

    if not CONTROLLERS.is_dir() or not GAME_API.is_file():
        print('[sweep][前置] 找不到控制器目录或 GameApi.ts —— 在仓库根目录跑')
        return 2

    all_endpoints = endpoints()
    game_api_text = io.open(GAME_API, encoding='utf-8').read()
    called = set(CALL.findall(game_api_text))

    # 自检：抽取逻辑必须同时命中"确实接了的"与"确实只有服务端的"
    if '/city/list' not in called:
        print('[sweep][FAIL] 自检不过：GameApi 的调用目标里找不到 /city/list —— 正则或文件结构变了')
        return 1
    if not any(path.startswith('/ops') for _, path, _ in all_endpoints):
        print('[sweep][FAIL] 自检不过：抽不到 /ops 端点 —— 控制器解析写坏了')
        return 1

    gap_a = [(v, p, c) for v, p, c in all_endpoints if p not in called and not SERVER_ONLY.match(p)]
    print(f'[sweep] 端点 {len(all_endpoints)} 个；GameApi 有调用目标的 {len(called)} 个')
    if not args.only_b:
        print(f'\n[sweep] (a) 服务端有、GameApi 没有调用目标（{len(gap_a)} 个）：')
        for verb, path, controller in gap_a:
            print(f'   {verb:5s} {path:40s} {controller}')
        server_only = [p for _, p, _ in all_endpoints if SERVER_ONLY.match(p)]
        print(f'   （另有 {len(server_only)} 个 /ops、/bot 端点，本来就只给运维/内部用，不计入）')

    methods = re.findall(r'^\s{2}(\w+)\(', game_api_text, re.M)
    other_text = '\n'.join(io.open(p, encoding='utf-8').read()
                           for p in CLIENT.rglob('*.ts') if p != GAME_API)
    orphan = [name for name in methods if name != 'constructor' and f'.{name}(' not in other_text]
    print(f'\n[sweep] (b) GameApi 上有方法、别的文件一处都没调（{len(orphan)} 个，界面没入口）：')
    for name in orphan:
        print(f'   {name}')
    print('\n[sweep] 判读口径：/ops、/bot 是运维与内部口，本来就不该有客户端调用；'
          '其余候选要么是"本批次未接"（服务端先行），要么就是漏接 —— 逐条分诊，别一律当缺陷。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
