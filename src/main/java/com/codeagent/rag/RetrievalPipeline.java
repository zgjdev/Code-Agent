package com.codeagent.rag;

import java.util.*;

/** Shared production and evaluation pipeline, with context assembled only after primary budgeting. */
public final class RetrievalPipeline {
    public Result apply(Map<RetrievalSource,List<RetrievalCandidate>> rankings, RetrievalRequest request) {
        int primaryDepth=Math.max(request.topK()*3,15);
        Map<RetrievalSource,List<RetrievalCandidate>> primaryRankings=new EnumMap<>(RetrievalSource.class);
        rankings.forEach((source,candidates)->primaryRankings.put(source,
                source==RetrievalSource.SEMANTIC_LOCAL || source==RetrievalSource.SEMANTIC_REMOTE
                        ? candidates.subList(0,Math.min(primaryDepth,candidates.size())) : candidates));
        var fused=new RetrievalFusion().fuse(primaryRankings,request.query(),primaryDepth);
        var primary=new RetrievalBudget().apply(fused,request.topK(),request.maxChars());
        var hits=new RetrievalContextAssembler().assemble(primary.hits(),rankings,request.query(),request.maxChars());
        return new Result(primary.hits(),hits,primary.partial());
    }
    public record Result(List<RetrievalHit> primaryHits,List<RetrievalHit> hits,boolean partial) {}
}
