package com.coding.platformapi.models;

import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareInstanceLiveDTO;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import lombok.Data;

import java.util.Map;

/** 实例详情页的聚合结果：台账 + 规格 + 连接方式 + 集群里的实时对象。 */
@Data
public class MiddlewareInstanceDetail {

    private PlatformMiddlewareInstance instance;

    /** 创建时记录的规格（spec_json 解析后的 map） */
    private Map<String, Object> spec;

    private MiddlewareConnectionDTO connection;

    /** 集群里读不到（集群不可用等）时为 null，原因见 liveError */
    private MiddlewareInstanceLiveDTO live;

    private String liveError;
}
