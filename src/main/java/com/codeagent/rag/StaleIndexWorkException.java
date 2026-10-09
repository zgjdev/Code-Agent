package com.codeagent.rag;

/** Expected cancellation of metadata work superseded by a newer file or model version. */
public final class StaleIndexWorkException extends Exception {
    public StaleIndexWorkException(String message) {
        super(message);
    }
}
