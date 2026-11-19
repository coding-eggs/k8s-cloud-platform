package com.coding.common.models.k8s.dto.admin;

import java.util.List;

public record NamespaceRecoveryPreview(String uid, int pods, List<String> volumes, List<String> workloads) {}
