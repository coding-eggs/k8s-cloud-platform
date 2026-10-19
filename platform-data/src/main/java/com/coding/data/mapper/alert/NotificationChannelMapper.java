package com.coding.data.mapper.alert;

import com.coding.data.models.alert.NotificationChannel;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationChannelMapper {

    List<NotificationChannel> selectAll();

    NotificationChannel selectByCode(@Param("code") String code);

    /** 规则编辑页候选：全局 + 本租户，且 enabled=1 */
    List<NotificationChannel> selectAvailable(@Param("tenantId") String tenantId);

    /** 该租户的默认渠道（tenant_id 精确匹配且 is_default=1） */
    NotificationChannel selectTenantDefault(@Param("tenantId") String tenantId);

    /** 全局默认渠道（tenant_id is null 且 is_default=1） */
    NotificationChannel selectGlobalDefault();

    int insert(NotificationChannel c);

    int updateByCode(NotificationChannel c);

    int deleteByCode(@Param("code") String code);

    /** 把同一租户维度（tenantId 为 null 时清全局）的默认标记全部清掉 */
    int clearDefault(@Param("tenantId") String tenantId);
}
