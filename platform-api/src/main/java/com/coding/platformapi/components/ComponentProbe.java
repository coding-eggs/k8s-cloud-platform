package com.coding.platformapi.components;

/** 工作负载判据。{@code imageRepo} 与 {@code name} 至少一个（前者跨安装稳定，后者是快路径）。 */
public record ComponentProbe(String namespace, String kind, String imageRepo, String name) {
}
