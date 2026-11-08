# B线：驻军回家前拒绝迁都

**目标**：按已有裁决接入迁都；存在旧王城驻军、撤防返城未完成或受约束攻击行军时拒绝，保存坐标和既有国家历史不丢失。
**当前状态**：R0规则与技术契约已冻结；B代码尚未实现。下一步按本卡的单元格锁→战略目标锁→国家版本CAS顺序实现迁都服务、端点和双存储回归；B代码将从当前集成主树接续，不直接改生成物。
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

- [ ] **B1 只读事实与拒绝RED**：核`Nation.moveCapital`当前生产调用，列出权限、旧驻军、返城、在途攻击、COMBAT的真来源；构造拒绝时坐标/资源/驻军均无副作用的用例。
- [ ] **B2 撤防与回家**：驻军存在拒绝；合法撤防已启动但未回家仍拒绝；真实返城完成后才进入其他迁都校验。复用既有归还链，不写模拟“已回家”标记掩盖缺口。
- [ ] **B3 接线和竞争**：使用R0生成合同完成端点→服务→国家保存；覆盖权限变化与迁都交错、攻击出发/进入COMBAT竞争、请求重试、保存失败。不得因先检查后更新而穿透守卫。
- [ ] **B4 持久与跨线回归**：新Store重读坐标/版本/历史，动态地图与新COMBAT用新坐标，已冻结COMBAT仍用旧载荷；A的历史成员和旧War计划保持可恢复。与A交叉验收由COORD在汇总SHA上执行。

## 验证入口

在已登记的独立worktree/Maven仓/测试库中，用Git Bash、Node20和`scripts/env.sh`执行：

```bash
mvn -Dmaven.repo.local="${IRONOATH_TASK_M2:?先登记本线Maven仓}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}" -f server/pom.xml -pl game-web -am -Dtest=StrategicWithdrawalEndpointFlowTest,StrategicGarrisonWithdrawalTest,NationRallyEndpointTest,NationRallyFortressMongoEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test
```

新增迁都测试类创建并纳入跟踪后再显式执行，不能用目标类不存在时的零测试当GREEN。验证范围应含共享Nation保存测试，但共享源码/测试修改由COORD统一提交。真实玩家数据库、真实账号迁都、5000与设备流程不由此卡自动授权。

测试连接不能只登记不传参；先读回实际目标与owned库/前缀，禁止静默回退27017/6379。测试或脚本不接受隔离参数时记PREREQ，交COORD补环境/量具后再跑。

## 交付与本线记录

交付拒绝/合法撤防返城/成功迁都的证据、共享接线请求、并发反证、Mongo恢复和旧COMBAT不漂移结果；保留尚未裁定或尚未验证的边界。按[逐格交接](./README.md)由COORD落档并原子本地提交。

| 格 | 状态 | 基线/候选 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| B-R0 | FROZEN | 用户本轮确认免费、无冷却及官员代行异常处理；WORLD_SIZE=512、Coord与现有城格生成判据；基线主树e6faa312 | 范围0..511；新坐标沿用世界生成判据，拒绝城市与保留战略目标。国王不查lastLoginAt；其他既有官职仅在最近登录可读、非零、非未来且离线满7天时可代行。保存沿NationStore版本CAS；迁都持有新旧坐标锁与CAPITAL目标锁，再进入NationOperationLock，行军出发共享坐标/目标锁，旧COMBAT快照不改 | 契约与代码尚未落地；下一步B1新增迁都请求/响应Schema、生成物与端点负控，随后实现/验证锁和保存。
