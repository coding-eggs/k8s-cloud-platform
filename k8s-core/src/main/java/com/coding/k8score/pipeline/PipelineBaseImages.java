package com.coding.k8score.pipeline;

/**
 * 流水线预设节点用到的**默认镜像**：一律指向平台 Harbor（2026-10-29 用户定）。
 *
 * <p>为什么把地址硬编码在这：节点的 schema 是静态的，而渲染路径上**不做任何改写** ——
 * 用户在表单里看到什么、跑起来就是什么。要换镜像仓库，只改这一个文件（唯一落点）。
 *
 * <p>用户改选别的镜像时，平台**原样使用**：镜像框是带候选补全的输入框（候选来自该租户的
 * 镜像仓库连接，与工作负载的镜像框同一套），选中即整段替换，不会再被改写成 Harbor 地址。
 *
 * <p>⚠️ 这些镜像必须先在 Harbor 里（{@code scripts/harbor-replication.py} 的 {@code IMAGES} 表），
 * 否则流水线一跑就是 ErrImagePull。
 */
public final class PipelineBaseImages {

    private static final String HARBOR = "harbor.ackcp.cloud";
    /** 官方镜像在 Harbor 的落点：{@code <harbor>/<上游主机>/<仓库>} */
    private static final String DOCKER = HARBOR + "/docker.io";

    /** mvn —— Java 构建节点的默认，也是 {@code java-build / java-test} 预设的镜像 */
    public static final String MAVEN = DOCKER + "/library/maven:3.9-eclipse-temurin-21";

    /** Go：预设与节点默认**统一**用 alpine（体积小、只有 linux 平台，复制/拉取都省事） */
    public static final String GOLANG = DOCKER + "/library/golang:1.23-alpine";

    /** {@code node-build / node-test} 预设用的 */
    public static final String NODE = DOCKER + "/library/node:20";
    /** Node 构建节点的默认（tag 与预设不同，历史原因；两个都在 Harbor 里） */
    public static final String NODE_ALPINE = DOCKER + "/library/node:20-alpine";

    /** Python 构建节点的默认 */
    public static final String PYTHON = DOCKER + "/library/python:3.12-slim";

    /** git 克隆节点 */
    public static final String GIT = DOCKER + "/alpine/git:2.45.2";

    /** 构建并推送节点（buildah） */
    public static final String BUILDAH = HARBOR + "/quay.io/buildah/stable:v1.35";

    private PipelineBaseImages() {
    }
}
