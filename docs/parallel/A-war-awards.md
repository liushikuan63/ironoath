# A线：国战奖分恢复与有效时长分摊

**目标**：让所有惰性结算入口都能从持久计划恢复奖分，重复投递不重复入榜；固定bonus依已裁的历史有效成员时长比例分摊。
**当前状态**：任务包已编制，未分配实际owner、未启动源码实施。依赖 [协同入口R0](./README.md)；接口冻结前只做现有代码取证与测试准备。
**规格**：[B13](../../B13_国家与国战.md) #2459/#2461/#2473、[开发包§零](../../待完善收口_VibeCoding开发包.md#current-execution)。技术栈沿当前Java17/Maven与内存/Mongo双实现。

## 写入边界

精确路径读取 [ownership.json](./ownership.json) 的`tasks.A`；只写这些源码/测试及本卡。主要是`WarAppService`、`RankBoardService`、`NationMembership`、War/Season存储和Social历史读取；拟新增`WarAwardPlan`及其测试只是一种可用承载，最终结构先在R0登记。

`Nation`、`NationStore`及Mongo/内存保存、`NationAppService`、`SocialAppService`、共享国家测试由COORD写。不要为了取历史而把整个国家/社交模块认领。B的迁都/March文件只读；契约与生成物不能自行改。

## 已核事实与接口依赖

- 两层成员历史**已经存在**：SocialStore保存玩家↔联盟历史，NationStore保存联盟↔国家历史。`NationMembership.playerIdsOf`目前取当前名单；`allAlliances()`排除已解散联盟，不能把当前集合当全部历史受益人。
- 先核历史反查是否覆盖退出、踢出、解散、跨国与重入，再求两层区间的有效交集。建盟/审核加入/踢出/退出已有时间记录，解散已有历史归档；只有有效反证证明缺口时才申请COORD修改入口，不新造第二套历史日志。
- R0需给出国家历史读取签名、起止时间边界/旧记录行为、War/Season持久计划与回执关系。使用原War的持久赛季事实，缺失时fail closed，不能回退到重试当日赛季/名单。
- A输出可恢复奖分计划、确定性分配和幂等落榜；B消费国家状态时必须保持历史及既有War载荷不被迁都覆盖。接口改动重新走COORD，不在两线各自发明兼容方式。

## 分格与可失败判据

- [ ] **A1 复现首红**：现跑`WarEndpointTest.settledWarRetriesFailedSeasonAwardFromDurableState`和`declaringSettlesTheExpiredWarWithoutAPanelRead`。保留真实断言及最终退出码；2026-10-25历史RED不是本轮结果。
- [ ] **A2 历史区间与分配**：覆盖加入/退出/重入、联盟退国/解散、区间交集、零有效时长、同一事实不同读取顺序。固定bonus按已裁比例，整数舍入及余数规则必须确定、总量守恒且重放一致；不改变奖励总额。尚缺产品含义仅停该分支并记录，不猜数。
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
| A-R0 | NOT_STARTED | 文档基线5fbf4e4c；无候选 | 仅有本次只读分工，无本线测试结果 | 先核原owner及共享历史接口；原两条RED未在本轮执行 |
