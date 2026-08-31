package com.fracta.audit.infrastructure;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 감사 로그 저장 전 민감 필드(토큰·시크릿·주민번호·ci_hash 등)를 {@code ***}로 치환한다. */
@Component
public class SensitiveFieldMasker {

    static final String MASK = "***";

    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(password|passwd|secret|token|api_?key|ci_?hash|rrn|resident|ssn|jumin)");

    private final ObjectMapper objectMapper;

    public SensitiveFieldMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 객체를 JSON으로 직렬화하며 민감 필드를 마스킹한다. null이면 null 반환. */
    public String maskToJson(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode node = objectMapper.valueToTree(value);
        maskNode(node);
        return node.toString();
    }

    private void maskNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(name -> {
                if (SENSITIVE_FIELD.matcher(name).find()) {
                    objectNode.put(name, MASK);
                } else {
                    maskNode(objectNode.get(name));
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::maskNode);
        }
    }
}
