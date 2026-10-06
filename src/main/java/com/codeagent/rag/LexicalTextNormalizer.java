package com.codeagent.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class LexicalTextNormalizer {
    private final JiebaSegmenter segmenter = new JiebaSegmenter();
    private static final Set<String> QUERY_FILLERS = Set.of(
            "的", "了", "和", "与", "及", "或", "是", "在", "有", "把", "被", "从", "到", "对", "为", "于",
            "这", "那", "这个", "那个", "这些", "那些", "如何", "怎么", "怎样", "哪里", "哪个", "什么",
            "是否", "能否", "可以", "需要", "应该", "一下", "请", "请问", "吗", "呢",
            "the", "a", "an", "is", "are", "to", "of", "in", "for", "how", "where", "what", "does", "do",
            "can", "please", "and", "or");

    /** Query terms differ from index text: do not require a complete Chinese question to exist in code. */
    public String normalizeQuery(String text) {
        if (text == null || text.isBlank()) return "";
        // A standalone name (including a method call spelling) is a symbol query, even if it is "of".
        if (text.trim().matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\(\\))?")) {
            return normalize(text.trim().replace("()", ""));
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : text.split("[^\\p{L}\\p{N}_$]+")) {
            if (token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                for (String word : segmenter.sentenceProcess(token)) addQueryToken(terms, word);
            } else {
                addQueryToken(terms, token);
            }
        }
        return String.join(" ", terms);
    }

    private static void addQueryToken(Set<String> terms, String token) {
        if (token == null || token.isBlank() || !token.matches("[\\p{L}\\p{N}_$]+")
                || (QUERY_FILLERS.contains(token.toLowerCase(Locale.ROOT))
                && !token.matches("[A-Za-z0-9_$]*[A-Z][A-Za-z0-9_$]*"))) return;
        addTokenForms(terms, token);
    }

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
