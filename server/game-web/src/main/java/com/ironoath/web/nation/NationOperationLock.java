package com.ironoath.web.nation;

import java.util.function.Supplier;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;
import java.util.IdentityHashMap;
import com.ironoath.core.lock.PlayerLock;

/**
 * 国家集结与组织变更共用的互斥边界，复用现有 JVM/Redisson 可重入锁。
 * 请求按可选玩家锁→按序全部国家锁进入；国家锁内不得再拿任何玩家锁。
 * 已持集合只能同集/子集重入，不能通过另一门面在内层加入新国家。
 * 跨玩家退款靠军队 CAS 与同档幂等凭据，不会形成国王/官员互等的锁环。
 */
public final class NationOperationLock {
    private final PlayerLock locks;
    // 同一PlayerLock提供者的不同门面也须看到外层持有集合；独立提供者互不干扰。
    private static final ThreadLocal<IdentityHashMap<PlayerLock,Set<String>>> HELD=new ThreadLocal<>();

    public NationOperationLock(PlayerLock locks) {
        this.locks = locks;
    }

    public <T> T runLocked(String nationId, Supplier<T> action) {
        return runLockedAll(java.util.Collections.singletonList(nationId),action);
    }

    /** 多方争夺须先拿齐全部国家锁；调用方不得在已持集结/目标锁时扩张国家集合。 */
    public <T> T runLockedAll(Collection<String> nationIds,Supplier<T> action) {
        if(nationIds==null || action==null)throw new IllegalArgumentException("国家锁集合和动作不得为空");
        var ordered=new TreeSet<String>();
        for(String id:nationIds) {
            if(id==null || id.isBlank())throw new IllegalArgumentException("国家锁标识不得为空");
            ordered.add(id);
        }
        if(ordered.isEmpty())return action.get();
        var scopes=HELD.get();var held=scopes==null?null:scopes.get(locks);
        if(held!=null && !held.containsAll(ordered))
            throw new IllegalStateException("已持国家锁时不能扩张国家集合，须从外层统一获取");
        var ids=List.copyOf(ordered);
        return lockNext(ids,0,()->withHeldSet(ids,action));
    }

    private <T> T withHeldSet(List<String> ids,Supplier<T> action) {
        var scopes=HELD.get();
        if(scopes==null){scopes=new IdentityHashMap<>();HELD.set(scopes);}
        var previous=scopes.get(locks);scopes.put(locks,previous==null?Set.copyOf(ids):previous);
        try{return action.get();}
        finally {
            if(previous==null)scopes.remove(locks);else scopes.put(locks,previous);
            if(scopes.isEmpty())HELD.remove();
        }
    }

    private <T> T lockNext(List<String> ids,int index,Supplier<T> action) {
        if(index==ids.size())return action.get();
        return locks.runLocked("@nation:"+ids.get(index),3000L,()->lockNext(ids,index+1,action));
    }
}
