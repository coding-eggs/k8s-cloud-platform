package com.coding.auth.client;

/** 写操作的结果：客户端详情 + 本次新生成的密钥（没有则为 null） */
public record ClientCreated(ClientAdminView client, ClientSecrets secrets) {
}
