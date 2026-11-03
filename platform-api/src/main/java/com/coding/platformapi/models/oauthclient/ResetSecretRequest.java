package com.coding.platformapi.models.oauthclient;

/** @param which client | jws | jwe */
public record ResetSecretRequest(String id, String which) {
}
