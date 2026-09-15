// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /world/marches 响应体：我的全部行军 + 家坐标。也带三个地图布局参数（worldSize / chunkSize / maxChunks）——**为什么放在这个响应而不是 ViewportResp**：客户端要用它们建立地图模型，而第一个 viewport 请求必须等模型建立（要算视野中心块与 chunk 键），放在 viewport 响应里就是鸡生蛋；marches 是「进入世界」时建模之前唯一必拉的响应（家坐标也在它里面），所以布局参数和 home 一起下发。
 */
public record MarchListResp(
        List<MarchView> marches,
        Coord home,
        int maxConcurrent,   // 同时出征上限，来源 global.MARCH_MAX_CONCURRENT。下发是为了让客户端能显示「2/3」而不是自己读配置
        Long peaceUntil,   // 自愿停战（免战）到期时刻，null 表示当前没有。三个来源都写同一本账（免战牌道具、闭城死守、流亡迁城），所以这里只有一个字段。下发是为了让地图上的「免战中」标记与真实判定同源
        Long nextExileAt,   // 下一次可以流亡迁城的时刻，null 表示随时可以。必须由服务端给：客户端本地缓存上一次迁城时刻的话，重装或换设备就会看到一个「可以迁」而服务端回 6011 —— 冷却是滚动窗口，两端各算一份必然漂
        int worldSize,   // 世界边长（格）。来源 global.WORLD_SIZE。下发是为了让客户端的地图模型不再把 global.json 的值镜像成常数（铁律 1：一个数字只能有一个家）
        int chunkSize,   // 地图分块边长（格），必须是正的 2 的幂。来源 global.WORLD_CHUNK_SIZE。与服务端 Coord.chunkKey 的位移约束同一份
        int maxChunks,   // 客户端同时持有的块数上限，必须是完全平方数（3×3=9，视野没有中心块就没法增量下发）。来源 global.VIEWPORT_CHUNK_COUNT。下发是为了让客户端能算视野块数与缓存上界，而不是自己读配置
        long serverNow)
{
}
