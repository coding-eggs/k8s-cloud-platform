package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 命名空间 ListenerSet 的状态窄投影：每个 listener 是否生效、是否冲突及原因。 */
@Data
public class IngressListenerSetStatusDTO {
    private boolean exists;
    private List<Listener> listeners = new ArrayList<>();

    @Data
    public static class Listener {
        private String name;
        private Integer port;
        private String protocol;
        private boolean programmed;
        private boolean conflicted;
        /** 不生效时的原因（取第一个不为 True 的条件；冲突时取 Conflicted 的原因） */
        private String reason;
        private String message;
    }
}
