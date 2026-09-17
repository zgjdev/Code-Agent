package com.codeagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 Reviewer 的输出。失败关闭：无法确认通过时一律判为不通过。
 */
final class ReviewResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewResponseParser() {
    }

    static boolean parseApproved(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(stripFences(reviewContent));
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过。
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    static String parseIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(stripFences(reviewContent));
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

    private static String stripFences(String content) {
        return content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
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
