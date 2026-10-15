package com.coding.platformapi.registry;

import java.util.List;
import java.util.Set;

/**
 * 一种镜像仓库的「方言」。设计见 docs/superpowers/specs/2026-10-14-registry-design.md §2。
 *
 * <p><b>加一档 = 实现它 + 加一个 {@code platform_credential.type} 枚举值 + 加一个 UI 选项</b>，
 * 候选端点 / 浏览端点 / 缓存 / 权限码 / 浏览页 / 前端**全都不用改**。
 * 「忘了实现」由 {@link RegistryDialectRegistry} 的护栏测试在构建期拦住。
 *
 * <p>⚠️ 将来若某一档的**测试连接**方式与 {@code /v2/} 不同（如 JFrog Artifactory 的端点在
 * {@code /artifactory/api/docker/<repo>/v2/}），届时要把 probe 也挪进本接口 ——
 * 现在有意不预加，那会是直到那天都没人调的方法。见 spec §11 的清单。
 */
public interface RegistryDialect {

    /** 对应 {@code platform_credential.type} 的值。 */
    String type();

    /**
     * 关键词补全。入参是用户在输入框里已有的**整串**（如 {@code harbor.example.com/proj/ap}）。
     *
     * <p>返回的每一条都是**要填进输入框的完整字符串** —— 拼接规则是方言相关的
     * （Harbor 是 {@code host/project/repo:tag}），交给前端拼就等于让前端懂方言。
     *
     * <p><b>取不到时返回空列表，不抛</b>：补全是每按键一次的高频动作，
     * 弹错会掩盖真正的问题，也会让用户以为「填不了镜像」（spec §5.3）。
     */
    List<String> suggest(CredentialRef cred, String imagePrefix);

    /**
     * 沿层级往下看。{@code path} 为空 = 第一层。
     *
     * <p>与 {@link #suggest} 相反，这里**失败了要抛** —— 用户在页面上主动点了一层，
     * 什么都不说等于页面坏了（spec §5.3 的姿态差异）。
     */
    BrowseResult browse(CredentialRef cred, List<String> path);

    /**
     * 本档允许的认证方式。{@code NONE} = 匿名。
     *
     * <p>声明在**各档自己**这里，是为了守住「加一档只动一处」——
     * 在 {@code CredentialService} 里维护一份「哪个类型能匿名」的手写清单就是第二个真相源，
     * 那正是 S3.1 终审删掉 {@code RegistryService.REGISTRY_TYPES} 时反对的东西。
     *
     * <p>至少要有一个值，由 {@code RegistryDialectRegistryTest} 的护栏测试钉住。
     */
    Set<String> authTypes();

    /**
     * 「测试连接」的规格。见 {@link ProbeSpec}。
     *
     * <p>⚠️ 出站那一半（超时、{@code Redirect.NEVER}、错误归因）**仍在
     * {@code CredentialProbeClient} 一处**，各档只给 URL 与文案 —— 那几条安全要求散到各档会漏。
     */
    ProbeSpec probeSpec(CredentialRef cred);

    /**
     * 把用户填的 {@code endpoint} 归一成这一档认的写法（默认：原样 {@code trim}）。
     *
     * <p>存在的理由：Docker Hub 的 endpoint 是**恒定的**（它没有"用户自己的地址"，
     * 出站用的两个站点也写死在方言里），所以那一档把三个别名统一成 {@code docker.io}、
     * 填别的直接拒 —— 不静默改写（管理员会以为配的是个私有 registry）。
     * 规则归**这一档**声明，不在 {@code CredentialService} 里按 type 分叉（spec §3.7 的同一条判断）。
     */
    default String normalizeEndpoint(String endpoint) {
        return endpoint == null ? null : endpoint.trim();
    }
}
