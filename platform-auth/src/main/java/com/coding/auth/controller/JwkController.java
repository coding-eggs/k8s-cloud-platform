package com.coding.auth.controller;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.common.utils.KeyGenerator;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.Oauth2JwkMapper;
import com.coding.data.models.auth.Oauth2Jwk;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

@RestController
@RequestMapping("/jwk")
@RequiredArgsConstructor
public class JwkController {

    private final Oauth2JwkMapper jwkMapper;   // 你需要自己实现这个 Service

    /**
     * 生成密钥对并保存到数据库
     */
    @PostMapping("/generate")
    public ResponseData<Oauth2Jwk> generateKeyPair(
            @RequestParam(defaultValue = "RS256", name = "签名算法") String sigAlg,          // 仅用于签名
            @RequestParam(defaultValue = "RSA-OAEP-256", name = "加密算法") String encAlg,   // 仅用于加密
            @RequestParam(required = false, name = "曲线") String curve,
            @RequestParam(defaultValue = "sig", name = "密钥使用场景") String keyUse,
            @RequestParam(defaultValue = "1", name = "优先级") Integer priority) throws ParseException, JOSEException {

        try {
            LocalDateTime now = LocalDateTime.now();
            String version = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

            String keyId;
            String jwkJson;
            JWK jwk;

            // 🔥 核心分流逻辑
            if ("sig".equalsIgnoreCase(keyUse)) {

                keyId = "sig-" + sigAlg + "-" + version;

                Curve ecCurve = null;
                if (JWSAlgorithm.Family.EC.contains(JWSAlgorithm.parse(sigAlg)) ||
                        JWSAlgorithm.Family.ED.contains(JWSAlgorithm.parse(sigAlg))) {
                    ecCurve = Curve.parse(curve != null ? curve : "P-256");
                }

                jwkJson = KeyGenerator.generateSigningKey(sigAlg, ecCurve, keyId);
                jwk = JWK.parse(jwkJson);

            } else if ("enc".equalsIgnoreCase(keyUse)) {

                Curve ecCurve = null;
                if (JWEAlgorithm.Family.ECDH_ES.contains(JWEAlgorithm.parse(encAlg))) {
                    ecCurve = Curve.parse(curve != null ? curve : "P-256");
                }

                keyId = "enc-" + encAlg + "-" + version;

                jwkJson = KeyGenerator.generateEncryptionKey(encAlg, keyId, ecCurve);
                jwk = JWK.parse(jwkJson);

            } else {
                throw new IllegalArgumentException("keyUse 只能是 sig 或 enc");
            }

            // ✅ 构建实体
            Oauth2Jwk entity = new Oauth2Jwk();
            entity.setId(ULIDGenerator.generateULID());
            entity.setKid(keyId);
            entity.setKty(jwk.getKeyType().getValue());
            entity.setUse(keyUse);

            // 🔥 这里很关键：alg 要用 jwk 自己的
            entity.setAlg(jwk.getAlgorithm() != null ? jwk.getAlgorithm().getName() : null);

            entity.setCrv(jwk instanceof ECKey ? ((ECKey) jwk).getCurve().getName() : null);

            // ⚠️ 强烈建议这里加密存储
            entity.setJwkJson(jwkJson);

            entity.setStatus("ENABLED");
            entity.setValidFrom(new Date());

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.YEAR, 1);
            entity.setValidUntil(cal.getTime());

            entity.setPriority(priority);
            entity.setCreatedAt(new Date());
            entity.setUpdatedAt(new Date());

            jwkMapper.insertSelective(entity);

            return new ResponseData<>(entity);

        } catch (Exception e) {
            throw e;
        }
    }

}
