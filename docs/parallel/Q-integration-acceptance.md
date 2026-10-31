# Q线：独立复审与集成验收

**目标**：用冻结的集成SHA和真实证据判断候选，不将各线局部GREEN相加成全项目完成。
**当前状态**：任务包已编制；只读准备可先行，运行验收尚未启动。Q是独立角色；不得由唯一实现者自述代替独立验收。
**入口**：[协同规则](./README.md)、[主开发包当前证据边界](../../待完善收口_VibeCoding开发包.md#current-execution)、[上线清单](../../上线检查清单.md)。

## 权限与输入

[ownership.json](./ownership.json)中Q没有生产源码/测试写权限，只能维护本卡及自有外部证据。发现失败交回原owner；不能改断言、换输入、取消用例或放宽准入来修绿。Q准备候选摘要时必须拿到：集成SHA、任务补丁/接口SHA、相应产物SHA、实现者与验收者、测试环境和资源owner、预期成功/拒绝判据、历史未完成项。

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

当前基线的两条War奖分RED是历史未修项；C或B局部成功可以单独记录，但Q3不得把它们排除出全量“全绿”判据。缺少设备/凭据只阻塞相应Q5项，不把整条可做工作停下，也不标项目完成。

## 资源释放与实装记录

保留包SHA256、版本/源码对应、验收者/环境、命令/最终退出码、日志截图、失败/跳过/未做。测试库清理要独立枚举owned前缀读回；TestMongo吞掉删除异常的行为不能证明清理成功。不得启停别人的容器、重启整台开发者工具或占用真实前台操作。

Q输出PASS/FAIL/PREREQ/SKIP/NOT_RUN明细和首红，不直接改主验收勾选。COORD在证据一致且归属明确时逐格更新主台账；发布仍遵守完整门槛、上游同步与现行授权。

| 格 | 状态 | 冻结输入 | 验证与证据 | 未做/下一步 |
|---|---|---|---|---|
| Q-R0 | NOT_STARTED | 无集成候选/运行产物 | 本次只编制验收矩阵，未运行代码/包/设备 | 待COORD提供真实候选、独立验收者和资源窗口 |
