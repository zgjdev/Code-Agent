package com.codeagent.rag;

import java.nio.file.Path;

public record IndexRefreshRequest(Path projectRoot, boolean rebuild) {}
