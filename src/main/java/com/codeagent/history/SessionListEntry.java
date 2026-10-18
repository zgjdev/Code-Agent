package com.codeagent.history;

public record SessionListEntry(SessionSummary summary, String title, int waitingExecutionCount,
                                boolean hasUnfinishedPlan, Availability availability, String unavailableReason) {
    public enum Availability { ATTACHABLE, EMPTY, UNSAFE, CORRUPT }
    public boolean attachable() { return availability == Availability.ATTACHABLE; }
    @Override public String toString() { return "SessionListEntry[availability=" + availability + "]"; }
}
