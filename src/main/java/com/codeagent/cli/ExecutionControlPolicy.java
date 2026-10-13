package com.codeagent.cli;

final class ExecutionControlPolicy {
    enum Category {
        TASK_INPUT,
        READ_ONLY,
        EXECUTION_CONTROL,
        SESSION_MUTATION,
        RUNTIME_MUTATION,
        DENIED
    }

    private ExecutionControlPolicy() {
    }

    static Category classify(CliCommandParser.CommandType type) {
        return switch (type) {
            case NONE, SWITCH_PLAN, SWITCH_REACT -> Category.TASK_INPUT;
            case SESSIONS, MEMORY_STATUS, MEMORY_LIST, MEMORY_SEARCH,
                 SEARCH_CODE, GRAPH_QUERY, CONTEXT_STATUS, POLICY_STATUS,
                 AUDIT_TAIL, MCP_LIST, MCP_LOGS, MCP_RESOURCES, MCP_PROMPTS,
                 SKILL_LIST, SKILL_SHOW -> Category.READ_ONLY;
            case CANCEL, EXIT, TASK -> Category.EXECUTION_CONTROL;
            case CLEAR, COMPACT, RESUME_SESSION, NEW_SESSION, HISTORY_CLEAR,
                 MEMORY_CLEAR, MEMORY_DELETE, MEMORY_SAVE,
                 PLAN_RESUME, PLAN_ABANDON -> Category.SESSION_MUTATION;
            case INIT_PROJECT_MEMORY, SWITCH_MODEL, SWITCH_HITL,
                 SNAPSHOT, RESTORE_SNAPSHOT, MCP_RESTART, MCP_DISABLE, MCP_ENABLE,
                 BROWSER, SKILL_ON, SKILL_OFF, SKILL_RELOAD,
                 BETTER_HARNESS, CONFIG, EXPORT -> Category.RUNTIME_MUTATION;
            case UNKNOWN_COMMAND -> Category.DENIED;
        };
    }

    static boolean allowedWhileBusy(CliCommandParser.CommandType type) {
        return switch (classify(type)) {
            case TASK_INPUT, READ_ONLY, EXECUTION_CONTROL -> true;
            case SESSION_MUTATION, RUNTIME_MUTATION, DENIED -> false;
        };
    }
}
