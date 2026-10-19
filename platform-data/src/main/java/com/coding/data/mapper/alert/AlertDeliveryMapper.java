package com.coding.data.mapper.alert;

import com.coding.data.models.alert.AlertDelivery;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface AlertDeliveryMapper {

    /** 幂等领取：唯一键已存在时不插入、返回 0 */
    int insertIfAbsent(AlertDelivery d);

    int markSent(@Param("id") Long id);

    int markFailed(@Param("id") Long id, @Param("error") String error,
                   @Param("attempts") Integer attempts);

    List<AlertDelivery> selectLatestByEventIds(@Param("eventIds") List<Long> eventIds);

    /**
     * 启动兜底：把**中断在 PENDING** 的投递标成 FAILED。
     *
     * <p>为什么必须有：投递记录的唯一键 `(event_id, channel_code, alert_status)` 同时是去重键。
     * 发送途中进程重启 → 该行永远 PENDING，而之后 Alertmanager 每一次重推都会被
     * `insertIfAbsent=0` 挡掉 —— **这条告警的邮件永久丢失**，列表却一直显示「发送中」。
     * 标成 FAILED 至少让「没发出去」看得见。
     */
    int failStalePending(@Param("before") Date before, @Param("error") String error);
}
