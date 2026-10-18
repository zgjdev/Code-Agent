package com.codeagent.memory;

import com.codeagent.util.JiebaSegmenterFactory;
import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Identical segmentation for FTS documents and queries; document term frequency is retained. */
final class MemorySearchTerms {
    private static final JiebaSegmenter SEGMENTER = JiebaSegmenterFactory.createSilently();

    private MemorySearchTerms() {}

    static synchronized String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        List<String> terms = new ArrayList<>();
        for (String word : SEGMENTER.sentenceProcess(text.toLowerCase(Locale.ROOT))) {
            for (String part : word.split("[^\\p{L}\\p{N}_]+")) {
                if (!part.isBlank()) terms.add(part);
            }
        }
        return String.join(" ", terms);
    }

    static String query(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return "";
        return new LinkedHashSet<>(List.of(normalized.split(" +"))).stream().limit(64)
                .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(" OR "));
    }
}
