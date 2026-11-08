package com.coding.platformapi.services;

/**
 * 同一 (集群, 命名空间) 的 ListenerSet 整份同步互斥：读分配表 → 下发 → 释放端口必须串行，
 * 否则两次同步交错，后写的那份会把先分配的 listener 覆盖掉（终审 I3）。
 * 跨实例（本地与集群共用库）生效，所以用数据库锁而不是 JVM 锁。
 */
@FunctionalInterface
public interface IngressNamespaceLock {

    void run(String clusterId, String namespace, Runnable body);
}
