package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.models.AppServiceRequest;
import com.coding.platformapi.models.AppServiceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 服务的期望配置（增删改）。只动 desired，不碰线上：发布时才按勾选生成快照。
 *
 * <p>key 规则：DNS 标签、至多 20 字符（对象名 {@code <slug>-<key>} 要装进 63）、创建后不可改、
 * 与依赖共用一个命名空间。不能以 -env / -secret / -headless 结尾：台账按这些后缀把对象归并回服务，
 * 允许的话 {@code api-env} 会和 api 的 ConfigMap 混成同一个服务。
 */
@Service
public class AppServiceConfigService {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern KEY = Pattern.compile("^[a-z]([a-z0-9-]{0,18}[a-z0-9])?$");
    private static final List<String> RESERVED_SUFFIXES = List.of("-env", "-secret", "-headless");

    private final PlatformApplicationMapper apps;
    private final PlatformAppServiceMapper services;
    private final PlatformAppDependencyMapper dependencies;
    private final PlatformAppReleaseMapper releases;

    public AppServiceConfigService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                                   PlatformAppDependencyMapper dependencies, PlatformAppReleaseMapper releases) {
        this.apps = apps;
        this.services = services;
        this.dependencies = dependencies;
        this.releases = releases;
    }

    public AppServiceView create(AppServiceRequest req, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        String key = req.getServiceKey();
        assertValidKey(key);
        Map<String, Object> desired = AppServiceDesired.normalize(req.getDesired());

        if (dependencies.selectByKey(app.getId(), key) != null) {
            throw error("名称 " + key + " 已被依赖占用");
        }
        PlatformAppService existing = services.selectByKey(app.getId(), key);
        if (existing != null) {
            // 待删除且从未发布过的残行没有任何意义，让位给新服务
            if (Boolean.TRUE.equals(existing.getPendingDelete()) && !isLive(app, key)) {
                services.deleteById(existing.getId());
            } else {
                throw error("服务 " + key + " 已存在");
            }
        }
        PlatformAppService row = new PlatformAppService();
        row.setAppId(app.getId());
        row.setServiceKey(key);
        row.setDesiredJson(JSON.writeValueAsString(desired));
        row.setPendingDelete(false);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        try {
            services.insert(row);
        } catch (DuplicateKeyException e) {
            throw error("服务 " + key + " 已存在");
        }
        return view(key, desired, false, 0);
    }

    public AppServiceView update(AppServiceRequest req, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        PlatformAppService row = require(app, req.getServiceKey());
        if (Boolean.TRUE.equals(row.getPendingDelete())) {
            throw error("服务 " + row.getServiceKey() + " 已标记删除，不能再改");
        }
        if (req.getVersion() == null || !req.getVersion().equals(row.getVersion())) {
            throw conflict();
        }
        Map<String, Object> desired = AppServiceDesired.normalize(req.getDesired());
        row.setDesiredJson(JSON.writeValueAsString(desired));
        row.setUpdatedAt(new Date());
        if (services.updateWithVersion(row) == 0) {
            throw conflict();
        }
        return view(row.getServiceKey(), desired, false, row.getVersion() + 1);
    }

    /** 从未发布过的直接删行；已发布过的标记待删除，下次发布勾选后才真正移除。 */
    public void remove(AppServiceRequest req, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        PlatformAppService row = require(app, req.getServiceKey());
        String key = row.getServiceKey();
        for (PlatformAppService other : services.selectByApp(app.getId())) {
            if (other.getServiceKey().equals(key) || Boolean.TRUE.equals(other.getPendingDelete())) {
                continue;
            }
            if (AppServiceDesired.references(AppSnapshot.parseMap(other.getDesiredJson())).contains(key)) {
                throw error("服务 " + key + " 仍被服务 " + other.getServiceKey() + " 引用，请先去掉引用");
            }
        }
        if (!isLive(app, key)) {
            services.deleteById(row.getId());
            return;
        }
        PlatformAppService patch = new PlatformAppService();
        patch.setId(row.getId());
        patch.setVersion(row.getVersion());
        patch.setPendingDelete(true);
        patch.setUpdatedAt(new Date());
        if (services.updateWithVersion(patch) == 0) {
            throw conflict();
        }
    }

    private PlatformAppService require(PlatformApplication app, String key) {
        PlatformAppService row = key == null ? null : services.selectByKey(app.getId(), key);
        if (row == null) {
            throw error("服务不存在：" + key);
        }
        return row;
    }

    private boolean isLive(PlatformApplication app, String key) {
        int no = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        if (no <= 0) {
            return false;
        }
        PlatformAppRelease live = releases.selectByNo(app.getId(), no);
        return live != null && AppSnapshot.parse(live.getSnapshotJson()).services().containsKey(key);
    }

    private static void assertValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw error("服务名需为小写字母开头的 DNS 标签（小写字母、数字、连字符，至多 20 位）");
        }
        for (String suffix : RESERVED_SUFFIXES) {
            if (key.endsWith(suffix)) {
                throw error("服务名不能以 " + suffix + " 结尾");
            }
        }
    }

    private static AppServiceView view(String key, Map<String, Object> desired, boolean pendingDelete, int version) {
        return new AppServiceView(key, AppServiceDesired.kindOf(desired), desired, pendingDelete, version);
    }

    private static CloudPlatformException conflict() {
        return error("配置冲突：服务已被他人修改，请刷新后重试");
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
