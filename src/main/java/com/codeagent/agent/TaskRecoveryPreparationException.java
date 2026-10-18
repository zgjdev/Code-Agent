package com.codeagent.agent;

/**
 * Nonterminal recovery preparation failure, emitted only before any task execution started.
 */
public final class TaskRecoveryPreparationException extends RuntimeException {

    public TaskRecoveryPreparationException() {
        super("Task recovery preparation unavailable");
    }
}
