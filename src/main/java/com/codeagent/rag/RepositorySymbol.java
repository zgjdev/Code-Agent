package com.codeagent.rag;

public record RepositorySymbol(String filePath, String symbolId, String qualifiedName,
                               String simpleName, String signature, String symbolKind,
                               int startLine, int endLine) {}
