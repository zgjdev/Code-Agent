package com.codeagent.runtime.execution;

/** Startup admission for old nonterminal work; never clears an existing durable user gate. */
public enum RecoveryPolicy { AUTOMATIC, WAIT_FOR_USER }
