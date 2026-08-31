package com.fracta.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fracta.issuance.application.AssetService;
import com.fracta.issuance.application.IssuanceService;
import com.fracta.issuance.application.ProspectusStorage;
import com.fracta.issuance.domain.UnderlyingAsset;
import com.fracta.support.AuthTestSupport;
import com.fracta.support.IntegrationTestBase;
import com.fracta.support.ProspectusEventRecorder;

class ProspectusUploadTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    AssetService assetService;

    @Autowired
    IssuanceService issuanceService;

    @Autowired
    ProspectusStorage storage;

    @Autowired
    ProspectusEventRecorder eventRecorder;

    @Test
    @DisplayName("PDF 업로드 → MinIO 객체 존재 + prospectus_file_key 저장 + AFTER_COMMIT 이벤트 발행")
    void uploadPdfStoresObjectAndPublishesEvent() throws Exception {
        var issuer = auth.signupAndLogin("prospectus-issuer");
        UnderlyingAsset asset = assetService.create(issuer.id(), "설명서 자산",
                UnderlyingAsset.AssetType.REIT, "PROS", null, 100, null);
        long issuanceId = issuanceService.create(asset.id(), 1_000, 500,
                Instant.now().plusSeconds(86_400), Instant.now().plusSeconds(172_800)).issuanceId();

        byte[] pdf = "%PDF-1.7 fracta test prospectus".getBytes();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "prospectus.pdf";
            }
        });
        var headers = auth.bearer(issuer.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/issuances/" + issuanceId + "/prospectus",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String fileKey = objectMapper.readTree(response.getBody()).path("data").path("fileKey").asText();
        assertThat(fileKey).startsWith("issuance-" + issuanceId + "/").endsWith(".pdf");

        // MinIO에 객체가 실제로 존재한다
        assertThat(storage.exists(fileKey)).isTrue();

        // 발행 건에 파일 키가 저장됐다
        assertThat(issuanceService.get(issuanceId).prospectusFileKey()).isEqualTo(fileKey);

        // ProspectusUploadedEvent가 커밋 후 발행됐다
        assertThat(eventRecorder.events())
                .anyMatch(e -> e.issuanceId() == issuanceId && e.fileKey().equals(fileKey));
    }

    @Test
    @DisplayName("PDF가 아닌 파일은 거부한다")
    void rejectsNonPdf() {
        var issuer = auth.signupAndLogin("nonpdf-issuer");
        UnderlyingAsset asset = assetService.create(issuer.id(), "비PDF 자산",
                UnderlyingAsset.AssetType.ETF, "NPDF", null, 10, null);
        long issuanceId = issuanceService.create(asset.id(), 100, 100,
                Instant.now().plusSeconds(86_400), Instant.now().plusSeconds(172_800)).issuanceId();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("not a pdf".getBytes()) {
            @Override
            public String getFilename() {
                return "malware.exe";
            }
        });
        var headers = auth.bearer(issuer.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/issuances/" + issuanceId + "/prospectus",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
