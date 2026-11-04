package com.coding.platformapi.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

/**
 * 发布执行的「实例归属 + 租约」。
 *
 * <p><b>为什么需要它</b>：发起人的 access token 只存在发起请求的那个进程内存里
 * （{@link ApplicationDeployCredentials}），而推进执行的定时任务每个实例都有一份。
 * 两个实例连同一个库时（典型：本地 IDE 起的 platform-api 与集群里那套共用一个 MySQL），
 * 另一台抢到执行就会因为「本进程没有凭据」把它打成 INTERRUPTED —— 页面显示「发布已中断」，
 * 点「继续」也只是把凭据交给某一个进程，下一拍又可能被另一边抢走。
 *
 * <p><b>判据</b>：创建 / 继续执行时就把 {@code owner_id} 写成当前实例、把租约给到
 * {@code now + LEASE_MILLIS}；之后只有本实例每拍续租，别人要等租约过期（= 原实例已死）才接管。
 * 于是「拿不到凭据」只剩一种含义：<b>接管了一个已死实例留下的执行</b>，此时判中断是对的。
 *
 * <p>实例标识默认取 HOSTNAME（容器里就是 Pod 名），本地起多个进程时才会退化成随机串 ——
 * 那种情况下起几个就多几个「实例」，问题依旧，要的是别让两个进程连同一个库。
 */
@Component
public class AppExecutionLease {

    /**
     * 一次租约的长度。worker 每拍（默认 3s）续一次，所以只有进程死掉才会等这么久才被接管；
     * 同时它要大于任何一步的最长耗时（HTTP 读超时 120s），否则推进到一半就被人抢走。
     */
    public static final long LEASE_MILLIS = 3 * 60 * 1000L;

    private final String instanceId;

    public AppExecutionLease(@Value("${platform.application.instance-id:}") String configured) {
        this.instanceId = configured == null || configured.isBlank() ? defaultInstanceId() : configured.trim();
    }

    /** 当前进程的实例标识；与执行行上的 {@code owner_id} 比对用 */
    public String instanceId() {
        return instanceId;
    }

    /** 从 now 起算的一次新租约 */
    public Date nextLease(Date now) {
        return new Date(now.getTime() + LEASE_MILLIS);
    }

    private static String defaultInstanceId() {
        for (String key : new String[]{"HOSTNAME", "POD_NAME"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "api-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
