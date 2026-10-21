// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 配置热更结果（B16 §5 / 验收 7：改配置表后客户端拉取新版本，不改包生效）。只回答两个问题：现在 live 的是哪一版、这次真的换掉了哪几张表。
 */
public record ConfigReloadResp(
        String version,   // 运维全量配置表版本指纹；客户端 /ops/config/manifest 只列展示白名单，另有内容 hash 清单版本，两者不再同值。
        List<String> changed)   // 这次内容真的变了的表名。**按内容 hash 比对而不是版本号**（与清单同一口径）：改了内容忘了升版本时，版本号看不出来而 hash 看得出来。空数组表示这次热更什么都没换（文件没动过，或改回了原样），那也是要如实说出来的结果 —— 它同时也是「我改的表到底是不是这张」的复核手段。
{
}
