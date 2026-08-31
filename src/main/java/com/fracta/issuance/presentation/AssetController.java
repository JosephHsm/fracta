package com.fracta.issuance.presentation;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fracta.common.response.ApiResponse;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.domain.UnderlyingAsset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    public record CreateAssetRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull UnderlyingAsset.AssetType assetType,
            @Pattern(regexp = "^[A-Za-z]{4}$") String assetCode,
            String brokerTicker,
            @NotNull @Positive Long splitRatio,
            String description) {
    }

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateAssetRequest request) {
        long issuerId = Long.parseLong(jwt.getSubject());
        UnderlyingAsset asset = assetService.create(issuerId, request.name(), request.assetType(),
                request.assetCode(), request.brokerTicker(), request.splitRatio(), request.description());
        return ApiResponse.of(Map.of("assetId", asset.id(), "assetCode", asset.assetCode()));
    }
}
