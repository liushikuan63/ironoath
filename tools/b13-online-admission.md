# B13-9 离线准入输入检查

`b13-online-admission.mjs` 只检查输入文件，不连接后端，也不启动 5000 在线负载。它输出 `INPUT_BINDINGS_ONLY_NO_RUNTIME_AUTHORITY`，表示文件形状、哈希、容量字段与当前 checkout SHA 一致；holder 是否仍有效、资源是否由本任务独占、UTC 窗口是否已获协调者确认，仍须独立读回。这个脚本的绿色结果不是性能准入或压测报告。

运行环境：Node 20 或更高；账号清单、准入回执、资源证明均放仓库外，不能提交。命令行从本脚本所在 Git checkout 读取 HEAD，并拒绝有跟踪文件改动的 checkout。示例（仅作输入形状说明，实际 5000 条玩家要有各自的身份、票据与合法行军前置）：

说明性片段如下；实际 `manifest.json` 须是合法 JSON 且恰好 5000 条：

```json
{"schemaVersion":1,"targetOrigin":"http://127.0.0.1:8299","durationSeconds":1,"players":[{"playerId":"fixture-player-1","token":"fixture-only","march":{"toX":10,"toY":20,"action":"SCOUT","units":[{"unitId":"unit_infantry_t1","count":1}]}}]}
```

`resource-proof.json` 至少提供 `schemaVersion: 1`、与回执相同的 `holderId`、与清单相同的 `targetOrigin`、`isolated: true`、`maxConnections >= 5000`、`maxMarchQps >= 200` 和非空的 `cleanupPlan`。`admission.json` 至少提供 `schemaVersion: 1`、`holderId`、`runId`、`targetKind: "isolated"`、`targetOrigin`、`manifestSha256`、`resourceProofSha256`、当前 Git `sourceSha`、`maxConnections >= 5000`、`maxMarchQps >= 200`、`notBeforeUtc` 和 `notAfterUtc`（UTC `Z` 格式）。文件哈希是原始字节 SHA256；不要把账号票据复制进回执、资源证明、终端输出或仓库日志。

```powershell
node tools/b13-online-admission.mjs --mode=inspect --manifest=D:\isolated\manifest.json --admission=D:\isolated\admission.json --proof=D:\isolated\resource-proof.json
node --test tools/b13-online-admission.test.mjs
```

退出码 0 只说明输入绑定通过；2 表示前置未满足。`b13-online-ws.mjs` 是下一格的连接池模块：按批次连接 `/ws`、发送与握手同一身份的 `bind`，只有收到服务端同身份的 `bound` 才计为在线；任一批失败会关掉本轮已开的连接，主动清理不计入异常断连。它没有命令行运行入口，离线事件夹具通过 4/4；这些夹具不证明真实后端可保持 5000 条连接。

`b13-online-march.mjs` 是行军调度模块：每个时间槽最多发一条 `POST /world/march`，请求体由清单行军前置加唯一 requestId 组成。计划槽、实际发起、过载漏发、调度迟到、HTTP 状态、业务码、成功请求延迟分位数和整个负载期的已绑定连接下界分别记数；已错过的槽不追发。离线 200 槽、超时补发反证、连接断开、容量不足和 UTC 到期夹具通过 3/3。它也没有命令行入口，假 fetch 的 200/200 不能称为真实 200 QPS。

`run-b13-online-load.mjs` 已把三段组合为独立运行入口。它需要 **Node 24 原生 WebSocket**、干净的专用 checkout、仓库外的三份输入和已有的仓库外输出目录；根据 runId 排他占用报告文件。只有 holder、资源归属和窗口经协调者**独立读回**后，执行者才可给它 `--mode=run`：

```powershell
node tools/run-b13-online-load.mjs --mode=run --manifest=D:\isolated\manifest.json --admission=D:\isolated\admission.json --proof=D:\isolated\resource-proof.json --output=D:\isolated\reports
```

报告包含原始样本、HTTP/业务码、每秒发起数、延迟 P50/P95/P99、最少已绑定连接数、异常断连和主动关闭数；不写入票据。只有 5000 条连接全程维持、每秒实际 200 次行军、业务成功及 WebSocket 清理均满足时，工具才给 `CANDIDATE_MEASURED_NOT_ACCEPTED`；它**仍不是 B13-9 验收通过**，因为还要独立核服务器资源、真实账号/战事前置、数据隔离与清理、压测报告及产品规定的观察时长。输入或负载不满足时，退出非零并保留失败报告。当前只有 Node 20 假 WS/假 fetch 的离线集成夹具和 Node 24 本机临时服务的 2 连接协议冒烟；**没有**在真实后端建立 5000 连接或运行 200 QPS。B11-6 的 5000 Bot 单轮夹具不能代替。
