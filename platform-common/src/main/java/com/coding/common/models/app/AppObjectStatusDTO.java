package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/** 一个对象的现读状态：READY / PENDING / MISSING / FAILED。 */
@Data
public class AppObjectStatusDTO {

    private String kind;

    private String name;

    private String state;

    private String message;

    /**
     * 工作负载未就绪时下钻到 Pod 的进展 / 问题（如「Pod x：拉取镜像失败 ImagePullBackOff（…）」），
     * 每条一行，最多几条；就绪或非工作负载为空。
     */
    private List<String> podIssues;
}
