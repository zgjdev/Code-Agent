package com.codeagent.tool;

import com.codeagent.model.recovery.TaskObservationContext;
import java.util.Objects;

public record TaskToolObservation(TaskObservationContext context, TaskToolObserver observer) {

    public TaskToolObservation {
        Objects.requireNonNull(context);
        Objects.requireNonNull(observer);
    }

    @Override
    public String toString() {
        return "TaskToolObservation[ordinal=" + context.callOrdinal() + "]";
    }
}
