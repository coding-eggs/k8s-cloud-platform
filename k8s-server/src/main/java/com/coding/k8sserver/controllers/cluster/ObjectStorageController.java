package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.objectstorage.ObjectBucketDTO;
import com.coding.common.models.objectstorage.ObjectBucketRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectCredentialGetRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialRevokeRequestDTO;
import com.coding.common.models.objectstorage.ObjectListRequestDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.common.models.objectstorage.ObjectStatRequestDTO;
import com.coding.common.models.objectstorage.SeaweedS3ConfigRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.services.SeaweedS3Service;
import io.minio.GetObjectResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 对象存储（SeaweedFS）的集群侧操作。
 *
 * <h2>为什么是 UPSTREAM 边界</h2>
 * 租户要在控制台看自己桶里的对象 —— 和 {@code /workload-probes} 同形：本层不做租户边界判定，
 * **桶归属校验在上游 platform-api**（按台账判断 bucket.tenant_id）。k8s-server 只用平台自己的
 * {@code platform-admin} 身份访问 S3，不接收也不使用租户凭据。
 *
 * <h2>为什么不在这里做业务判断</h2>
 * 「谁能建桶 / 配额是多少 / 桶属于谁」全在 platform-api；本层只做 S3 侧 I/O 与配置 Secret 维护。
 */
@Tag(name = "集群域-对象存储", description = "S3 桶 / 对象（只读）/ 凭据配置（SeaweedFS）")
@RestController
@RequestMapping("/object-storage")
public class ObjectStorageController implements AccessBoundaryAware {

    private final SeaweedS3Service service;
    private final ResourceAccessResolver accessResolver;

    public ObjectStorageController(SeaweedS3Service service, ResourceAccessResolver accessResolver) {
        this.service = service;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/config/ensure")
    @Operation(summary = "确保平台 S3 配置文件存在（含 platform-admin 身份）",
            description = "组件安装前由 platform-api 调用；已存在则原样保留")
    public ResponseData<Void> ensureConfig(@RequestBody SeaweedS3ConfigRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        service.ensureConfigSecret(request.getClusterId());
        return new ResponseData<>();
    }

    @PostMapping("/buckets/list")
    @Operation(summary = "列桶（平台 admin 身份，用于对账）")
    public ResponseData<List<ObjectBucketDTO>> listBuckets(@RequestBody ObjectBucketRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.listBuckets(request.getClusterId(), request.getEndpoint()));
    }

    @PostMapping("/buckets/create")
    @Operation(summary = "建桶（幂等）")
    public ResponseData<Void> createBucket(@RequestBody ObjectBucketRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        service.createBucket(request.getClusterId(), request.getEndpoint(), request.getBucket());
        return new ResponseData<>();
    }

    @PostMapping("/buckets/delete")
    @Operation(summary = "删桶（非空桶会被 S3 拒绝；幂等）")
    public ResponseData<Void> deleteBucket(@RequestBody ObjectBucketRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        service.deleteBucket(request.getClusterId(), request.getEndpoint(), request.getBucket());
        return new ResponseData<>();
    }

    @PostMapping("/objects/list")
    @Operation(summary = "列对象（前缀 + key 游标分页）")
    public ResponseData<ObjectListResultDTO> listObjects(@RequestBody ObjectListRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.listObjects(request.getClusterId(), request.getEndpoint(),
                request.getBucket(), request.getPrefix(), request.getAfter(), request.getMaxKeys()));
    }

    @PostMapping("/objects/stat")
    @Operation(summary = "对象元数据")
    public ResponseData<ObjectStatDTO> statObject(@RequestBody ObjectStatRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.statObject(request.getClusterId(), request.getEndpoint(),
                request.getBucket(), request.getKey()));
    }

    /**
     * 下载对象：流式回传（大小上限由 platform-api 先 stat 再判断）。
     *
     * <p>⚠️ 这是流式端点，**不要加审计注解** —— 审计的单元是「一次用户请求」，
     * 把流式响应拖进审计路径没有意义；platform-api 那一层已经有审计。
     */
    @GetMapping("/objects/download")
    @Operation(summary = "下载对象（流式；GET 便于上游用 streamGet 原样透传）")
    public ResponseEntity<InputStreamResource> download(@RequestParam String clusterId,
                                                        @RequestParam(required = false) String endpoint,
                                                        @RequestParam String bucket,
                                                        @RequestParam String key) {
        accessResolver.assertClusterAccess(clusterId);
        GetObjectResponse object = service.openObject(clusterId, endpoint, bucket, key);
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(object));
    }

    @PostMapping("/credentials/create")
    @Operation(summary = "发放凭据（写 bucket-scoped identity + Secret，滚动 S3 网关）")
    public ResponseData<String> createCredential(@RequestBody ObjectCredentialRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.addCredential(request.getClusterId(), request.getEndpoint(),
                request.getIdentityName(), request.getSecretName(), request.getActions()));
    }

    @PostMapping("/credentials/get")
    @Operation(summary = "读取凭据（AK/SK；platform-api 校验权限后取用）")
    public ResponseData<ObjectCredentialDTO> getCredential(@RequestBody ObjectCredentialGetRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.readCredential(request.getClusterId(), request.getSecretName()));
    }

    @PostMapping("/credentials/revoke")
    @Operation(summary = "吊销凭据（删 identity + 删 Secret + 滚动）")
    public ResponseData<Void> revokeCredential(@RequestBody ObjectCredentialRevokeRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        service.revokeCredential(request.getClusterId(), request.getEndpoint(),
                request.getIdentityName(), request.getSecretName());
        return new ResponseData<>();
    }
}
