package com.codeagent.cli;

import java.nio.file.Path;

/** CLI compatibility facade for the shared execution input expansion. */
final class LocalPathMentionExpander {
    private final com.codeagent.runtime.execution.LocalPathMentionExpander delegate;
    LocalPathMentionExpander(Path root) { delegate = new com.codeagent.runtime.execution.LocalPathMentionExpander(root); }
    String expand(String input) { return delegate.expand(input); }
}