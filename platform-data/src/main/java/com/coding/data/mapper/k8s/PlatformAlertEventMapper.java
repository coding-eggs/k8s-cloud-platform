package com.coding.data.mapper.k8s;

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
}
