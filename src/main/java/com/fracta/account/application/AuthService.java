package com.fracta.account.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fracta.account.api.InvestorId;
import com.fracta.account.api.InvestorRegisteredEvent;
import com.fracta.account.domain.Investor;
import com.fracta.account.infrastructure.InvestorRepository;
import com.fracta.audit.api.Auditable;
import com.fracta.common.config.JwtProperties;
import com.fracta.common.error.DomainException;
import com.fracta.common.error.ErrorCode;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** 투자자 등록(AC-01)·로그인·JWT 발급. */
@Service
public class AuthService {

    public static class DuplicateEmailException extends DomainException {
        public DuplicateEmailException(String email) {
            super(ErrorCode.VALID_DUPLICATE_EMAIL, "이미 등록된 이메일이다", Map.of("email", email));
        }
    }

    public static class InvalidCredentialsException extends DomainException {
        public InvalidCredentialsException() {
            super(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }

    private final InvestorRepository investors;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final ApplicationEventPublisher events;

    public AuthService(InvestorRepository investors, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, JwtProperties jwtProperties,
                       ApplicationEventPublisher events) {
        this.investors = investors;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.events = events;
    }

    @Transactional
    @Auditable(action = "INVESTOR_SIGNUP", targetType = "INVESTOR", targetId = "#result.value()")
    public InvestorId signup(String name, String email, String rawPassword) {
        investors.findByEmail(email).ifPresent(i -> {
            throw new DuplicateEmailException(email);
        });
        // CI는 Mock 해시 (AC-01). 실명 확인 연동은 범위 밖
        String ciHash = sha256Hex("mock-ci|" + email + "|" + name);
        Investor investor = investors.save(
                new Investor(name, email, passwordEncoder.encode(rawPassword), ciHash));
        InvestorId id = InvestorId.of(investor.id());
        events.publishEvent(new InvestorRegisteredEvent(id));
        return id;
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String email, String rawPassword) {
        Investor investor = investors.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, investor.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueToken(investor.id(), investor.name(), investor.role().name());
    }

    public TokenResponse issueToken(long investorId, String name, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(investorId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(jwtProperties.ttlSeconds()))
                .claim("name", name)
                .claim("role", role)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", jwtProperties.ttlSeconds());
    }

    private static String sha256Hex(String input) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", e);
        }
    }
}
