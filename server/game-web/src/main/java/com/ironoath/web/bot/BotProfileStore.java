package com.ironoath.web.bot;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import com.ironoath.core.bot.BotProfile;

/** Bot身份和完整画像的权威存储；不在真人存档上增加可公开身份字段。 */
public interface BotProfileStore {
    Optional<BotProfile> find(String botId);
    Collection<BotProfile> all();
    /** 全部身份ID的有序快照；不加载完整画像，不缓存跨实例身份变化，失败向外传播。 */
    Collection<String> ids();
    /** 权威存储当前身份数，不为计数反序列化全部画像。 */
    int size();
    /** 只查询输入集合中存在的身份ID，不加载/排序完整画像；null/空集合为空，失败向外传播。 */
    Set<String> existingIds(Collection<String> playerIds);
    /** 原子保存并返回原画像；首次登记返回null。 */
    BotProfile put(BotProfile profile);
    boolean remove(String botId);
    void clear();
}
