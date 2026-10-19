package com.coding.data.mapper.alert;

import com.coding.data.models.alert.AlertDelivery;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertDeliveryMapper {

    /** 幂等领取：唯一键已存在时不插入、返回 0 */
    int insertIfAbsent(AlertDelivery d);

    int markSent(@Param("id") Long id);

    int markFailed(@Param("id") Long id, @Param("error") String error,
                   @Param("attempts") Integer attempts);

    List<AlertDelivery> selectLatestByEventIds(@Param("eventIds") List<Long> eventIds);

    /** 启动扫描：中断的 PENDING 记录 */
    List<AlertDelivery> selectPending();
}
