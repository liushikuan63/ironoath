# C线：生产GameItem-only与旧回调兼容

**目标**：落实已确认政策：生产拒绝legacy `/pay/callback`履约，仅开发/测试保留；production readiness按真实GameItem要求判断。
**当前状态**：C候选分支`codex/live-c-pay-20261108`提交`37c8c1f3`已合入主树提交`e6faa312`（未推送）。生产旧回调拒绝与开发/测试兼容的五文件实现及测试已在C worktree和集成主树两处完成定向运行；拒绝日志只记录固定政策原因，不记录请求订单号。prod策略选择由`PayBeansConfig`工厂和显式prod Environment覆盖，当前未做全prod应用启动。生产商户/商品、真实交易回调/退款和设备验收仍未做。
**规格**：[B19 #2473](../../B19_付费发货与礼包弹窗.md)、[开发包§零](../../待完善收口_VibeCoding开发包.md#current-execution)；当前渠道是`wx.requestMidasPaymentGameItem`，不另起支付集成。

## 写入边界

[ownership.json](./ownership.json)的`tasks.C`仅分配五个现有文件：`PayController`、`PayBeansConfig`、`ProductionReadiness`、`PayEndpointTest`和`ProductionReadinessTest`，以及本卡。`ProductionReadiness`在本批由C独占，不允许A/B顺手调整其他生产闸门。

冻结PayAppService构造器及SignatureVerifier/ProductFulfiller、PlayerLock、IdempotencyStore、TimeService、订单存储、GameItem signer/webhook接口。不能擅自改全局test profile、配置表、客户端或契约；如确有需要，列精确变更请求由COORD分配。PayEndpointTest整类Spring上下文与可变桩由C独占，不拆成多个写入者。

## 分格与可失败判据

- [ ] **C1 生产拒绝RED**：以实际生产路径选择条件发送legacy回调，证明当前会到旧处理器；期望修后明确拒绝且没有订单履约/资源发放副作用。只mock readiness的方法调用不足以证明路由隔离。
- [ ] **C2 最小政策实现**：生产旧route不可履约，开发/测试继续原合同；GameItem必需配置缺失、错误签名/应用/环境身份仍拒绝。不能仅移除旧verifier门禁后宣布生产ready。
- [ ] **C3 兼容回归**：覆盖生产与非生产profile组合、重复回调、缺头ACK/requestId、签名/金额/玩家/环境错误及未知渠道结果。保留当前GameItem合同，不猜取消码，不改订单金额。
- [ ] **C4 交付边界**：本地测试证明代码政策和fail-closed；真实商户、商品、沙箱交易/回调/退款及设备另交Q列前提，不能用测试密钥证据替代。

## 验证入口

在已登记的本线worktree/独立Maven仓中，用Git Bash、Node20并source`scripts/env.sh`后执行：

```bash
mvn -Dmaven.repo.local="${IRONOATH_TASK_M2:?先登记本线Maven仓}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}" -Dironoath.test.redis.address="${IRONOATH_TASK_REDIS_ADDRESS:?先登记自有Redis}" -f server/pom.xml -pl game-web -am -Dtest=PayEndpointTest,ProductionReadinessTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -Dmaven.repo.local="${IRONOATH_TASK_M2:?}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}" -Dironoath.test.redis.address="${IRONOATH_TASK_REDIS_ADDRESS:?先登记自有Redis}" -f server/pom.xml -pl game-web -am -Dtest=PayEntitlementTest,PayOrderStoreEquivalenceTest,RedissonWeChatSessionKeyStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

逐项读取实际执行数、失败/错误/跳过及最终退出码；Redis/Mongo先核隔离资源，前提不足记PREREQ而非通过。不读真实密钥，不创建真实订单/扣款/退款，不改玩家库。

测试连接不能只登记不传参；先读回实际目标与owned库/前缀，禁止静默回退27017/6379。测试或脚本不接受隔离参数时记PREREQ，交COORD补环境/量具后再跑。

## 交付与本线记录

提交生产拒绝有效反证、开发/测试兼容、GameItem配置负控、五文件补丁及测试资源清理回执。按[逐格交接](./README.md)交COORD合并规格和主台账；Q由未实现该候选者复核。

| 格 | 状态 | 基线/候选 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| C-R0 | IN_PROGRESS | 来源分支提交37c8c1f3；主树集成提交e6faa312 | ownership限定的五文件差异，`git diff --check` EXIT=0；专属M2=`D:/Java/maven/m2-ironoath-c-20261109`、Mongo `127.0.0.1:27029`、Redis `redis://127.0.0.1:6381`均为本线资源 | 已集成并在主树重跑目标套件；仍需Q独立复核；真实渠道保持未验 |
| C1-C3 | TARGETED_GREEN | 主树集成e6faa312；隔离Mongo+Redis；JDK17 | 在C候选及主树集成版本均运行`PayEndpointTest`+`ProductionReadinessTest`44/0/0/0及`PayEntitlementTest`37/0/0/0、`RedissonWeChatSessionKeyStoreTest`2/0/0/0、`PayOrderStoreEquivalenceTest`18/0/0/0；集成主树两批均零失败/零跳过。prod策略由`PayBeansConfig`工厂取显式prod Environment，再经真实Controller/业务结果验证拒绝；dev/test验证兼容。首轮错误Redis URI修正后通过 | 未留存旧行为RED；全prod应用启动、C4真实商户/商品/沙箱交易/退款与设备以及Q独立复审仍未验证 |
