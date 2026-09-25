package com.codeagent.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * 长期记忆的确定性等价判断器。
 *
 * <p>只处理可以零歧义判定的格式等价：同一 type/scope/project 域内，消除 Unicode
 * 宽窄、大小写、空白和普通句读差异后内容完全一致才判为重复。真正的同义改写、
 * 补充关系和事实更新由 MemoryWriteResolver + MemoryRelationClassifier 处理。</p>
 */
final class MemoryDeduplicator {
    private static final String SIGNIFICANT_PUNCTUATION = "#/@\\%_-'’!:&|^~";

    private MemoryDeduplicator() {
    }

    static boolean isDuplicate(MemoryEntry existing, MemoryEntry incoming) {
        return sameDomain(existing, incoming)
                && canonicalize(existing.getContent()).equals(canonicalize(incoming.getContent()));
    }

    static boolean sameDomain(MemoryEntry left, MemoryEntry right) {
        if (left == null || right == null || left.getType() != right.getType()) {
            return false;
        }

        String leftScope = LongTermMemory.scopeOf(left);
        String rightScope = LongTermMemory.scopeOf(right);
        if (!leftScope.equals(rightScope)) {
            return false;
        }

        if ("project".equals(leftScope)) {
            String leftProject = left.getMetadata().get("project");
            String rightProject = right.getMetadata().get("project");
            return leftProject != null
                    && !leftProject.isBlank()
                    && Objects.equals(leftProject, rightProject);
        }
        return true;
    }

    static String canonicalize(String content) {
        String normalized = Normalizer.normalize(
                content == null ? "" : content,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        int[] codePoints = normalized.codePoints().toArray();
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isLetterOrDigit(codePoint)) {
                canonical.appendCodePoint(codePoint);
            } else if (isSignificantMarker(codePoints, index)) {
                canonical.appendCodePoint(codePoint);
            }
        }
        return canonical.toString();
    }

    private static boolean isSignificantMarker(int[] codePoints, int index) {
        int codePoint = codePoints[index];
        int type = Character.getType(codePoint);
        if (type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL) {
            return true;
        }
        if (codePoint == '.') {
            return hasLetterOrDigitBefore(codePoints, index)
                    && hasLetterOrDigitAfter(codePoints, index);
        }
        if ((codePoint == '\'' || codePoint == '’')
                && !(hasLetterOrDigitBefore(codePoints, index)
                && hasLetterOrDigitAfter(codePoints, index))) {
            return false;
        }
        return SIGNIFICANT_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    private static boolean hasLetterOrDigitBefore(int[] codePoints, int index) {
        return index > 0 && Character.isLetterOrDigit(codePoints[index - 1]);
    }

    private static boolean hasLetterOrDigitAfter(int[] codePoints, int index) {
        return index + 1 < codePoints.length
                && Character.isLetterOrDigit(codePoints[index + 1]);
    }
}
