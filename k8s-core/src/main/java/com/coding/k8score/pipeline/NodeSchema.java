package com.coding.k8score.pipeline;

import java.util.List;

/** 一个节点的表单 schema。**非空且 key 不重复**由护栏测试钉住（spec §2.4）。 */
public record NodeSchema(List<NodeField> fields) {
}
