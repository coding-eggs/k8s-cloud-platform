package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformIngressPort;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 应用网关端口池分配（表 platform_ingress_port）。 */
@Repository
public interface PlatformIngressPortMapper {

    /** 某集群的全部行（含 RELEASED，冷却期判定要用） */
    List<PlatformIngressPort> selectByCluster(@Param("clusterId") String clusterId);

    PlatformIngressPort selectActiveByService(@Param("appId") String appId, @Param("serviceKey") String serviceKey);

    List<PlatformIngressPort> selectActiveByNamespace(@Param("clusterId") String clusterId,
                                                      @Param("namespace") String namespace);

    List<PlatformIngressPort> selectActiveByApp(@Param("appId") String appId);

    /** 端口被别人抢先时抛 DuplicateKeyException（uk_ingress_port） */
    int insert(PlatformIngressPort record);

    /**
     * 复用一条 RELEASED 行：条件 status=RELEASED 且 released_at &lt; cooldownCutoff；
     * 返回 0 = 期间被别人复用了（调用方换下一个端口）。
     */
    int reactivate(PlatformIngressPort record);

    int updateProtocol(@Param("id") Long id, @Param("protocol") String protocol, @Param("now") Date now);

    int releaseService(@Param("appId") String appId, @Param("serviceKey") String serviceKey, @Param("now") Date now);

    int releaseApp(@Param("appId") String appId, @Param("now") Date now);
}
