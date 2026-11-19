package com.coding.platformapi.controllers;

import com.coding.common.audit.Auditable;
import com.coding.common.models.k8s.dto.admin.NamespaceRecoveryPreview;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.NamespaceRequest;
import com.coding.platformapi.services.NamespaceRequestService;
import com.coding.platformapi.services.NamespaceRequestService.Recovery;
import com.coding.platformapi.services.NamespaceRequestService.Review;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/namespace-requests")
@RequiredArgsConstructor
public class NamespaceRequestController {
    private final NamespaceRequestService requests;

    @PostMapping("/reminders")
    public ResponseData<List<com.coding.data.models.auth.PlatformTenantNamespace>> reminders() {
        return new ResponseData<>(requests.reminders());
    }

    @PostMapping("/submit")
    @Auditable(action="namespace:request",target="#request.namespace")
    public ResponseData<Void> submit(@RequestBody NamespaceRequest request) {
        requests.submit(request);
        return new ResponseData<>();
    }
    @PostMapping("/mine")
    public ResponseData<List<NamespaceRequest>> mine() { return new ResponseData<>(requests.mine()); }

    @PostMapping("/list")
    public ResponseData<List<NamespaceRequest>> list(@RequestParam String tenantId) { return new ResponseData<>(requests.list(tenantId)); }

    @PostMapping("/review")
    @Auditable(action="namespace:review",target="#review.id")
    public ResponseData<Void> review(@RequestBody Review review) {
        requests.review(review);
        return new ResponseData<>();
    }
    @PostMapping("/preview")
    public ResponseData<NamespaceRecoveryPreview> preview(@RequestBody Recovery recovery) {
        return new ResponseData<>(requests.preview(recovery));
    }
    @PostMapping("/stop")
    @Auditable(action="namespace:stop",target="#recovery.allocationId")
    public ResponseData<Void> stop(@RequestBody Recovery recovery) {
        requests.reclaim(recovery,false);
        return new ResponseData<>();
    }
    @PostMapping("/delete")
    @Auditable(action="namespace:destroy",target="#recovery.allocationId")
    public ResponseData<Void> delete(@RequestBody Recovery recovery) {
        requests.reclaim(recovery,true);
        return new ResponseData<>();
    }
}
