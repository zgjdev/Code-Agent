package com.codeagent.search;

import java.util.List;

public record CodeSearchResult(String engine, List<GrepMatch> matches,
                               boolean partial, String partialReason) {
    public CodeSearchResult { matches = List.copyOf(matches); }
}
