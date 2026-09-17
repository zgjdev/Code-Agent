package com.codeagent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptTest {

    @Test
    void keepsTheTasksContractThatPlannerParsePlanReads() {
        String prompt = assemble();

        assertTrue(prompt.contains("\"tasks\""), "Planner.parsePlan 只读 tasks 字段：\n" + prompt);
        assertFalse(prompt.contains("\"steps\""), "计划提示词不得要求模型输出 steps：\n" + prompt);
    }

    @Test
    void carriesTheParallelDispatchRule() {
        String prompt = assemble();

        assertTrue(prompt.contains("编排器会把同一轮就绪的任务并行分配"),
                "planner 提示词必须指导模型把可并行的任务留空 dependencies：\n" + prompt);
    }

    private static String assemble() {
        return PromptAssembler.createDefault().assemble(PromptMode.PLANNER, PromptContext.empty());
    }
}
