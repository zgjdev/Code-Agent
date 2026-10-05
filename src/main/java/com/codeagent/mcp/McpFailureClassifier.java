package com.codeagent.mcp;

import com.codeagent.mcp.transport.McpHttpException;
import com.codeagent.tool.ToolOutput.FailureKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.codeagent.mcp.jsonrpc.JsonRpcException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/** Fail closed: only known infrastructure failures are eligible for fallback. */
final class McpFailureClassifier {
    static FailureKind classify(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return FailureKind.CANCELLED;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpException http) {
                return http.statusCode() >= 500 && http.statusCode() <= 599
                        ? FailureKind.BACKEND_UNAVAILABLE : FailureKind.EXECUTION_ERROR;
            }
            if (cause instanceof JsonRpcException || cause instanceof JsonProcessingException)
                return FailureKind.EXECUTION_ERROR;
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException
                    || cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof SocketException || cause instanceof EOFException)
                return FailureKind.BACKEND_UNAVAILABLE;
            if (cause instanceof InterruptedException) return FailureKind.CANCELLED;
            // OkHttp call timeout is InterruptedIOException (without a thread interrupt).
            if (cause instanceof InterruptedIOException) return FailureKind.BACKEND_UNAVAILABLE;
        }
        return FailureKind.EXECUTION_ERROR;
    }
}
