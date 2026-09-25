package com.codeagent.rag;

public record IndexedSymbol(
        String symbolId,
        String qualifiedName,
        String simpleName,
        String signature,
        String symbolKind,
        String ownerSymbolId,
        int startLine,
        int endLine
) {}
