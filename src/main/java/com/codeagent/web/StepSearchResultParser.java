package com.codeagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

/** Fixed Step JSON result fields only; snippets and XML/text are never scanned. */
public final class StepSearchResultParser {
    private StepSearchResultParser() {}

    public static List<String> discoveredUrls(JsonNode result) {
        if (result == null) return List.of();
        try {
            if (result == null || !result.isObject()) return List.of();
            if (result.has("code") && (!result.path("code").isIntegralNumber() || result.path("code").bigIntegerValue().signum() != 0)) return List.of();
            JsonNode results = result.path("results");
            if (!results.isArray() || results.size() > 20) return List.of();
            var urls = new LinkedHashSet<String>();
            for (JsonNode item : results) {
                if (!item.isObject() || !item.path("url").isTextual()) return List.of();
                String url = item.path("url").asText();
                URI uri = URI.create(url);
                if (url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                    return List.of();
                urls.add(url);
            }
            return List.copyOf(urls);
        } catch (Exception e) {
            return List.of();
        }
    }
}
