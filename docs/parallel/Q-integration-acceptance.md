# Q线：独立复审与集成验收

**目标**：用冻结的集成SHA和真实证据判断候选，不将各线局部GREEN相加成全项目完成。
**当前状态**：Q-R1已对冻结候选完成Q0–Q4独立复核：Q0 PASS；Q1-A/C PASS、Q1-B PARTIAL；Q2 PASS；Q3 PASS（现有全套日志与新增Redisson探针报告）；Q4 PASS（仅隔离dev实装范围）。Q5仍NOT_RUN；本记录不代表B13整批完成。
**入口**：[协同规则](./README.md)、[主开发包当前证据边界](../../待完善收口_VibeCoding开发包.md#current-execution)、[上线清单](../../上线检查清单.md)。

## 权限与输入

[ownership.json](./ownership.json)中Q没有生产源码/测试写权限，只能维护本卡及自有外部证据。发现失败交回原owner；不能改断言、换输入、取消用例或放宽准入来修绿。Q准备候选摘要时必须拿到：集成SHA、任务补丁/接口SHA、相应产物SHA、实现者与验收者、测试环境和资源owner、预期成功/拒绝判据、历史未完成项。

**本次复核身份与冻结输入**：Reviewer角色为Q（Codex独立只读复核者，非A/B/C实现者）。候选HEAD=`7e6556e16bf0cfa0106eb5f498755786dbecf445`（父提交`454f364601fe68dfb58cc236c72defa9539137c7`）；从父提交到本HEAD的源码差异仅新增测试探针`CapitalMigrationRedissonMongoProbe.java`。集成运行JAR SHA256=`948D7F2066BBD8EBC3A040C5193C8B94AFE735E81098FAEA483BF0E2DD7E6EDC`。本次只读已有报告与源码，没有重跑测试、构建或runtime。

## 分段验收

| 阶段 | 核验内容 | 不通过时处理 |
|---|---|---|
| Q0 文件与语义 | 写入清单和ownership一致；共享接口已有同一基线；主台账/规格/任务卡一致；有效RED及生产调用点可复核 | 返回具体路径或断点给owner；不自动扩大写权限 |
| Q1 定向回归 | A两条奖分RED与历史/幂等；B撤防到真实回家、迁都竞争与Mongo；C生产拒绝旧route及dev/test兼容、GameItem负控 | 标FAIL及首红，不用其他线成功替代 |
| Q2 交叉回归 | 成员退会/退国/解散或迁都后，旧War仍按冻结赛季/受益区间恢复；旧COMBAT不漂移；非支付改动不削弱生产readiness | 定位共享接口/锁/保存冲突，交COORD串行修复并冻结新SHA |
| Q3 全套与构建 | 同一集成输入跑check/test及适用构建；逐段最终退出码和用例数可核 | wrapper提前退出时后续为NOT_RUN，跳过为SKIP，不能沿用上轮结果 |
| Q4 真启动/产物 | 自有后端+正确环境变量+冻结产物上的关键流程；公共UI变化带已有内容可见性批跑 | 检查实际后端地址和普通/dev档，区分功能FAIL与PREREQ |
| Q5 外部/实体 | 微信包独立安装启动与流程；真实支付/设备/性能按各自授权、材料、准入验收 | 独立列前提；不以web-mobile或本地测试替代；未做保持开放 |

## 命令与执行次序

独立checkout和Maven仓准备好后，Git Bash使用Node20、JDK17（仓库`scripts/env.sh`）。先串行跑并分别记录：

```bash
: "${IRONOATH_TASK_M2:?先登记本线无空格路径的Maven仓}"
: "${IRONOATH_TASK_MONGO_URI:?先登记自有Mongo}"
: "${IRONOATH_TASK_REDIS_ADDRESS:?先登记自有Redis}"
export IRONOATH_TASK_M2 IRONOATH_TASK_MONGO_URI IRONOATH_TASK_REDIS_ADDRESS
mvn() {
  command mvn "$@" \
    "-Dmaven.repo.local=$IRONOATH_TASK_M2" \
    "-Dironoath.test.mongo.uri=$IRONOATH_TASK_MONGO_URI" \
    "-Dironoath.test.redis.address=$IRONOATH_TASK_REDIS_ADDRESS"
}
export -f mvn
bash scripts/check.sh
bash scripts/test.sh
bash scripts/build.sh
env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh
```

以上是命令清单，不是一次无条件批跑：每条必须保存实际退出码和报告；前一条失败先分类，后续是否可独立执行由COORD明确。上面只在本次Git Bash进程及其子Bash导出mvn函数，每次把三个隔离参数显式传为Maven命令行属性；仅放入MAVEN_OPTS不能证明测试fork继承地址。先用type mvn核对当前/子Bash入口；脚本若直接调用mvn.cmd、绝对路径或非Bash子进程，需同样显式传三个-D，未核实则PREREQ，不默认开跑。不覆盖别的线的install产物。COORD独占契约真源修改与正式生成物发布；Q允许在独立冻结验收树中随`check.sh/build.sh`重生成作一致性检查，不能改真源或把差异当新实现合入；有差异保留证据并返回COORD。测试运行前读回实际Mongo/Redis目标；不接受隔离属性的脚本或测试记PREREQ，不执行默认回退。

构建成功且产物SHA冻结后，再选用项目双后端批跑或对应探针；先核普通/dev档、每份探针实际后端变量与打印地址、账户/库owner及隔离能力。批跑脚本若不能满足这些条件，记PREREQ并由COORD准备自有实例，不盲目启动默认8080/默认数据库。

本候选中A1的两条War奖分RED已有当前GREEN报告；历史RED只作问题来源，不当成本轮结果。Q3仍按同一代码候选的完整门禁报告判定。隔离dev实装只支持Q4限定范围，不替代Q5生产服、设备或真实支付证据，也不代表B13整批完成。

## 资源释放与实装记录

保留包SHA256、版本/源码对应、验收者/环境、命令/最终退出码、日志截图、失败/跳过/未做。测试库清理要独立枚举owned前缀读回；TestMongo吞掉删除异常的行为不能证明清理成功。不得启停别人的容器、重启整台开发者工具或占用真实前台操作。

Q输出PASS/FAIL/PREREQ/SKIP/NOT_RUN明细和首红，不直接改主验收勾选。COORD在证据一致且归属明确时逐格更新主台账；发布仍遵守完整门槛、上游同步与现行授权。

| 格 | 状态 | 冻结输入 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| Q-R0 | PREPARED_HISTORICAL | 当时尚无集成候选 | 原记录仅编制验收矩阵；现由Q-R1冻结HEAD复核覆盖 | 保留为准备阶段历史，不再代表当前状态 |
| Q0 | PASS | HEAD `7e6556e16bf0cfa0106eb5f498755786dbecf445`；JAR `948D7F2066BBD8EBC3A040C5193C8B94AFE735E81098FAEA483BF0E2DD7E6EDC` | ownership边界清楚；A/C卡标独立Q通过，B卡已同步B3 PARTIAL与三项交错缺口；#2500是候选交接时的历史待办记录，本Q卡现登记当前结果 | 后续批次由COORD按归属追加主台账，不回写历史记录 |
| Q1-A | PASS | 同上 | `WarEndpointTest` 28/0/0/0；`RankEndpointTest` 20/0/0/0；`WarAwardMongoRecoveryEndpointTest` 2/0/0/0。Mongo历史按持久区间算出13/10/3/5，首个回执后故障并用新Store恢复，不再读成员历史；全量报告中A1两条入口均执行通过 | 生产服、release/设备/真实用户仍未验 |
| Q1-B | PARTIAL | 同上；B隔离Mongo+Redis | B定向套件130/0/0/0；`CapitalMigrationRedissonMongoProbe` 1/0/0/0。它由测试后端的Redisson客户端外再创建一个独立Redis peer client持有目标坐标锁，真实Mongo迁都等待3秒后拒绝；坐标/成员历史不变、已拿旧格锁释放，解锁后同requestId迁都成功。此为两个Redisson客户端竞争，**不是第二个后端进程** | B3仍缺权限变化交错、`NationStore.save(expectedVersion)`冲突/失败、COMBAT同时完成迁都的受控负控。最小补证：用隔离Mongo与Redis加同步屏障分别触发这三种交错，并独立读回Nation版本/坐标、CAPITAL锚点/驻军/历史、TargetOperation冻结载荷及War事件；证明只出现合法串行结果、失败不留部分写。若声称跨后端进程安全，另需第二后端进程证据 |
| Q1-C | PASS | 同上 | `PayEndpointTest` 30/0/0/0、`ProductionReadinessTest` 14/0/0/0。真实Controller在prod策略下拒绝legacy `/pay/callback`且订单保持PENDING；dev/test兼容保留；GameItem signer/readiness负控仍有效 | 真实商户/订单/扣款/退款不在本机证据范围 |
| Q2 | PASS | 同上 | A Mongo恢复用例验证旧赛季/受益区间冻结；B Mongo迁都恢复保留Nation成员历史与WarAwardPlan，COMBAT冻结坐标/投影回归通过；支付闸门测试确认非支付改动没有放宽GameItem readiness | B3三项并发边界仍由Q1-B PARTIAL追踪 |
| Q3 | PASS | 同上 | 已有`test.sh`：game-web 2729/0/0/0、balance-sim 18/0/0/0、客户端1424/1424，EXIT=0；`build.sh` EXIT=0；`check.sh` EXIT=0（静态51、客户端1424/1424、Node80/80、Python28/28）。新Redisson探针单独1/0/0/0，B套件130/0/0/0 | 全量test/build日志早于HEAD提交；从父提交到HEAD仅新增该测试探针，且有独立目标运行报告；日志未内嵌HEAD哈希，故本PASS以生产源码未变及定向探针报告为输入绑定依据，不声称全套在提交后重跑 |
| Q4 | PASS（隔离dev） | JAR SHA256 `948D7F2066BBD8EBC3A040C5193C8B94AFE735E81098FAEA483BF0E2DD7E6EDC`；端口8400；Mongo `ironoath_b13_gate_runtime_20261110`；Redis DB15 | 50名合成账号经正常API建号、队长升星与ArmyAppService训练；无army文档种兵；实际B08圈层守卫放行后捕获四关、进入SIEGE并按dev时钟推进到SETTLED，WAR榜50/50为正。Mongo读回4个目标归攻国、4个battle events、0 pending reservations、奖分计划已冻结；owner清理确认Mongo库不存在、Redis DB14/15为0键；容器停止、端口释放 | 仅隔离本机dev，不代表生产服、真实账号或B13整批验收 |
| Q5 | NOT_RUN | 无生产/微信release/真实支付输入 | 未触真实玩家数据、商户凭据或真实订单；未验release AppID、设备安装/流程及B13-9容量 | 保持未验；需各自合规输入与独立环境验收 |
