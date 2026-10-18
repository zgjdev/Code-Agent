package com.codeagent.tool;

import com.codeagent.model.recovery.TaskObservationContext;
import com.codeagent.tool.ToolRegistry.ToolInvocation;
import com.codeagent.tool.ToolRegistry.ToolExecutionResult;

/**
 * Scoped durable lifecycle. A start failure must prevent invocation; result failure means unknown.
 */
public interface TaskToolObserver {

    void started(TaskObservationContext context, ToolInvocation invocation) throws Exception;

    void completed(TaskObservationContext context, ToolInvocation invocation, ToolExecutionResult result) throws Exception;
}
