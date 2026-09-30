package com.codeagent.agent;

import com.codeagent.llm.StructuredOutputSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

/**
 * 解析 Reviewer 的结构化输出。失败关闭：无法确认通过时一律判为不通过。
 */
final class ReviewResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("approved", "summary", "issues", "suggestions");
    private static final StructuredOutputSpec OUTPUT_SPEC =
            new StructuredOutputSpec("step_review", buildSchema(), true);

    private ReviewResponseParser() {
    }

    static StructuredOutputSpec structuredOutputSpec() {
        return OUTPUT_SPEC;
    }

    static JsonNode validateStructured(JsonNode root) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("Reviewer response must be a JSON object");
        }
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IOException("Unexpected reviewer field: " + field);
            }
        }

        JsonNode approved = root.get("approved");
        JsonNode summary = root.get("summary");
        JsonNode issues = root.get("issues");
        JsonNode suggestions = root.get("suggestions");
        if (approved == null || !approved.isBoolean()) {
            throw new IOException("Reviewer field 'approved' must be boolean");
        }
        if (summary == null || !summary.isTextual()) {
            throw new IOException("Reviewer field 'summary' must be string");
        }
        validateTextArray(issues, "issues");
        validateTextArray(suggestions, "suggestions");
        return root;
    }

    static boolean parseApproved(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            JsonNode root = validateStructured(MAPPER.readTree(stripFences(reviewContent)));
            return root.path("approved").asBoolean(false);
        } catch (Exception e) {
            log.warn("Reviewer output is not valid structured JSON, defaulting to rejected: {}", e.getMessage());
            return false;
        }
    }

    static String parseIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            JsonNode root = validateStructured(MAPPER.readTree(stripFences(reviewContent)));
            String issues = joinArray(root.path("issues"));
            if (!issues.isEmpty()) {
                return issues;
            }
            String suggestions = joinArray(root.path("suggestions"));
            if (!suggestions.isEmpty()) {
                return suggestions;
            }
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
            // 落到默认提示
        }
        return "审查未通过，请改进执行结果";
    }

    private static void validateTextArray(JsonNode node, String field) throws IOException {
        if (node == null || !node.isArray()) {
            throw new IOException("Reviewer field '" + field + "' must be an array");
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IOException("Reviewer field '" + field + "' must contain only strings");
            }
        }
    }

    private static JsonNode buildSchema() {
        var root = MAPPER.createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("approved").put("type", "boolean");
        properties.putObject("summary").put("type", "string");
        properties.putObject("issues")
                .put("type", "array")
                .putObject("items").put("type", "string");
        properties.putObject("suggestions")
                .put("type", "array")
                .putObject("items").put("type", "string");
        root.putArray("required").add("approved").add("summary").add("issues").add("suggestions");
        root.put("additionalProperties", false);
        return root;
    }

    private static String stripFences(String content) {
        String fence = String.valueOf((char) 96).repeat(3);
        return content.replaceAll(fence + "json\\s*", "")
                .replaceAll(fence + "\\s*", "")
                .trim();
    }

    private static String joinArray(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        for (JsonNode item : node) {
            items.append("- ").append(item.asText()).append("\n");
        }
        return items.toString().trim();
    }
}
