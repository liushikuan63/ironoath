# A线：国战奖分恢复与有效时长分摊

**目标**：让所有惰性结算入口都能从持久计划恢复奖分，重复投递不重复入榜；固定bonus依已裁的历史有效成员时长比例分摊。
**当前状态**：A-R0历史反查与A1/A2/A3/A4实现候选已于本地提交`f401fdae`（未推送）。2026-11-09补充`WarAwardMongoRecoveryEndpointTest`，用Mongo `WarStore`与Mongo季榜模拟首位回执落盘后中断，再由新建`MongoWarStore`、`MongoSeasonBoardStore`和`WarAppService`恢复；本轮定向1/0/0/0。A-R0、A1/A2/A4原有定向读数均在下表；当前完整`test.sh`/`build.sh`与独立Q状态在主开发包§四。
**规格**：[B13](../../B13_国家与国战.md) #2459/#2461/#2473、[开发包§零](../../待完善收口_VibeCoding开发包.md#current-execution)。技术栈沿当前Java17/Maven与内存/Mongo双实现。

## 写入边界

精确路径读取 [ownership.json](./ownership.json) 的`tasks.A`；只写这些源码/测试及本卡。主要是`WarAppService`、`RankBoardService`、`NationMembership`、War/Season存储和Social历史读取。`WarAwardPlan`及测试登记在`game-core/nation`：`WarScoreBoard.Snapshot`属于game-core，核心模块不能反向依赖game-web。

`Nation`、`NationStore`及Mongo/内存保存、`NationAppService`、`SocialAppService`、共享国家测试由COORD写。不要为了取历史而把整个国家/社交模块认领。B的迁都/March文件只读；契约与生成物不能自行改。

## 已核事实与接口依赖

- 两层成员历史**已经存在**：SocialStore保存玩家↔联盟历史，NationStore保存联盟↔国家历史。`NationMembership.playerIdsOf`目前取当前名单；`allAlliances()`排除已解散联盟，不能把当前集合当全部历史受益人。
- 已核历史反查覆盖退出、踢出、解散、跨国与重入的数据来源：建盟/审核加入/踢出/退出有时间段，解散后Mongo原`_id`档案保留且内存历史Map不随联盟对象删除；没有可辩护历史起点的旧成员不补当前名单/重试时刻基线，不新造第二套历史日志。
- **A-R0历史读取契约（签名/边界已冻结；实现和等价断言已加入但未test-compile/run）**：在`SocialStore`新增`record AllianceMemberEpoch(String playerId,long fromAt,long untilAt)`与`List<AllianceMemberEpoch> allianceMemberEpochs(String allianceId)`。返回该联盟保留的所有历史成员区间，含已解散归档；按`fromAt`、`playerId`确定性排序，区间为`[fromAt,untilAt)`，`Long.MAX_VALUE`表示尚未闭合。Mongo按联盟原`_id`读归档文档，不走过滤已解散的`allianceById`/`allAlliances()`；内存版从保留的玩家历史映射反查。旧未知成员仅在建立durable baseline后从观察时刻计时；`NationStore.allianceNationEpochs(String allianceId)`复用现有接口。
- **A-R0恢复契约**：计划冻结进`WarScoreBoard.Snapshot`；`WarAppService`先存计划再用`SeasonBoardStore.recordScopedIncrement`投递，主键`sourceId=operationId=war_<startedAt>`、`sequence=1`；所有成员投递和独立回读成功后才写`seasonAwardedAt`。`warStatus`与`declare`均恢复同一计划，缺少原结算赛季时fail closed，不回退重试当天赛季/名单。
- A输出可恢复奖分计划、确定性分配和幂等落榜；B消费国家状态时必须保持历史及既有War载荷不被迁都覆盖。共享接口改变重新走COORD，不在两线各自发明兼容方式。

## 分格与可失败判据

- [ ] **A1 复现首红**：现跑`WarEndpointTest.settledWarRetriesFailedSeasonAwardFromDurableState`和`declaringSettlesTheExpiredWarWithoutAPanelRead`。保留真实断言及最终退出码；2026-10-25历史RED不是本轮结果。
- [ ] **A2 历史区间与分配（用户已确认基数）**：覆盖加入/退出/重入、联盟退国/解散、区间交集、零有效时长、同一事实不同读取顺序。每名成员按其本人在该国的有效时长折算自己的全战期bonus；有效区间先按玩家合并，再按`floor(bonus × memberMillis / warDurationMillis)`向下取整，小数余数不转给其他成员；不改变每人全战期额度。源码和反例测试已编辑，尚未编译/执行；无Mongo URI不能运行集成测试。
- [ ] **A3 持久恢复**：在SETTLED落盘后、首人落榜后、部分成功后故障；换新Store实例重读可继续。旧赛季和受益人不漂移；重复请求/并发重试不给双份分。不能用重放`accumulate`代替幂等凭证。
- [ ] **A4 生产接线**：读面板和再次宣战都进入同一恢复机制；计数所有`settleIfExpired`生产调用点，拒绝只修一个入口。关联Rank/War/Season与历史存储测试通过后交COORD集成。

## 验证入口与停止条件

在本线worktree、独立Maven仓与自有测试资源中，用Git Bash设置Node20并source仓库`scripts/env.sh`后，执行：

```bash
mvn -Dmaven.repo.local="${IRONOATH_TASK_M2:?先登记本线Maven仓}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}" -f server/pom.xml -pl game-web -am -Dtest=WarEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -Dmaven.repo.local="${IRONOATH_TASK_M2:?}" -Dironoath.test.mongo.uri="${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}" -f server/pom.xml -pl game-web -am -Dtest=WarStoreEquivalenceTest,SeasonBoardStoreEquivalenceTest,SocialStoreEquivalenceTest,RankEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`-am`保障本轮依赖；允许上游模块无指定测试不代表目标类可零测试。必须核实际类名、执行数量和0跳过；新增测试加入跟踪后再跑引用门。全套与业务运行由Q按冻结候选执行。发现共享API/锁顺序/数据兼容需求时先交变更请求，不越界抢写。

测试连接不能只登记不传参；先读回实际目标与owned库/前缀，禁止静默回退27017/6379。测试或脚本不接受隔离参数时记PREREQ，交COORD补环境/量具后再跑。

## 交付与本线记录

交付精确补丁、来源SHA、两条RED到GREEN、区间/分摊反证、Mongo独立恢复、生产调用点、owned库清理及未做项。按[逐格交接](./README.md)由COORD同格落主台账并原子提交；只交补丁不算验收完成。

| 格 | 状态 | 基线/候选 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| A-R0 | IN_PROGRESS | 文档基线5fbf4e4c；代码提交f401fdae | `SocialStore.allianceMemberEpochs`已接内存/Mongo历史反查；`SocialStoreEquivalenceTest`现跑30/0/0/0，Mongo与内存用例全执行；A轮测试资源已登记为专属M2与自有临时Mongo | 仍待整批集成及Q独立验收；历史成员计算的Mongo服务级联动未与War投递放在同一测试中 |
| A1/A3/A4 | A1/A4 TARGETED_GREEN；A3 CROSS_MONGO_RECOVERY_GREEN | 提交f401fdae；新增`WarAwardMongoRecoveryEndpointTest` | 生产`settleIfExpired`仅有`WarAppService.java:158,373`两处，均进入统一恢复出口；`WarEndpointTest` 28/0/0/0、`RankEndpointTest` 20/0/0/0；新增Mongo故障用例在首个WAR回执真实写入后注入中断，用全新War/Season Store及WarAppService恢复，保留原计划且逐人只入账一次，1/0/0/0；随机owned数据库标记删除并读回为空 | 仍需Q独立复核冻结候选；A2跨国家历史与奖分分配的组合验收仍由分层测试证明，不据此标B13完整 |
| A2 | IN_PROGRESS | #2473及#2477用户裁决；提交f401fdae；每人全战期额度按本人有效成员时长比例折算 | `RankEndpointTest`现跑20/0/0/0，覆盖历史交集、重入后合并取整、国家归属间断、战后新成员排除、零时长及有效期重叠拒绝；`SocialStoreEquivalenceTest`与Mongo计划往返均通过 | 端到端War结算与Mongo历史读取目前由分层测试组合证明；仍需Q对冻结候选做跨国家历史/重启交叉验收，不能据局部GREEN标完整B13完成 |
