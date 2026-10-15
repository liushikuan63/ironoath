# 互动空队列延迟身份读取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. One fresh-context whole-branch review at the end.

**Goal:** 生产互动驱动无到期待办时不先读取全体Bot身份；有待办时仍用本次最新身份过滤后排序和分页。

**Architecture:** BotConversationStore增加具兼容default的`dueForKnownBots(long now, Supplier<? extends Collection<String>> knownBots, int limit)`。Mongo与memory实现先检查有无到期项，然后至多一次解析supplier并沿原查询；BotConversationService.drive实际转发延迟supplier。原dueFor及所有发送/完成CAS/水位/RNG/限额规则不变。

**Tech Stack:** Java17、Spring Boot3.2.5、Mongo driver4.11.2、现有JUnit/Mockito/TestMongo。

**Spec:** 本文及#1396后实际调用链：drive先求`List.copyOf(bots.botIds())`，Mongo dueFor内部empty-probe无法节省这次上游读取。当前885 JFR Profile.ids conversation叶等待约4.69s是诊断样本，不作可省墙钟或稳定收益保证。

## Global Constraints

- 固定5000/30s/P99 5ms/180s，同canonical195/fbe/606新旧fresh比较；不改fixture、配置、RNG、真人服务、Redis持久性或真实锁。
- 原9490/UID/数据库/微信保持，无原业务HTTP/Mongo，无push/deploy/payment/新前台；保护无关文件。
- 无身份缓存；空队列判断只是当前read，后来的offer可到下轮；原查询本来也有empty-probe race。新顺序可能省略无工作时的身份读取错误日志，有工作时原错误向drive原catch传播；不称全时序故障等价。
- 有工作仍过滤已知Bot后limit，不能拿due(limit)后过滤；supplier失败不得伪装为空。
- 内存实现不持monitor调用外部supplier；旧实现仅default委托保持兼容，不要求第三方实现额外优化。
- 新入口supplier为null始终拒绝；解析结果null在消费时拒绝。生产limit仍原配置，不重定义非法limit语义。

## Review Focus

1. 仅未来待办也应省名单；新offer和到期后下一调用必须可见。
2. 未知Bot超过50不能挡住后面的已知Bot；已知名单变更每次重读。
3. 探针、名单、正式查询失败不能吞掉；无工作省去名单异常属明确范围。
4. 内存supplier阻塞时其他线程offer应能完成，无跨服务monitor死锁。
5. default兼容老实现，生产service真实接线与Mongo命令/映射保持。

### Task 1: 完整延迟入口、生产接线与验收

**Files:** 修改`server/game-web/src/main/java/com/ironoath/web/bot/BotConversationStore.java`、同目录`BotConversationService.java`、`store/mongo/MongoBotConversationStore.java`、`store/memory/InMemoryBotConversationStore.java`；测试沿现有`BotConversationTest`、`BotConversationStoreTest`、`BotConversationQueryTest`，必要时新增窄测试；四台账与本计划同格提交。

- [x] 首红：真实Spring生产service空队列和仅未来待办均不读取`BotRegistry.botIds`，现状应功能失败非编译失败。
- [x] 实现具default的延迟入口，两内置实现guard后一次读名单，service传supplier。
- [x] 补边界：default委托/异常、null、empty/future/newOffer、过滤分页、最新身份、probe/find失败、supplier阻塞不持monitor；真实Mongo差分及namespace清理。
- [x] focused红绿、针对接线/先读supplier/过滤后limit的有效反例；恢复源码SHA。
- [x] 全量freshJUnit/客户端/47门/构建；新候选JAR深核源码family与配置冻结。
- [x] 同原fixture新memory、fresh旧885/新persistent串行有界比较并审计；不把partial/failed0称budget通过。
- [x] 新337Web与相关17FIRST真实probe，核产物/进程/端口清理。
- [ ] 四台账下一格写真实证据和未做；一次fresh最强whole-branch reviewer，必要同席fixpass；明确路径原子local提交/读回，NO PUSH，继续总目标。

验证checkpoint：2885server/1344client33tools47门、37focused/3mutants、真实Mongo固定计数、同canonical新旧持久与337Web/17FIRST已实跑。最终整格审查与本地commit由上述receipt记录；任务实际状态见同名`.superpowers/sdd` progress，总5000目标未完仍继续。
