package com.coding.data.mapper.alert;

import com.coding.data.models.alert.RuleNotify;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RuleNotifyMapper {

    RuleNotify selectByRule(@Param("clusterId") String clusterId,
                            @Param("namespace") String namespace,
                            @Param("ruleName") String ruleName);

    int upsert(RuleNotify r);

    int deleteByRule(@Param("clusterId") String clusterId,
                     @Param("namespace") String namespace,
                     @Param("ruleName") String ruleName);

    /** 删除渠道前的引用检查 */
    int countByChannel(@Param("channelCode") String channelCode);
}
