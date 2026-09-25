package com.codeagent.search;

import java.util.List;

public record GrepMatch(String file, int lineNumber, List<ContextLine> context) {
    public GrepMatch { context = List.copyOf(context); }
}
