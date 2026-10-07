package com.codeagent.rag;

import java.util.*;

/** Appends whole indexed siblings using spare budget; primary evidence and result order are immutable. */
public final class RetrievalContextAssembler {
    private static final int MAX_COMPANIONS_PER_FILE = 2;

    public List<RetrievalHit> assemble(List<RetrievalHit> primary,
            Map<RetrievalSource,List<RetrievalCandidate>> rankings, String query, int maxChars) {
        if (primary.isEmpty()) return primary;
        List<RetrievalHit> output = new ArrayList<>(primary);
        long used = primary.stream().mapToLong(h -> h.content().length()).sum();
        Map<String,Integer> owner = new LinkedHashMap<>(), counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i=0;i<primary.size();i++) {
            var hit=primary.get(i); owner.putIfAbsent(hit.filePath(),i);
            seen.add(key(hit.filePath(),hit.startLine(),hit.endLine(),hit.symbol()));
        }
        for (var source:List.of(RetrievalSource.SEMANTIC_LOCAL,RetrievalSource.SEMANTIC_REMOTE)) {
            for (var candidate:rankings.getOrDefault(source,List.of())) {
                String file=candidate.filePath(); Integer position=owner.get(file);
                String key=key(file,candidate.startLine(),candidate.endLine(),candidate.symbol());
                if (position==null || seen.contains(key) || counts.getOrDefault(file,0)>=MAX_COMPANIONS_PER_FILE
                        || candidate.content().isBlank()) continue;
                if (output.stream().anyMatch(h -> h.filePath().equals(file) && h.content().contains(candidate.content()))) continue;
                String addition="\n// Indexed context at lines "+candidate.startLine()+"-"+candidate.endLine()+"\n"+candidate.content();
                if (used+addition.length()>maxChars) continue;
                var hit=output.get(position); Set<RetrievalSource> sources=new LinkedHashSet<>(hit.sources()); sources.add(source);
                output.set(position,new RetrievalHit(file,Math.min(hit.startLine(),candidate.startLine()),
                        Math.max(hit.endLine(),candidate.endLine()),"context",hit.symbol(),hit.content()+addition,hit.score(),sources));
                used+=addition.length(); seen.add(key); counts.merge(file,1,Integer::sum);
            }
        }
        return List.copyOf(output);
    }
    private static String key(String file,int start,int end,String symbol) {
        return file+':'+start+':'+end+':'+symbol;
    }
}
