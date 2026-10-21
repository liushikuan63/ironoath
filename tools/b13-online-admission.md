# B13-9 离线准入输入检查

`b13-online-admission.mjs` 只检查输入文件，不连接后端，也不启动 5000 在线负载。它输出 `INPUT_BINDINGS_ONLY_NO_RUNTIME_AUTHORITY`，表示文件形状、哈希、容量字段与当前 checkout SHA 一致；holder 是否仍有效、资源是否由本任务独占、UTC 窗口是否已获协调者确认，仍须独立读回。这个脚本的绿色结果不是性能准入或压测报告。

运行环境：Node 20 或更高；账号清单、准入回执、资源证明均放仓库外，不能提交。命令行从本脚本所在 Git checkout 读取 HEAD，并拒绝有跟踪文件改动的 checkout。示例（仅作输入形状说明，实际 5000 条玩家要有各自的身份、票据与合法行军前置）：

说明性片段如下；实际 `manifest.json` 须是合法 JSON 且恰好 5000 条：

```json
{"schemaVersion":1,"targetOrigin":"http://127.0.0.1:8299","players":[{"playerId":"fixture-player-1","token":"fixture-only","march":{"toX":10,"toY":20,"action":"SCOUT","units":[{"unitId":"unit_infantry_t1","count":1}]}}]}
```

`resource-proof.json` 至少提供 `schemaVersion: 1`、与回执相同的 `holderId`、与清单相同的 `targetOrigin`、`isolated: true`、`maxConnections >= 5000`、`maxMarchQps >= 200` 和非空的 `cleanupPlan`。`admission.json` 至少提供 `schemaVersion: 1`、`holderId`、`runId`、`targetKind: "isolated"`、`targetOrigin`、`manifestSha256`、`resourceProofSha256`、当前 Git `sourceSha`、`maxConnections >= 5000`、`maxMarchQps >= 200`、`notBeforeUtc` 和 `notAfterUtc`（UTC `Z` 格式）。文件哈希是原始字节 SHA256；不要把账号票据复制进回执、资源证明、终端输出或仓库日志。

```powershell
node tools/b13-online-admission.mjs --mode=inspect --manifest=D:\isolated\manifest.json --admission=D:\isolated\admission.json --proof=D:\isolated\resource-proof.json
node --test tools/b13-online-admission.test.mjs
```

退出码 0 只说明输入绑定通过；2 表示前置未满足。正式 B13-9 仍须独立并发驱动在隔离环境保持 5000 条已握手且已 `bind` 的 `/ws` 连接，向真实 `POST /world/march` 提供 200 QPS，并报告 HTTP/业务成功率、每秒实际发起数、延迟分位数、断连数、资源占用与独立清理读数。目前此驱动与运行证据尚未完成；B11-6 的 5000 Bot 单轮夹具不能代替。
