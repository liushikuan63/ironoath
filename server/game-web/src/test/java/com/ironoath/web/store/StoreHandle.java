package com.ironoath.web.store;

/**
 * 职责：版本化仓储契约里「一次读取」的结果 —— 状态 + <b>读的那一刻</b>看到的版本。
 * 依赖：无。
 *
 * <p>版本必须在读的时候一起取：晚取（比如 save 前再查一次 {@code versionOf}）会拿到别人刚推进过的
 * 版本号，于是"过期版本被拒"这条契约会被自己绕过去 —— 而那正是它要抓的 bug。
 */
record StoreHandle<T>(T state, long readVersion) {
}
