#!/usr/bin/env bash
# 职责：本地开发 —— 启动服务端（dev profile，storage=memory，无需 MongoDB/Redis）。
# 说明：客户端需在 Cocos Creator 3.8 中打开 client/ 目录运行，本脚本只负责服务端。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

# 两步走是有原因的，不是为了啰嗦：
#   1) `spring-boot:run` 从命令行调用时会在 reactor 里每个模块各执行一次。
#      带上 -am 它就会在父聚合工程（ironoath-server）上跑一遍，而那里没有主类 ⇒
#      "Unable to find a suitable main class" 直接起不来。所以 run 只给 game-web。
#   2) 但 run 要解析 game-core / game-common 的 jar，只 -pl game-web 会用本地仓库里的旧 jar，
#      改完上游不生效还不报错。所以先 install 一次把上游模块刷进本地仓库。
mvn -f server/pom.xml -pl game-web -am -DskipTests install -q
exec mvn -f server/pom.xml -pl game-web spring-boot:run \
  -Dspring-boot.run.profiles=dev
