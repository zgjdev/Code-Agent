package com.codeagent.rag;

import com.codeagent.rag.stage.CodeRetrieverStage;
import com.codeagent.rag.stage.SemanticRetriever;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RetrievalStageRunner {
    public Result run(List<CodeRetrieverStage> stages, RetrievalContext context) {
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        Map<RetrievalSource, Long> durations = new EnumMap<>(RetrievalSource.class);
        Map<RetrievalSource, Integer> hits = new EnumMap<>(RetrievalSource.class);
        List<String> reasons = new ArrayList<>();
        for (CodeRetrieverStage stage : stages) {
            RetrievalSource source = stage instanceof SemanticRetriever semantic
                    ? semantic.source(context) : stage.source();
            long started = System.nanoTime();
            try {
                List<RetrievalCandidate> result = stage.retrieve(context);
                rankings.put(source, result);
                hits.put(source, result.size());
            } catch (Exception e) {
                rankings.put(source, List.of());
                hits.put(source, 0);
                reasons.add("stage_" + source.name().toLowerCase() + "_failed");
            } finally {
                durations.put(source, (System.nanoTime() - started) / 1_000_000);
            }
        }
        return new Result(rankings, durations, hits, reasons);
    }

    public record Result(Map<RetrievalSource, List<RetrievalCandidate>> rankings,
                         Map<RetrievalSource, Long> durations,
                         Map<RetrievalSource, Integer> hits, List<String> degradedReasonCodes) {
        public Result {
            rankings = Map.copyOf(rankings);
            durations = Map.copyOf(durations);
            hits = Map.copyOf(hits);
            degradedReasonCodes = List.copyOf(degradedReasonCodes);
        }
    }
}
