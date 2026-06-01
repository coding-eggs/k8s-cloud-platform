package com.coding.common.components;

import com.coding.data.mapper.auth.Oauth2JwkMapper;
import com.coding.data.models.auth.Oauth2Jwk;
import com.nimbusds.jose.jwk.JWK;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Service
public class JwkService {


    private final Oauth2JwkMapper oauth2JwkMapper;

    /**
     * 从数据库查询所有可用 JWK 并转换为 List<JWK>
     */
    public List<JWK> loadAllJwkFromDb() {
        List<Oauth2Jwk> entities = oauth2JwkMapper.selectAll();

        return entities.stream()
                .map(this::convertToJWK)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 将单个 Oauth2Jwk 实体转换为 Nimbus JWK 对象
     */
    private JWK convertToJWK(Oauth2Jwk entity) {
        try {
            return JWK.parse(entity.getJwkJson());
        } catch (Exception e) {
            log.error("解析 JWK 失败，kid={}, error={}", entity.getKid(), e.getMessage());
            return null;   // 解析失败则跳过该密钥
        }
    }

    


}
