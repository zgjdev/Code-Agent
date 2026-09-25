package com.codeagent.rag.embedding;

import com.codeagent.memory.MemoryEntry;

import java.util.ArrayList;
import java.util.List;

/** Applies the stable BGE retrieval prefixes and a conservative input budget. */
public final class EmbeddingInputPolicy {
    static final int MAX_ESTIMATED_TOKENS = 384;
    private static final int PART_CONTENT_BUDGET = 360;
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String DOCUMENT_PREFIX = "代码文档：";

    public String prepareQuery(String input) {
        return QUERY_PREFIX + normalize(input);
    }

    public String prepareDocument(String input) {
        return DOCUMENT_PREFIX + normalize(input);
    }

    public List<String> prepareDocumentParts(String input) {
        String normalized = normalize(input);
        if (MemoryEntry.estimateTokens(prepareDocument(normalized)) <= MAX_ESTIMATED_TOKENS) {
            return List.of(prepareDocument(normalized));
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : normalized.split("\\R", -1)) {
            String candidate = current.isEmpty() ? line : current + "\n" + line;
            if (MemoryEntry.estimateTokens(prepareDocument(candidate)) <= PART_CONTENT_BUDGET) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                String completed = current.toString();
                result.add(prepareDocument(completed));
                current.setLength(0);
                String overlap = trailingLines(completed, 2);
                String withOverlap = overlap.isEmpty() ? line : overlap + "\n" + line;
                if (MemoryEntry.estimateTokens(prepareDocument(withOverlap)) <= PART_CONTENT_BUDGET) {
                    current.append(withOverlap);
                    continue;
                }
            }
            appendOversizedLine(line, result, current);
        }
        if (!current.isEmpty()) result.add(prepareDocument(current.toString()));
        if (result.size() <= 1) return List.copyOf(result);
        List<String> numbered = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            String content = result.get(i).substring(DOCUMENT_PREFIX.length());
            numbered.add(DOCUMENT_PREFIX + "片段 " + (i + 1) + "/" + result.size() + "\n" + content);
        }
        return List.copyOf(numbered);
    }

    private static String trailingLines(String text, int count) {
        String[] lines = text.split("\\R", -1);
        int start = Math.max(0, lines.length - count);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
    }

    private static void appendOversizedLine(String line, List<String> result, StringBuilder current) {
        int offset = 0;
        while (offset < line.length()) {
            int low = offset + 1;
            int high = line.length();
            int best = offset;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (MemoryEntry.estimateTokens(DOCUMENT_PREFIX + line.substring(offset, middle))
                        <= PART_CONTENT_BUDGET) {
                    best = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (best == offset) throw new IllegalArgumentException("Embedding token budget is too small");
            String part = line.substring(offset, best);
            offset = best;
            if (offset < line.length()) result.add(DOCUMENT_PREFIX + part);
            else current.append(part);
        }
    }

    private static String normalize(String input) {
        if (input == null) return "";
        return input.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
