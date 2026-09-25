package com.codeagent.rag;

import com.codeagent.memory.MemoryEntry;

import java.nio.file.Path;
import java.util.List;

public final class RepositoryMapSelector {
    private final SqliteRetrievalIndex index;
    private final RepositoryMapBuilder builder = new RepositoryMapBuilder();

    public RepositoryMapSelector(SqliteRetrievalIndex index) { this.index = index; }

    public RepositoryMap select(Path projectRoot, String query, int tokenBudget) {
        if (tokenBudget <= 0) return new RepositoryMap("", 0, true);
        try {
            List<String> lines = builder.buildLines(index.listSymbols(projectRoot),
                    index.listRelations(projectRoot), query);
            StringBuilder result = new StringBuilder();
            boolean partial = false;
            for (String line : lines) {
                String candidate = result.isEmpty() ? line : result + "\n" + line;
                if (MemoryEntry.estimateTokens(candidate) > tokenBudget) {
                    partial = true;
                    break;
                }
                if (!result.isEmpty()) result.append('\n');
                result.append(line);
            }
            return new RepositoryMap(result.toString(), MemoryEntry.estimateTokens(result.toString()), partial);
        } catch (Exception e) {
            return new RepositoryMap("", 0, true);
        }
    }
}
