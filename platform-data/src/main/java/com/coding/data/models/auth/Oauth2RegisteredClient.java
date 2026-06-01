package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * oauth2_registered_client
 */
@Data
public class Oauth2RegisteredClient implements Serializable {
    private String id;

    private String clientId;

    private Date clientIdIssuedAt;

    private String clientSecret;

    private Date clientSecretExpiresAt;

    private String clientName;

    private String clientAuthenticationMethods;

    private String authorizationGrantTypes;

    private String redirectUris;

    private String postLogoutRedirectUris;

    private String scopes;

    private String clientSettings;

    private String tokenSettings;

    private static final long serialVersionUID = 1L;
}