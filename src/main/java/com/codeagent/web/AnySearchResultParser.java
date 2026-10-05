package com.codeagent.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the complete AnySearch search envelope, never arbitrary tool prose. */
public final class AnySearchResultParser {
    private static final Pattern HEADER = Pattern.compile("## Search Results \\(([0-9]{1,2}) results, [0-9]+ms\\)");
    private static final Pattern TITLE = Pattern.compile("### ([0-9]{1,2})\\. .+");
    private static final String URL_PREFIX = "- **URL**: ";

    private AnySearchResultParser() {}

    public static List<String> discoveredUrls(String text) {
        if (text == null || text.length() > 1_048_576 || text.contains("```") || text.contains("~~~")) {
            return List.of();
        }
        String[] lines = text.strip().split("\\R", -1);
        Matcher header = HEADER.matcher(lines[0]);
        if (!header.matches()) return List.of();
        int count = Integer.parseInt(header.group(1));
        if (count > 10) return List.of();
        List<String> urls = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            Matcher title = TITLE.matcher(line);
            if (title.matches()) {
                if (Integer.parseInt(title.group(1)) != urls.size() + 1 || ++i >= lines.length
                        || !lines[i].startsWith(URL_PREFIX)) return List.of();
                String url = lines[i].substring(URL_PREFIX.length());
                if (!validUrl(url)) return List.of();
                urls.add(url);
            } else if (urls.isEmpty() || line.stripLeading().startsWith("#")
                    || line.contains("**URL**:") || line.startsWith(">") || line.startsWith("    ")
                    || line.startsWith("\t")) {
                return List.of();
            }
        }
        return urls.size() == count ? List.copyOf(new LinkedHashSet<>(urls)) : List.of();
    }

    private static boolean validUrl(String url) {
        if (url.isBlank() || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
