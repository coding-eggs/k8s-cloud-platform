package com.coding.data.mapper.k8s;

import java.util.Collection;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.data.models.k8s.PlatformAlertEventQuery;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface PlatformAlertEventMapper {
    /**
     * 幂等写入：幂等键 (cluster_id, fingerprint, starts_at) 命中则更新状态/结束时间等可变字段。
     * <p>Alertmanager 至少一次投递，同一次 firing 会话会被推很多遍；resolve 也走这里。
     */
    int upsert(PlatformAlertEvent event);

    List<PlatformAlertEvent> query(PlatformAlertEventQuery query);

    int deleteById(@Param("id") Long id);

    PlatformAlertEvent lockEvent(@Param("id") long id);

    record HandlingEntry(String actor, String kind, String note, Date createdAt) {}

    @org.apache.ibatis.annotations.Select("SELECT actor, kind, note, created_at AS createdAt FROM platform_alert_handling WHERE event_id=#{id} ORDER BY id")
    List<HandlingEntry> handling(@Param("id") long id);

    @org.apache.ibatis.annotations.Insert("INSERT INTO platform_alert_handling(event_id,actor,kind,note) VALUES(#{id},#{actor},#{kind},#{note})")
    void addHandling(@Param("id") long id, @Param("actor") String actor, @Param("kind") String kind, @Param("note") String note);

    /** 自检回收用：按告警名找最近写入的行（Task 18 的 self-test 用它删掉刚投的那条）。 */
    List<Long> findIdsByAlertname(@Param("clusterId") String clusterId,
                                  @Param("alertname") String alertname);

    /**
     * 按幂等键回查一行 —— **投递引擎要的 id 只能这么拿**。
     * <p>MySQL 的 {@code ON DUPLICATE KEY UPDATE} 只在"真插入"时回填自增 id；
     * 重推命中已有行时返回 0、id 仍是 null。投递记录按 {@code event_id} 去重，没 id 就没法记。
     */
    PlatformAlertEvent selectByEpisode(@Param("clusterId") String clusterId,
                                       @Param("fingerprint") String fingerprint,
                                       @Param("startsAt") Date startsAt);

    /** 概览计数；clusterId 为 null = 全部集群，namespaces 为 null = 不限、空集合 = 无结果 */
    List<NamespaceSeverityCount> countFiringByNamespace(@Param("clusterId") String clusterId,
                                                       @Param("namespaces") Collection<String> namespaces);

    record NamespaceSeverityCount(String namespace, String severity, long count) {}
}
