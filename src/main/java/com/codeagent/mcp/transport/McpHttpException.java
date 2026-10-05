package com.codeagent.mcp.transport;

import java.io.IOException;

/** HTTP status remains typed across the JSON-RPC IOException cause chain. */
public final class McpHttpException extends IOException {
    private final int statusCode;

    public McpHttpException(int statusCode) {
        super("MCP HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
