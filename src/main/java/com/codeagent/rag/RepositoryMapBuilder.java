package com.codeagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryMapBuilder {
    public List<String> buildLines(List<RepositorySymbol> symbols,
                                   List<RepositoryRelation> relations, String query) {
        Map<String, Integer> indegree = new HashMap<>();
        for (RepositoryRelation relation : relations) {
            if (relation.resolved()) indegree.merge(relation.toSymbolId(), 1, Integer::sum);
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase();
        List<RepositorySymbol> ordered = new ArrayList<>(symbols);
        ordered.sort(Comparator
                .comparing((RepositorySymbol symbol) -> !matches(symbol, normalizedQuery))
                .thenComparing((RepositorySymbol symbol) -> -indegree.getOrDefault(symbol.symbolId(), 0))
                .thenComparing(RepositorySymbol::filePath)
                .thenComparingInt(RepositorySymbol::startLine)
                .thenComparing(RepositorySymbol::qualifiedName));
        List<String> lines = new ArrayList<>();
        String previousFile = null;
        for (RepositorySymbol symbol : ordered) {
            if (!symbol.filePath().equals(previousFile)) {
                lines.add(symbol.filePath());
                previousFile = symbol.filePath();
            }
            String displayName = symbol.signature() == null || symbol.signature().isBlank()
                    ? symbol.qualifiedName() : symbol.signature();
            lines.add("  " + symbol.symbolKind().toLowerCase() + " " + displayName
                    + " [" + symbol.startLine() + "-" + symbol.endLine() + "]");
            relations.stream().filter(relation -> relation.fromSymbolId().equals(symbol.symbolId()))
                    .sorted(Comparator.comparing(RepositoryRelation::relationType)
                            .thenComparing(RepositoryRelation::targetText))
                    .forEach(relation -> lines.add("    -> " + relation.relationType() + " "
                            + relation.targetText() + (relation.resolved() ? "" : " (unresolved)")));
        }
        return List.copyOf(lines);
    }

    private static boolean matches(RepositorySymbol symbol, String query) {
        return !query.isBlank() && (symbol.simpleName().toLowerCase().contains(query)
                || query.contains(symbol.simpleName().toLowerCase()));
    }
}
