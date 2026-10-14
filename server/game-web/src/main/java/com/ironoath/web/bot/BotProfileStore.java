package com.ironoath.web.bot;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import com.ironoath.core.bot.BotProfile;

/** Bot身份和完整画像的权威存储；不在真人存档上增加可公开身份字段。 */
public interface BotProfileStore {
    Optional<BotProfile> find(String botId);
    Collection<BotProfile> all();
    /** 只查询输入集合中存在的身份ID，不加载/排序完整画像；null/空集合为空，失败向外传播。 */
    Set<String> existingIds(Collection<String> playerIds);
    /** 原子保存并返回原画像；首次登记返回null。 */
    BotProfile put(BotProfile profile);
    boolean remove(String botId);
    void clear();
}
