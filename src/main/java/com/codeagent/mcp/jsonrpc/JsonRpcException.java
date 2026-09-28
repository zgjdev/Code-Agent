package com.codeagent.mcp.jsonrpc;

public class JsonRpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
