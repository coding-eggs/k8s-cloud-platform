package com.coding.platformapi.k8s;

import com.coding.common.exception.CloudPlatformException;

/**
 * k8s-server 返回了 HTTP 401。
 *
 * <p>为什么要单独一个类型：401 的信封里业务码是 4003（不是 401），空 body 时则退化成通用错误码；
 * 4004 还同时表示「session 过期」和「kube config 不存在」。业务码判不准，只有 HTTP 状态码可靠。
 * 继承 {@link CloudPlatformException}，既有调用方与全局异常处理不受影响。
 */
public class K8sServerUnauthorizedException extends CloudPlatformException {

    public K8sServerUnauthorizedException(Integer code, String msg) {
        super(code, msg);
    }
}
