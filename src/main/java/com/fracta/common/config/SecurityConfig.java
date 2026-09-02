package com.fracta.common.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.common.error.ErrorCode;
import com.fracta.common.response.ErrorResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.HttpServletResponse;

/** 웹앱용 JWT 인증 (Phase 3). /api/v1/** 보호, /api/v1/auth/** 공개. OAuth2·API Key는 Phase 7. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 오픈 API 전용 체인. 웹앱 체인보다 먼저 매칭되며 <b>리소스 서버를 붙이지 않는다</b>.
     *
     * <p>{@code permitAll} 만으로는 부족하다 — 리소스 서버가 살아 있으면 Bearer 토큰이 오는 순간
     * 웹앱 디코더로 검증을 시도하고, 발급자가 달라 실패해 401을 돌려준다. 오픈 API 인증은
     * {@code OpenApiGatewayFilter} 가 자기 발급자로 처리한다.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain openApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/open/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint()))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder(
            @org.springframework.beans.factory.annotation.Value("${security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    /**
     * 웹앱용 JWT. 오픈 API 발급자({@code openApiJwtEncoder}/{@code openApiJwtDecoder})와
     * 시크릿·발급자가 분리돼 있고, 웹앱 Security 체인은 이쪽을 쓴다.
     */
    @Bean
    @Primary
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) ->
                writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_UNAUTHORIZED);
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                writeError(response, HttpStatus.FORBIDDEN, ErrorCode.AUTH_FORBIDDEN);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of(code.name(), code.defaultMessage(), java.util.Map.of())));
    }
}
