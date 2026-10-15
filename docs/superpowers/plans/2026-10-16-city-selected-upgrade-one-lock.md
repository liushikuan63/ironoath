# 城建选择与升级共用玩家锁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. 本聊天内联执行，设计定向审查后实施，计划完整产物仅一次fresh whole-branch终审；不新增用户批准门。

**Goal:** Bot生产选择升级目标与实际真人升级共用一次真实玩家锁，保首阶段保存、无目标无幂等键、第二阶段真实加载/结算和全部原规则；真实测量，不预承诺完整5000预算。

**Architecture:** CityAppService提取既有纯目标选择和私有请求占用骨架；普通upgrade仍幂等占用后获取真实锁，新内部入口在一次真实锁内先完成选择并保存，再生成UUID、验证/重新取serverNow/占用幂等键，执行原doUpgrade（保完整load）。两个入口各在唯一最外层catch覆盖完整锁生命周期，仅本次成功占用才释放。BotWorldAdapter改接新入口，局部supplier阶段标记保选择失败与后续失败的既有计数口径。

**Tech Stack:** 原Java17/Spring/Mongo/Redisson/JUnit，不新增依赖。**Base:** `5ae7157af36822456240e05bf3eaeb21d0bca520`。**Spec:** AGENTS.md、开发包§四#1388、tmp/persistent-budget-resume-20261015/city-selected-upgrade-one-lock-proposal.md及其设计审查回执。

## 约束与裁决

- 原195/fbe/606量具及46cfg、5000/30s5ms180s不改，P99子集/partial永不称通过。保原9490/Java21756/v35原45cfg/同P67原Mongo普通1×/微信25724；无原业务HTTP/Mongo、不push部署付款新前台、保护无关未跟踪文件。
- 第二次load/settlement、原doUpgrade业务体、玩家/城市两次CAS、原事件/扣费/求助、普通请求路径与RNG均保留。无跨保存Ctx复用、无身份/均值跨请求缓存、无业务跳过、无常驻线程或定时器。
- Ruling: 只合一次锁，不合第二次读取 — 存储映射/对象归一/时间/失锁后的状态再读仍走原路径；收益可能较小，但不以假定往返一致换取未证明的快路径。
- Ruling: 外层唯一catch负责释放 — 内层doUpgrade成功后最终unlock仍可能抛RuntimeException，必须覆盖；共享请求骨架不catch，避免提前/双release。只沿原RuntimeException策略，不扩大Error策略。
- Ruling: 首选择serverNow在第一次runLocked前采样，后阶段validate后重新取serverNow — 不用tick now/旧Ctx。先保存选择，再生成requestId/占用幂等；后阶段失败不回滚首保存。
- Ruling: 单锁关闭原两段间插入窗口并扩大持锁跨度 — 并发、锁超时和已移除I/O失败不完全等价；不改真实锁/看门狗，保第二load/CAS，补竞争和unlock失败证据，不宣称多文档事务。
- 每阶段实际检查压缩次数；新测试先git add使静态门可见；构建/压力/Web/探针串行；计划工作区保留作项目验收资料，不自动删证据。

## Task 1：完整红绿、真实验收与本地原子交付

**完成合同:** 生产接线和行为回归明确、有效首红→绿、非零全量测试无skip、47门/客户端/工具、冻结新JAR与config、固定量具新旧真实对照、新Web及相关17项FIRST探针、精确路径fresh终审与本地提交读回；正式预算未过则继续原总目标，不宣称完成。

- [x] 读取实际选择/upgrade/锁/幂等/测试入口，设计回执69ca9ffd无未解决阻断，最新proposal e9f9c542；本次BASE和原输入冻结。
- [x] 生产Adapter真实发展用例首红：memory/Mongo两条均真实升级成功，仅TooManyActualInvocations(2而非1)失败；2run/2fail/0err/0skip，生产SHA未变，city-selected-first-red1/result.actual.json。
- [x] 16项新边界用例各memory/Mongo通过，连同发展/并发/快照共6focused68/0/0/0；不把新增测试导入歧义的0tests或错把伐木场耗木的4项断言失败计产品首红。
- [x] 纯选择函数提取，旧botCityTickFacts保原withSettledCity；新入口同序load/选择/players.save/cities.save；有目标才supplier生成requestId，服务按选定目标构造原请求。
- [x] 统一验证/新now/TTL/tryAcquire私有骨架与本次Claim；两个入口各唯一外层RuntimeException catch覆盖最终unlock，无公开alreadyLocked口。原doUpgrade/load完整函数文本SHA审计一致，保第二加载。
- [x] Adapter生产实际调用新入口，成功/无目标/选择与后续失败计数经过回归；observe/RNG/动作/真人权限未改。
- [x] 三受控mutants（漏outer cleanup/提前inner cleanup/漏Adapter阶段标记）各memory/Mongo2run2fail0errskip，逐SHA恢复；最终unlock、释放顺序、不重试释放、无owner不释放及首保存持久边界全过。
- [x] 同UID真实JVM锁竞争见证被阻塞，另一UID可运行；真实Mongo受控peer第二读后改城被CAS拒绝且保peer，原player先保存的部分写边界照实断言，无事务/回滚或真实Redis分区注入声明。
- [x] full1实际298fresh2873/0/0/0（1份无现行类的旧XML stale排除），不可变XML/cases归档；fresh全量内6focused68再次全0。build/check0、1344client33tools47门；源码生产接点1，原doUpgrade/load完整文本一致。
- [x] 新候选885e4cdd/46cfg冻结，深展开13类差仅两生产源码families、无资源漂移；同195/fbe/606新memory三phase5000全过，fresh oldd2ce3811/180205.4989ms/子集 P99=99.2136 vs新4095/180174.8156ms/子集 P99=96.3545，两failed0但完整/budgetfalse。delta284不证稳定收益，五清理均真；持久warm/hot因cold未完整而未运行。
- [x] 新337Web wrapper0/rawCocos36/invalid0/fpsfalse；exact17 FIRST/batch/cleanup/webDrift全0。额外原city-build-many真实点击两栋、两HTTP成功/权威1级/队列0、pageErrors0；08queued/09done两截图实际查看，原4输出backup/newarchive/hashrestore。3memory backend退出/22port清，原9490/45cfg/v35/同UID/微信归属及输入字节保持，Web不替代原生。
- [ ] 四台账§四追加准确证据/未做、计划与源码冻结清单；一次fresh最强whole-branch审查、必要一轮修复与对应验证；明确路径原子本地提交、提交blob/hash读回、压缩计数/队列更新，继续原未完成持久5000目标。
