# Bot 每日校准有界并发 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 当前聊天原生执行，独立代理只读审查；沿既有授权连续实施，不新增设计签字门。

**Goal:** 只让不同玩家的每日校准任务有界并发，保真实玩家锁、持久完成标记及失败重试，测量冷轮收益而不承诺完整5000预算通过。

**Architecture:** 新增只负责有限批次执行与收口的 BotCalibrationBatch。Spring 管理路径默认4个 worker、上限8，兼容构造器与旧校准入口保持串行；Runtime 明确调用新并发入口。每任务继续执行原锁内 profile/player 重读及原 save；全部已派发任务结束后才回收或返回，失败停止继续派发，不取消正在写入的任务。

**Tech Stack:** Java 17、项目现有 Spring Boot/Mongo/Redisson、JDK ExecutorCompletionService；不增加依赖。

**Spec:** `待完善收口_VibeCoding开发包.md` §四 #1378、`AGENTS.md`、`tmp/persistent-budget-resume-20261015/command-window-audit.actual.json`。本计划只改变校准执行方式，不实施 lazy-decision 提案。

## Global Constraints

- 原5000 / fullRoundMillis=30000 / singleTickP99Millis=5 / 180s有界不变；原195 fixture、fbe runner和46份配置不改，不把partial/P99子集当通过。
- 保原9490 Java21756、v35/45cfg、同P67原Mongo普通1×和微信25724；不访问原业务HTTP/Mongo，不推送部署付款或新前台。
- 不改数值、RNG、World事实读取、enroll、队列协议、调度器或实际锁实现；不缓存身份、不绕真实租约，不增加常驻定时器。
- 每个校准仍在工作线程自己的真实 PlayerLock.runLocked 内读 profile/player、判断 DayKey、写原玩家版本与lastBotCalibrationAt；真人均值和now仍每批取同一份原输入。
- 只有当前已核实在玩家操作返回后调用的 Runtime 顶层路径启用并发；旧公开串行入口保留，线程绑定事务/同步资源存在时新入口回退当前线程串行。
- 小格原子本地提交；新测试先git add使门禁可见；构建、压力、Web与探针串行。每阶段读真实压缩次数。

## Review Focus

1. 一项已写入但回执失败，其他工作仍在进行：先收口已派发任务，再抛原cause；保完成标记，重试不重复改成功者。
2. 等待线程被中断：停止继续派发，所有已派发写入完成后恢复中断标志并报告失败，不留下后台写入或提前回收。
3. Spring事务/同步/绑定资源与旧重入调用：回退同线程；旧calibrate及calibrateIfNewDay保持串行，不把原有重入迁往新线程。
4. 当前生产接点：Spring真实Runtime实际走新入口，所有 worker仍持真实玩家锁并锁内重读；mock/兼容构造器串行不能替代该证据。
5. 生命周期和失败边界：最多配置数量的在途任务，首个观察到的失败后不再派发；已派发全部结束且线程退出后才返回，回收只在全成功后发生。

---

### Task 1: 钉住当前生产入口串行与需要保留的状态语义

**Files:** Create `server/game-web/src/test/java/com/ironoath/web/BotCalibrationParallelTest.java`。

**Interfaces:** 只消费现有 `BotRuntimeService.tick(long)`、BotRegistry、PlayerInitService、PlayerRepository和PlayerLock；首红不引用尚不存在的新API。

- [ ] 写Spring memory实例测试 `runtimeCalibrationOverlapsDifferentPlayerWritesButReturnsAfterBoth`：配置`ironoath.bot.calibration-workers=2`，创建两名本测试Bot；在真实玩家save、且lastBotCalibrationAt等于本次now的窄钩子中，用有超时的latch记录重叠数及实际线程。原串行应表现maxActive=1，要求2产生功能首红；finally始终释放latch并回收本测试profile。
- [ ] 同类覆盖`ackFailureDrainsWorkersAndRetryUsesPersistedMarkers`：一个真实save之后抛同一个受控异常，另一个工作阻塞；不得提前返回或设置日扫描完成，放行后才能抛cause；同日重试确认已完成者不再save。
- [ ] 运行并归档有效XML：`mvn -f server/pom.xml -pl game-web -am test -Dtest=BotCalibrationParallelTest -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false`，保非零功能失败及零errors/skips，编译失败不能计首红。

### Task 2: 有界批次与校准生产接线

**Files:** Create `server/game-web/src/main/java/com/ironoath/web/bot/BotCalibrationBatch.java`、`server/game-web/src/test/java/com/ironoath/web/bot/BotCalibrationBatchTest.java`；Modify `server/game-web/src/main/java/com/ironoath/web/bot/BotCalibrationService.java`、`server/game-web/src/main/java/com/ironoath/web/bot/BotRuntimeService.java`。

**Interfaces:**

- `BotCalibrationBatch(int workers)`：Spring属性`ironoath.bot.calibration-workers`默认4，合法1..8；包内可注入executor factory以测提交拒绝。
- `<T> List<T> run(List<String> ids, Function<String,T> task, boolean parallelAllowed)`：返回顺序与输入一致；false、workers=1、单任务或线程绑定Spring资源时同线程执行。
- BotCalibrationService保五参兼容构造器，新增Spring显式注入batch构造器；旧`calibrate(long)`/`calibrateIfNewDay(long)`保持串行。新`calibrateInParallelIfNewDay(long)`复用同一synchronized日门与原校准逻辑，供已审核的Runtime顶层使用。
- Runtime只将当前`calibration.calibrateIfNewDay(now)`接至新入口；其它时序、输入和body不改。

- [ ] 先以串行stub建立接口并写helper有效行为测试，再实现并发，避免将编译错误当并发首红。
- [ ] 用CompletionService滑窗派发，最多workers个在途任务；按完成事件观察失败并停止新派发，仍收完当前在途工作；结果按原输入索引保存。
- [ ] 多个失败按已派发的最小输入索引选择原RuntimeException/Error对象；提交拒绝同样先收口；等待中断不取消写入，收口并恢复标志，若无worker失败则抛带原InterruptedException的等待失败。
- [ ] 每次run只创建本次executor，finally关闭并等待退出；不保存可逃逸Future或常驻线程池。不在worker调用recycle或写lastCalibratedDay。
- [ ] helper测试覆盖有界重叠/有序结果、首失败停止派发并drain、原cause、提交拒绝后drain、中断后drain并恢复标志、显式串行与Spring绑定资源回退、所有实际worker线程在返回后退出。
- [ ] 校准集成测试证明每个worker仍走真实InJvm/Redisson锁接口和锁内权威读取；回收在全部成功之后，失败不回收；旧入口在调用方持本玩家锁时继续同线程。
- [ ] 运行新测试及既有BotCalibrationTest、BotCalibrationStorageEquivalenceTest、BotRuntime相关测试，确认非零执行且零fail/error/skip；审查生产接点和线程上下文边界。

### Task 3: 冻结、实际对照与交付

**Files:** 上述源码/测试；既有四份交付文档和本计划状态。

- [ ] 两类测试git add后冻结明确源码清单；独立源码审查。跑`build.sh`及`check.sh`，逐份读取本轮fresh XML与实际client/tools/gates计数，不引用预计测试数。
- [ ] 冻结新JAR/46cfg及Batch/Calibration/Runtime和未变关键类SHA；以旧deb与新候选跑同195/fbe/606的新memory和真实Mongo+Redisson+Netty有界对照。所有owner cleanup真实全过。
- [ ] 新Web完整构建，精确17FIRST首次全过、无跳过或前提不足；保原实例文件/进程归属。并发worker不存活、无脱离调用的写入须有实际证据。
- [ ] 独立复算原始样本/实际实现与回读，只按实际完整性和预算判定；并发可能只改善冷校准，不直接宣称逐Bot P99达标。
- [ ] 追加开发包§四、收口清单、验收矩阵、核心收口文档的真值与未做；最终差异独审后只提交明确路径，不推送。更新队列及本计划真实完成状态，继续未完成目标。

Self-review：覆盖目标与五项Review Focus，原入口/新入口和helper签名一致；没有任何放宽预算、并发调度或lazy业务跳过的任务。执行方式沿现有授权采用原生逐步实施和独立只读审查，不因技能默认交接模板再向用户请求重复批准。
