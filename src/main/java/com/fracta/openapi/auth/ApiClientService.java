package com.fracta.openapi.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.audit.api.Auditable;
import com.fracta.openapi.ApiEnv;
import com.fracta.openapi.sandbox.SandboxAccountProvisioner;

/** 클라이언트 등록·시크릿 발급/재발급·토큰 발급 (OA-01, OA-02). */
@Service
public class ApiClientService {

    private static final long TOKEN_TTL_SECONDS = 3_600;   // FSD: access_token 유효 1시간
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 평문 시크릿은 이 응답에서 딱 한 번만 나간다. 이후에는 어디서도 조회할 수 없다. */
    public record RegisteredClient(String clientId, String clientSecret, String scopes, ApiEnv env) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String scope) {
    }

    private final ApiClientRepository clients;
    private final JwtEncoder jwtEncoder;
    private final SandboxAccountProvisioner sandboxAccounts;

    public ApiClientService(ApiClientRepository clients,
                            @Qualifier("openApiJwtEncoder") JwtEncoder jwtEncoder,
                            SandboxAccountProvisioner sandboxAccounts) {
        this.clients = clients;
        this.jwtEncoder = jwtEncoder;
        this.sandboxAccounts = sandboxAccounts;
    }

    @Transactional
    @Auditable(action = "API_CLIENT_REGISTER", targetType = "API_CLIENT")
    public RegisteredClient register(String name, long ownerInvestorId, Set<ApiScope> scopes,
                                     ApiEnv env, int perSec, int perDay) {
        String clientId = "cli_" + randomToken(16);
        String secret = "sec_" + randomToken(32);
        String scopeText = ApiScope.join(scopes);

        clients.save(new ApiClient(clientId, sha256(secret), name, ownerInvestorId,
                scopeText, env, perSec, perDay));
        if (env == ApiEnv.SANDBOX) {
            sandboxAccounts.provision(ownerInvestorId);
        }
        return new RegisteredClient(clientId, secret, scopeText, env);
    }

    /** 재발급 — 반환 시점부터 기존 시크릿은 즉시 무효다. */
    @Transactional
    @Auditable(action = "API_CLIENT_ROTATE_SECRET", targetType = "API_CLIENT")
    public RegisteredClient rotateSecret(String clientId) {
        ApiClient client = clients.findByClientId(clientId)
                .orElseThrow(() -> new OpenApiExceptions.InvalidClientException(clientId));
        String secret = "sec_" + randomToken(32);
        client.rotateSecret(sha256(secret));
        return new RegisteredClient(client.clientId(), secret, client.scopes(), client.env());
    }

    /** client_credentials 그랜트. 시크릿은 해시 비교하고 타이밍 세이프하게 본다. */
    @Transactional(readOnly = true)
    public TokenResponse issueToken(String clientId, String clientSecret) {
        ApiClient client = clients.findByClientId(clientId)
                .orElseThrow(() -> new OpenApiExceptions.InvalidClientException(clientId));
        if (!client.active()
                || !MessageDigest.isEqual(
                        sha256(clientSecret).getBytes(StandardCharsets.UTF_8),
                        client.clientSecretHash().getBytes(StandardCharsets.UTF_8))) {
            throw new OpenApiExceptions.InvalidClientException(clientId);
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(OpenApiJwtConfig.ISSUER)
                .subject(client.clientId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(TOKEN_TTL_SECONDS))
                .claim("scope", client.scopes())
                .claim("env", client.env().name())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", TOKEN_TTL_SECONDS, client.scopes());
    }

    @Transactional(readOnly = true)
    public ApiClient require(String clientId) {
        return clients.findByClientId(clientId)
                .orElseThrow(() -> new OpenApiExceptions.InvalidClientException(clientId));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
