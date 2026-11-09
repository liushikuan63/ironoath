# B线：驻军回家前拒绝迁都

**目标**：按已有裁决接入迁都；存在旧王城驻军、撤防返城未完成或受约束攻击行军时拒绝，保存坐标和既有国家历史不丢失。
**当前状态**：B代码已本地提交`935609b3`并快进至主树；R0基线`bd54648c`。B1/B2通过，B3/B4保留Q及COMBAT跨线边界；最终B专项122/0/0/0、`check.sh`通过。集成主树`test.sh`已在活动时钟夹具修复下2557/0、balance-sim14/0、client1420/0，夹具修复另单独记录。完整构建、B版live HTTP、独立Q、旧批次尾项和fetch同步仍待，不推送。
**规格**：[B13 #2460及#2473补注](../../B13_国家与国战.md)、[开发包§零](../../待完善收口_VibeCoding开发包.md#current-execution)。沿现有Java17、March和COMBAT恢复架构。

## 写入边界

[ownership.json](./ownership.json)的`tasks.B`列出精确白名单：NationController、动态王城目标解析、March推进、战略撤防及对应测试。本线可在登记的拟新增路径设计专用`CapitalMigrationService`/端点测试，不代表已有实现，也不要求无关重构。

`Nation`、`NationStore`及Mongo/内存保存、`NationAppService`、`NationLeaders`、共享国家测试归COORD。`nation.schema.json`与生成DTO由COORD按本线提案生成；不得手写生成类临时绕过。A的War/Season及历史读取文件只读，需要新查询先走接口协商。

## 已裁规则与依赖

- 旧王城驻军必须合法撤防**并回家**后才可迁都。撤防操作槽完成不等于返城完成；使用真实March生命周期，不能瞬移驻军或只清占领记录。
- 国王离线满一周时其他官员可决定；复用现有身份/权限事实，不自行限定新职务、投票人数或附加门槛。
- 已发起但尚未进COMBAT的王城目标攻击March阻止迁都，直到该March结算或返程完成；未完COMBAT及在途并发必须按既有冻结载荷/锁纪律处理。已有COMBAT坐标、地形、战果不随新都城重算。
- 新坐标合法性、费用、冷却等只沿用可找到的明确规格。缺值记录产品缺口，不从测试坐标/兵值推导玩法；可以继续无依赖的拒绝分支工作。
- R0交付：迁都契约/错误边界、国家保存方法与历史保留语义、同宣战/进COMBAT/撤防返城交错时的锁或版本策略。B输出迁都专用逻辑与生产调用请求，COORD接国家聚合入口。

## 分格与可失败判据

- [x] **B1 只读事实与拒绝RED**：核`Nation.moveCapital`当前生产调用，列出权限、旧驻军、返城、在途攻击、COMBAT的真来源；构造拒绝时坐标/资源/驻军均无副作用的用例。
- [x] **B2 撤防与回家**：驻军存在拒绝；合法撤防已启动但未回家仍拒绝；真实返城完成后才进入其他迁都校验。复用既有归还链，不写模拟“已回家”标记掩盖缺口。
- [ ] **B3 接线和竞争**：使用R0生成合同完成端点→服务→国家保存；覆盖权限变化与迁都交错、攻击出发/进入COMBAT竞争、请求重试、保存失败。不得因先检查后更新而穿透守卫。
- [ ] **B4 持久与跨线回归**：新Store重读坐标/版本/历史，动态地图与新COMBAT用新坐标，已冻结COMBAT仍用旧载荷；A的历史成员和旧War计划保持可恢复。与A交叉验收由COORD在汇总SHA上执行。

## 验证入口

在B专属worktree、Maven仓和Mongo/Redis临时库中，用Git Bash、Node20和`scripts/env.sh`执行；Mongo/Redis地址每次按当前owner登记，禁止回退共享27017/6379：

```bash
mvn -o -Dmaven.repo.local="${IRONOATH_TASK_M2:?先登记B专属Maven仓}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记B自有Mongo}" -Dironoath.test.redis.address="${IRONOATH_TASK_REDIS_ADDRESS:?先登记B自有Redis}" -f server/pom.xml -pl game-web -am -Dtest=CapitalMigrationEndpointTest,CapitalMigrationServicePolicyTest,CapitalMigrationMongoEndpointTest,StrategicWithdrawalEndpointFlowTest,StrategicGarrisonWithdrawalTest,NationRallyEndpointTest,NationRallyFortressMongoEndpointTest,MarchSendSinglePlayerReadTest,RallyDepartureRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

本轮新增迁都测试类已纳入跟踪；仍显式执行目标类，不能用目标类不存在时的零测试当GREEN。修改`contract/proto`后需先运行`npm run gen`再跑`check-contract-sync.sh`。共享Nation保存与A/C交叉回归由COORD在集成主树串行执行。真实玩家数据库、真实账号迁都、5000与设备流程不由此卡自动授权。

测试连接不能只登记不传参；先读回实际目标与owned库/前缀，禁止静默回退27017/6379。测试或脚本不接受隔离参数时记PREREQ，交COORD补环境/量具后再跑。

## 交付与本线记录

交付拒绝/合法撤防返城/成功迁都的证据、共享接线请求、并发反证、Mongo恢复和旧COMBAT不漂移结果；保留尚未裁定或尚未验证的边界。按[逐格交接](./README.md)由COORD落档并原子本地提交。

| 格 | 状态 | 基线/候选 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| B-R0 | FROZEN | 本轮用户裁决；源基线`bd54648c` | `WORLD_SIZE=512`与`Coord.withinWorld`确定范围0..511；目标格沿用城市/战略目标禁占判据；费用0、冷却0；国王不查登录，其他官职须可读、非0、非未来且离线满7天。新旧坐标字典序锁→CAPITAL目标锁→NationOperationLock，存储用现有`save(expectedVersion)` CAS | 已实现请求/响应契约与生成物；无坐标/费用/冷却配置新增，见B1-B4 |
| B1 | TARGETED_GREEN | `codex/live-b-capital-20261108`，R0基线`bd54648c` | 缺路由RED见D:/tmp/ironoath-runtime-e6faa-20261109/b13-capital-endpoint-route-red-20261109.log；现`CapitalMigrationEndpointTest`覆盖必填/类型/额外字段、身份、坐标边界/城市/战略目标、重复requestId、资源与连续迁都；`CapitalMigrationServicePolicyTest`覆盖King绕过读、缺失/0/未来/未满7天/读异常/精确一周 | API尚无客户端面板调用；微信/真实服流程未验 |
| B2 | TARGETED_GREEN | 真实March/TargetOccupation/TargetOperation内存闭环 | `StrategicWithdrawalEndpointFlowTest.oldCapitalGarrisonMustActuallyReturnHomeBeforeMigration`：STATIONED拒绝、recall后RETURNING仍拒绝、processDue真实返城并完成槽后迁都成功；冻结撤防锚点保持一致 | 自有测试夹具；生产服/真玩家数据未验 |
| B3 | PARTIAL | 当前候选；现有Rally/Target操作路径接线 | 活跃旧都攻击行军拒绝；目的格已有行军时不改写其地图意义；单行军与集结出发共享坐标→CAPITAL目标锁；策略单测验证相同坐标锁串行；`MarchSendSinglePlayerReadTest`1/0、`RallyDepartureRecoveryTest`26/0，重复requestId/坐标无副作用覆盖 | 尚缺Q对跨实例竞争、权限变更/保存失败交错与COMBAT同时完成边界的独立审查 |
| B4 | PARTIAL | B专属tmpfs Mongo 27031与Redis 6383；Maven仓`D:/Java/maven/m2-ironoath-b-20261109` | `CapitalMigrationMongoEndpointTest`经HTTP迁都，以新`MongoNationStore`读回坐标/成员时段；冻结`WarAwardPlan`经新`MongoWarStore`保持相同，`WarCapitalTargetResolver`将CAPITAL映射到新坐标；测试库前缀回查`[]` | 未在同一用例持有旧COMBAT payload并迁都后核对，Q2跨线复审/整套与实体运行仍待 |
