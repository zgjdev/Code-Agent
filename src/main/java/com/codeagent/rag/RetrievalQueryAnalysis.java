package com.codeagent.rag;

import java.util.*;
import java.util.regex.Pattern;

/** Extracts bounded soft lexical hints; never changes semantic intent or grants path authority. */
public final class RetrievalQueryAnalysis {
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?<![A-Za-z0-9_$/\\.])([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\(\\))?)(?![A-Za-z0-9_$/\\.])");
    private static final Pattern QUOTED = Pattern.compile("`([A-Za-z_$][A-Za-z0-9_$.]*(?:\\(\\))?)`");
    private RetrievalQueryAnalysis() {}

    public static List<String> codeHints(String query) {
        if (query == null || query.isBlank() || query.length() > 8192) return List.of();
        Set<String> quoted = new HashSet<>();
        var quotes = QUOTED.matcher(query);
        while (quotes.find()) quoted.add(quotes.group(1));
        Set<String> hints = new LinkedHashSet<>();
        var matches = IDENTIFIER.matcher(query);
        while (matches.find() && hints.size() < 8) {
            String term = matches.group(1);
            if (term.length() > 128 || term.matches("(?i).*\\.(java|json|xml|yaml|yml|md|py|js|ts|txt)$")) continue;
            if (quoted.contains(term) || term.matches(".*(?:[a-z][A-Z]|[A-Z]{2}|[._$]|\\(\\)).*")) hints.add(term);
        }
        return List.copyOf(hints);
    }
}
