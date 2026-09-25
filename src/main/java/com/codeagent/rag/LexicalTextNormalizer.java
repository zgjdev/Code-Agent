package com.codeagent.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class LexicalTextNormalizer {
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        Set<String> terms = new LinkedHashSet<>();
        for (String token : text.split("[^\\p{L}\\p{N}_$]+")) {
            addTokenForms(terms, token);
        }
        segmenter.sentenceProcess(text).forEach(token -> addTokenForms(terms, token));
        return String.join(" ", terms);
    }

    private static void addTokenForms(Set<String> terms, String token) {
        if (token == null || token.isBlank()) return;
        String cleaned = token.trim();
        terms.add(cleaned);
        terms.add(cleaned.toLowerCase(Locale.ROOT));
        for (String part : cleaned.replace('_', ' ').replace('$', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").split("\\s+")) {
            if (!part.isBlank()) terms.add(part.toLowerCase(Locale.ROOT));
        }
    }
}
