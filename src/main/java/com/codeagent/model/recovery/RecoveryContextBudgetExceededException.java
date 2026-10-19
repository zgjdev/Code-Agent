package com.codeagent.model.recovery;

/** No model request is safe: even provenance plus complete omission counts cannot fit. */
public final class RecoveryContextBudgetExceededException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final int limitTokens, requiredCoreTokens, omittedExecutionCount, omittedPlanCount,
            omittedTaskCount, omittedFactCount, omittedUnknownCount, omittedWarningCount;
    public RecoveryContextBudgetExceededException(int limitTokens, int requiredCoreTokens, int executions,
            int plans, int tasks, int facts, int unknowns, int warnings) {
        super("Recovery observation budget insufficient: limit=" + limitTokens + ", core=" + requiredCoreTokens
                + ", omittedExecutions=" + executions + ", omittedPlans=" + plans + ", omittedTasks=" + tasks
                + ", omittedFacts=" + facts + ", omittedUnknowns=" + unknowns + ", omittedWarnings=" + warnings);
        this.limitTokens=limitTokens; this.requiredCoreTokens=requiredCoreTokens;
        omittedExecutionCount=executions;omittedPlanCount=plans;omittedTaskCount=tasks;
        omittedFactCount=facts;omittedUnknownCount=unknowns;omittedWarningCount=warnings;
    }
    public int limitTokens(){return limitTokens;} public int requiredCoreTokens(){return requiredCoreTokens;}
    public int omittedExecutionCount(){return omittedExecutionCount;} public int omittedPlanCount(){return omittedPlanCount;}
    public int omittedTaskCount(){return omittedTaskCount;} public int omittedFactCount(){return omittedFactCount;}
    public int omittedUnknownCount(){return omittedUnknownCount;} public int omittedWarningCount(){return omittedWarningCount;}
}
