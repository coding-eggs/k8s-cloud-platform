package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 应用对平台对象（目前只有中间件实例）的引用守卫 —— 与 {@code NamespaceAccessGuard} 同形：
 * 一条安全规则只要一处实现。
 */
@Service
public class ApplicationReferenceGuard {

    public static final String MIDDLEWARE_INSTANCE = "MIDDLEWARE_INSTANCE";

    private final PlatformApplicationReferenceMapper references;
    private final PlatformApplicationMapper applications;

    public ApplicationReferenceGuard(PlatformApplicationReferenceMapper references,
                                     PlatformApplicationMapper applications) {
        this.references = references;
        this.applications = applications;
    }

    /** 用一批引用整体替换某个应用的引用（建/改图时调用；部署时补 CREATE 的实例）。 */
    public void replace(String appId, List<Ref> refs) {
        references.deleteByApp(appId);
        Date now = new Date();
        for (Ref r : refs) {
            if (r.refId() == null || r.refId().isBlank()) {
                continue;   // CREATE 还没部署过 → 暂无实例可引用
            }
            PlatformApplicationReference row = new PlatformApplicationReference();
            row.setAppId(appId);
            row.setNodeId(r.nodeKey());
            row.setRefType(MIDDLEWARE_INSTANCE);
            row.setRefId(r.refId());
            row.setCreatedAt(now);
            references.insert(row);
        }
    }

    /** 被应用引用时拒绝删除，并列出引用它的应用名（用户才知道去哪个画布移除）。 */
    public void assertNotReferenced(String instanceId) {
        List<PlatformApplicationReference> refs =
                references.selectByRef(MIDDLEWARE_INSTANCE, instanceId);
        if (refs.isEmpty()) {
            return;
        }
        String apps = refs.stream().map(r -> {
            PlatformApplication a = applications.selectById(r.getAppId());
            return a == null ? r.getAppId() : a.getName();
        }).distinct().collect(Collectors.joining("、"));
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "该实例被应用 " + apps + " 使用，请先在应用里移除依赖");
    }

    /** 一条引用：应用里的节点 key → 平台对象 id。 */
    public record Ref(String nodeKey, String refId) {
    }
}
