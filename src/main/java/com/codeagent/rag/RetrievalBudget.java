package com.codeagent.rag;

import java.util.ArrayList;
import java.util.List;

public final class RetrievalBudget {
    public Result apply(List<RetrievalHit> input, int topK, int maxChars) {
        List<RetrievalHit> output = new ArrayList<>();
        int used = 0;
        boolean partial = input.size() > topK;
        for (RetrievalHit hit : input) {
            if (output.size() >= topK) { partial = true; break; }
            int available = maxChars - used;
            if (available <= 0) { partial = true; break; }
            String content = fitLines(hit.content(), available);
            if (content.length() < hit.content().length()) partial = true;
            if (content.isEmpty() && output.isEmpty()) content = hit.content().substring(0,
                    Math.min(maxChars, hit.content().length()));
            if (content.isEmpty()) break;
            output.add(new RetrievalHit(hit.filePath(), hit.startLine(), hit.endLine(),
                    hit.chunkType(), hit.symbol(), content, hit.score(), hit.sources()));
            used += content.length();
            if (content.length() < hit.content().length()) break;
        }
        return new Result(List.copyOf(output), partial);
    }

    private static String fitLines(String content, int available) {
        if (content.length() <= available) return content;
        int newline = content.lastIndexOf('\n', available);
        return newline > 0 ? content.substring(0, newline) : "";
    }

    public record Result(List<RetrievalHit> hits, boolean partial) {}
}
