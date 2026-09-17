package com.codeagent.agent;

/**
 * 统一 Plan-and-Execute 的两个可选环节：人工计划门与步骤自动评审。
 * 两个开关串联，互不依赖。
 */
public record PipelineOptions(boolean humanPlanGate, boolean stepReview) {

    public static final PipelineOptions PLAN_PRESET = new PipelineOptions(true, false);
    public static final PipelineOptions TEAM_PRESET = new PipelineOptions(false, true);
    public static final PipelineOptions FULL_PRESET = new PipelineOptions(true, true);
}
