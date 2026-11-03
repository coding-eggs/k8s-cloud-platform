package com.coding.auth.client;

/** 外部客户端类型，创建后不可改 */
public enum ClientType {
    /** 有后端、能保管密钥：client_secret_basic */
    CONFIDENTIAL,
    /** 纯前端 / 移动端：none，只能授权码 + PKCE */
    PUBLIC
}
