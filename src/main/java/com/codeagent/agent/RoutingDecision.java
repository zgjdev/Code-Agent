package com.codeagent.agent;

import com.codeagent.context.MeasuredUsage;

import java.util.Objects;
import java.util.Optional;

public record RoutingDecision(ExecutionMode mode,
                              RoutingSource source,
                              Optional<MeasuredUsage> usage) {
    public RoutingDecision {
        mode = Objects.requireNonNull(mode, "mode");
        source = Objects.requireNonNull(source, "source");
        usage = usage == null ? Optional.empty() : usage;
    }

    public static RoutingDecision explicit(ExecutionMode mode) {
        return new RoutingDecision(mode, RoutingSource.EXPLICIT, Optional.empty());
    }
}
