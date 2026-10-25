package com.coding.platformapi.controllers;

import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.ObjectStorageBucketRef;
import com.coding.platformapi.models.ObjectStorageBucketView;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.platformapi.models.ObjectStorageCredentialRef;
import com.coding.platformapi.models.ObjectStorageCredentialView;
import com.coding.platformapi.models.ObjectStorageObjectQuery;
import com.coding.platformapi.models.ObjectStorageObjectRef;
import com.coding.platformapi.models.ObjectStorageQuotaSet;
import com.coding.platformapi.models.ObjectStorageQuotaView;
import com.coding.platformapi.models.ObjectStorageQuery;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.ObjectStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 对象存储（S3 桶 / 凭据 / 对象读写）。
 *
 * <p>租户可读写**自己桶里**的对象；管理员可以代建桶、设配额、看全部。
 * v1 的对象写操作只有「上传单个对象」和「删除单个对象」—— 不做按前缀批量删、不做建目录（S3 没有目录）。
 */
@Tag(name = "对象存储", description = "桶 / 凭据 / 对象读写")
@RestController
@RequestMapping("/object-storage")
public class ObjectStorageController {

    private final ObjectStorageService service;

    @Autowired
    private AuthContext auth;

    public ObjectStorageController(ObjectStorageService service) {
        this.service = service;
    }

    @PostMapping("/buckets/list")
    @Operation(summary = "桶列表（租户按归属收窄；管理员不传 tenantId 看全部）")
    public ResponseData<List<ObjectStorageBucketView>> list(@RequestBody ObjectStorageQuery request) {
        return new ResponseData<>(service.listBuckets(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/buckets/stats")
    @Operation(summary = "桶的当前用量（对象数/字节数；超列举上限时是下限）")
    public ResponseData<List<com.coding.common.models.objectstorage.ObjectBucketStatDTO>> bucketStats(
            @RequestBody com.coding.platformapi.models.ObjectStorageStatsQuery request) {
        return new ResponseData<>(service.bucketStats(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId()), request.getBuckets()));
    }

    @PostMapping("/buckets/create")
    @Auditable(action = AuditAction.OBJECT_BUCKET_CREATE, target = "#request.name")
    @Operation(summary = "建桶（仅管理员；桶名 = t-<租户短码>-<name>）")
    public ResponseData<ObjectStorageBucketView> create(@RequestBody ObjectStorageCreateBucket request) {
        return new ResponseData<>(service.createBucket(request, operator()));
    }

    @PostMapping("/buckets/anonymous")
    @Auditable(action = AuditAction.OBJECT_BUCKET_ANONYMOUS_SET, target = "#request.bucketId")
    @Operation(summary = "开/关桶的匿名读（仅管理员；只给读，不给列目录）")
    public ResponseData<Void> setAnonymousRead(
            @RequestBody com.coding.platformapi.models.ObjectStorageBucketAnonymous request) {
        service.setAnonymousRead(request, auth.effectiveTenantId(request.getTenantId()), operator());
        return new ResponseData<>();
    }

    @PostMapping("/buckets/quota")
    @Auditable(action = AuditAction.OBJECT_BUCKET_QUOTA_SET, target = "#request.bucketId")
    @Operation(summary = "修改桶的容量上限（null = 不限；仅管理员）")
    public ResponseData<Void> setBucketQuota(
            @RequestBody com.coding.platformapi.models.ObjectStorageBucketQuota request) {
        service.setBucketQuota(request, auth.effectiveTenantId(request.getTenantId()), operator());
        return new ResponseData<>();
    }

    @PostMapping("/buckets/delete")
    @Auditable(action = AuditAction.OBJECT_BUCKET_DELETE, target = "#request.bucketId")
    @Operation(summary = "删桶（仅管理员；非空桶会被拒）")
    public ResponseData<Void> delete(@RequestBody ObjectStorageBucketRef request) {
        service.deleteBucket(request, operator());
        return new ResponseData<>();
    }

    @PostMapping("/quota/get")
    @Operation(summary = "某租户的桶配额与用量")
    public ResponseData<ObjectStorageQuotaView> quota(@RequestBody ObjectStorageQuery request) {
        return new ResponseData<>(service.quota(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/quota/set")
    @Auditable(action = AuditAction.OBJECT_QUOTA_SET, target = "#request.tenantId")
    @Operation(summary = "设置某租户的桶数上限（仅管理员）")
    public ResponseData<Void> setQuota(@RequestBody ObjectStorageQuotaSet request) {
        service.setQuota(request, operator());
        return new ResponseData<>();
    }

    @PostMapping("/credentials/list")
    @Operation(summary = "某个桶的有效凭据（只有 accessKey）")
    public ResponseData<List<ObjectStorageCredentialView>> credentials(@RequestBody ObjectStorageBucketRef request) {
        return new ResponseData<>(service.listCredentials(request.getBucketId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/credentials/issue")
    @Auditable(action = AuditAction.OBJECT_CREDENTIAL_ISSUE, target = "#request.bucketId")
    @Operation(summary = "发放凭据（每桶一组；secretKey 只在这里返回一次）")
    public ResponseData<ObjectCredentialDTO> issue(@RequestBody ObjectStorageBucketRef request) {
        return new ResponseData<>(service.issueCredential(request, operator()));
    }

    @PostMapping("/credentials/get")
    @Auditable(action = AuditAction.OBJECT_CREDENTIAL_VIEW, target = "#request.credentialId")
    @Operation(summary = "查看凭据（AK/SK；租户只能看自己桶的）")
    public ResponseData<ObjectCredentialDTO> credential(@RequestBody ObjectStorageCredentialRef request) {
        return new ResponseData<>(service.viewCredential(request,
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/credentials/revoke")
    @Auditable(action = AuditAction.OBJECT_CREDENTIAL_REVOKE, target = "#request.credentialId")
    @Operation(summary = "吊销凭据（仅管理员；滚动 S3 网关后生效）")
    public ResponseData<Void> revoke(@RequestBody ObjectStorageCredentialRef request) {
        service.revokeCredential(request, operator());
        return new ResponseData<>();
    }

    @PostMapping("/objects/list")
    @Operation(summary = "列对象（前缀 + key 游标分页）")
    public ResponseData<ObjectListResultDTO> objects(@RequestBody ObjectStorageObjectQuery request) {
        return new ResponseData<>(service.listObjects(request,
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/objects/stat")
    @Operation(summary = "对象详情")
    public ResponseData<ObjectStatDTO> stat(@RequestBody ObjectStorageObjectRef request) {
        return new ResponseData<>(service.statObject(request,
                auth.effectiveTenantId(request.getTenantId())));
    }

    /**
     * 上传单个对象（请求体 = 原始字节流，不 multipart、不缓冲）。
     *
     * <p>{@code size} 必带：平台据此在**读流之前**拒超限，同时它是下游 MinIO putObject 需要的对象长度。
     * 同名 key 直接覆盖（S3 语义）。
     */
    @PostMapping("/objects/upload")
    @Auditable(action = AuditAction.OBJECT_UPLOAD, target = "#key")
    @Operation(summary = "上传单个对象（≤100MB；请求体为原始字节流）")
    public ResponseData<Void> upload(@RequestParam String bucketId,
                                     @RequestParam String key,
                                     @RequestParam Long size,
                                     @RequestParam(required = false) String contentType,
                                     @RequestParam(required = false) String tenantId,
                                     jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        service.uploadObject(new com.coding.platformapi.models.ObjectStorageObjectUpload(
                        bucketId, key, size, contentType),
                auth.effectiveTenantId(tenantId), request.getContentLengthLong(), request.getInputStream());
        return new ResponseData<>();
    }

    @PostMapping("/objects/delete")
    @Auditable(action = AuditAction.OBJECT_DELETE, target = "#request.key")
    @Operation(summary = "删除单个对象（租户可删自己桶里的）")
    public ResponseData<Void> deleteObject(@RequestBody ObjectStorageObjectRef request) {
        service.deleteObject(request, auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    /**
     * 下载对象（流式）。
     *
     * <p>浏览器侧用 fetch + blob；**不要加审计注解**（流式端点不进审计路径）。
     */
    @PostMapping("/objects/download")
    @Operation(summary = "下载对象（≤100MB；流式）")
    public ResponseEntity<StreamingResponseBody> download(@RequestBody ObjectStorageObjectRef request) {
        // 租户与大小校验都必须在**同步**阶段完成：流式响应一旦开始，异常无法再变成 JSON 错误
        String tenantId = auth.effectiveTenantId(request.getTenantId());
        service.requireDownloadable(request, tenantId);
        StreamingResponseBody body = out -> service.download(request, tenantId, out);
        String fileName = request.getKey() == null ? "download"
                : request.getKey().substring(request.getKey().lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
