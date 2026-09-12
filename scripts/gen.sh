#!/usr/bin/env bash
# 职责：运行配置表/契约代码生成器，产出 Java DTO、TS interface 与配置表类型（唯一真源 → 双端）。
# 说明：分两步 —— 先 install 依赖模块到本地仓库，再只对 config-gen 执行 exec:java。
#       不能用 `-pl tools/config-gen -am exec:java`，因为 Maven 会对反应堆里的每个模块都跑一遍该目标，
#       在 game-common 上执行 ContractGenMain 会直接失败。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

# 用 maven.test.skip 而不是 skipTests：后者仍会编译测试源码，
# 而测试引用的正是本脚本要生成的类型，会形成「测试编译失败 → 生成器跑不起来」的死锁。
mvn -q -f server/pom.xml -pl tools/config-gen -am -Dmaven.test.skip=true install

mvn -q -f server/pom.xml -pl tools/config-gen exec:java \
  -Dexec.mainClass=com.ironoath.codegen.ContractGenMain \
  -Dexec.classpathScope=compile \
  -Dexec.args="--schema-dir=contract/proto --config-dir=contract/config --java-out=server/game-web/src/main/java/com/ironoath/web/dto/generated --ts-out=client/assets/scripts/net/generated --java-cfg-out=server/game-config/src/main/java/com/ironoath/config/cfg --ts-cfg-out=client/assets/scripts/config/generated"

echo "[gen] 代码生成完成。生成物已入库，请提交。"
