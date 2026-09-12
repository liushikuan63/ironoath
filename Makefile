# 职责：根级构建入口（与 package.json 的 npm scripts 等价，二者都委托 scripts/*.sh）。
# 注意：Windows 上若未安装 make，请改用 `npm run <target>`。

SHELL := /usr/bin/env bash

.PHONY: help dev build test check gen test-server test-client check-layering check-contract clean

help:
	@echo "dev           启动服务端（dev profile）"
	@echo "build         全量构建（代码生成 + 分层检查 + 编译 + 单测）"
	@echo "test          跑全部单测（服务端 JUnit + 客户端 node:test）"
	@echo "check         分层依赖检查 + 双端契约一致性检查"
	@echo "gen           运行配置表/契约代码生成器"
	@echo "clean         清理构建产物"

dev:
	@bash scripts/dev.sh

build:
	@bash scripts/build.sh

test:
	@bash scripts/test.sh

check:
	@bash scripts/check.sh

gen:
	@bash scripts/gen.sh

test-server:
	@bash -c 'source scripts/env.sh && mvn -f server/pom.xml test'

test-client:
	@bash scripts/test-client.sh

check-layering:
	@bash scripts/check-layering.sh

check-contract:
	@bash scripts/check-contract-sync.sh

clean:
	@bash -c 'source scripts/env.sh && mvn -f server/pom.xml clean'
	@rm -rf client/build-test .tmp-contract-check
