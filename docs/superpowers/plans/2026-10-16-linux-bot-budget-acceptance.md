# Linux 持久5000验收工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline; one fresh-context whole-change review at the end.

**Goal:** 按用户2026-10-16明确裁决，以Linux服务端部署环境判定完整5000预算，同时保留Windows发布链路未达证据。

**Architecture:** 新增独立Linux编排器，复用未改的195 Java业务夹具与606 Mongo/Redis资源保护，Host JDK17真实编译、缓存LinuxJRE17真实执行。应用、Mongo、Redis为不同容器，通过已有同一Docker bridge的容器IP连接；不走Windows发布端口，也不共享Mongo网络namespace。Redis沿原D盘自有bind数据目录和appendfsync always。新runner拥有独立SHA和Linux容器身份回执，不冒充原fbe进程。

**Tech Stack:** Python3.12/3.13标准库、JDK17、缓存eclipse-temurin17-jre、redis:alpine、现有Mongo7.0.43。

**Spec:** 用户回答“以 Linux 服务端部署环境验收，同时保留 Windows 链路的限制记录”。#1398/1399确认发布路线等待；#1400 IPv6不解决。原Windows canonical cold old4083/new4162仍预算false。

## Global Constraints

- 原Java fixture SHA195162d3ddfc5da85496771b2bd80a0deaede30ea7e4419ee1c4a1997b7742de、原fbe runner与606资源helper不改；5000/30000ms/P99 5ms/180s、businessNow1791979200000、workers4、Netty2、46cfg保持。
- 原9490Java21756/v35原45cfg/同UID/数据库/普通1x/微信25724不动；无原业务HTTP/Mongo、push、生产部署、付款或新前台。
- 只用本机已缓存镜像，禁止自动pull、修改原Mongo容器network、全局Docker/TCP/配置或真实用户数据。
- 全新目录、随机库与marker、精确container name/run/thread/image/id；丢创建回执按名字+不可变标记恢复；归属未知保留并报错，不能当已清理。
- Java容器身份、LinuxVM PID、host Docker CLI PID区分；结果`processExited`以实际Java容器退出/移除判断。
- Linux预算是用户指定验收口径；不得把它回填为原Windows通过。正确性、预算、failed、skip、负控、资源清理分别记录。

## Review Focus

1. 容器同名被替换、归属错、已知ID改名或创建超时，均不得误删或报不存在。
2. 网络必须是现有同一个bridge；地址空/歧义/公共地址须拒绝，不自动改原Mongo网络。
3. 端口占用、保护端口及错误镜像/JRE须在业务夹具前拒绝；有效负控证明监听者仍在。
4. Mongo marker/drop、Redis fsync/readback与丢回执保护不弱化；App/Redis都实际清理且原Mongo/9490不变。
5. 夹具与映射真实：原source编译、classpath/config只读挂载、env白名单、实际Linux/JRE17/Redisson/Mongo/原队列、原始样本复算。

### Task 1: Linux编排器、资源保护与实际验收

**Files:** 新增`scripts/tools/run-bot-runtime-bench-linux.py`、`scripts/tools/bench_linux_runtime.py`、`scripts/tools/test_bench_linux_runtime.py`、`tools/BenchPortGuard.java`；修改`scripts/check.sh`接入现有和新增Python资源测试；四台账与本计划同格提交。

- [x] 实现前先用旧资源helper的真实已知ID改名行为建立有效反例，再以新容器authority钉住完整归属/丢回执/改名保留与故障传播；不是零测试或import失败。
- [x] 实现最小generic容器owner、已有bridge地址选择、guest命令/env白名单；复用606保护Mongo/Redis。
- [x] 实现独立LinuxCLI：Host JDK17编译195夹具及端口guard，缓存17JRE先跑实际busy/protected-port负控，再不同容器桥接Mongo/Redis并运行原业务。
- [x] 新Python测试和有效mutants/实际owner负控；不改原195/fbe/606，原始字节SHA保持。
- [x] 全门、freshJUnit/客户端/工具测试与新runner冻结；先memory bootstrap，再fresh旧885/新4d7真实持久有界对比及原始计数/样本/持久性/清理审计。
- [ ] 按actual结果更新台账；一次fresh最强整格审查，必要同席fixpass；明确路径原子local提交及读回，NO PUSH，继续原目标未完成项。

验证checkpoint：最终full2/24Python与所有实际负控、memory2和fresh旧新Linux持久各三轮已实跑，9phase正确但六持久预算未达；原Windows失败保留。最后的整格审/提交状态按receipt及同名`.superpowers/sdd` progress更新，不能把本checkpoint当目标完成。

Ruling: 原reviewer跨聊天不可用，本次只为唯一P2 fixpass建立fresh审查席位；原整格审查证据继续有效，不重复City/Conversation。修改仅finally与其回归，故历史六持久样本保留ae39身份；新runner全门、三memory与真实双故障另行验证，不重标样本来源。最终复审及commit receipt决定本格收口，整体预算仍未达。
