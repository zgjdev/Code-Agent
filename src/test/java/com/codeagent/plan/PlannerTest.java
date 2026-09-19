package com.codeagent.plan;

import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void createsMinimalPlanForSimpleGoalWithoutCallingLlm() throws Exception {
        Planner planner = new Planner(new FailingGLMClient());

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals("直接执行简单任务：列出当前目录的文件", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        Task task = plan.getTask("task_1");
        assertEquals(Task.TaskType.COMMAND, task.getType());
        assertEquals("列出当前目录的文件", task.getDescription());
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        planner.setProjectMemorySupplier(() -> "## CODEAGENT.md 项目记忆\n- 计划前必须读取项目规则");

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
        assertTrue(client.lastSystemPrompt.contains("计划前必须读取项目规则"));
    }

    @Test
    void parsesPlanWrappedInMarkdownFence() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                ```json
                {
                  "summary": "带围栏的计划",
                  "tasks": [
                    {
                      "id": "t1",
                      "description": "执行命令",
                      "type": "COMMAND",
                      "dependencies": []
                    }
                  ]
                }
                ```
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先执行命令再汇总结果");

        assertEquals("带围栏的计划", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        assertEquals("执行命令", plan.getTask("task_1").getDescription());
        assertEquals(Task.TaskType.COMMAND, plan.getTask("task_1").getType());
    }

    @Test
    void mapsDependencyDeclaredBeforeItsTarget() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "前向引用",
                  "tasks": [
                    {
                      "id": "b",
                      "description": "依赖后定义的任务",
                      "type": "VERIFICATION",
                      "dependencies": ["a"]
                    },
                    {
                      "id": "a",
                      "description": "先定义在这里",
                      "type": "ANALYSIS",
                      "dependencies": []
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先分析再验证项目结构");

        assertEquals(2, plan.getAllTasks().size());
        assertEquals(List.of("task_2"), plan.getTask("task_1").getDependencies());
        assertEquals("task_2", plan.getExecutionOrder().get(0));
    }

    @Test
    void fallsBackToAnalysisTypeWhenTypeFieldIsMissing() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "缺类型",
                  "tasks": [
                    {"id": "t1", "description": "第一步", "dependencies": []}
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先做第一步再继续后续步骤");

        assertEquals(Task.TaskType.ANALYSIS, plan.getTask("task_1").getType());
    }

    @Test
    void throwsWhenPlannerOutputIsNotParseableJson() {
        Planner planner = new Planner(new StubGLMClient("这不是 JSON"));

        assertThrows(IOException.class, () -> planner.createPlan("先分析再验证项目结构"));
    }

    @Test
    void parsesResourceClaimsAcceptanceCriteriaAndRequiredEvidence() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "safe write",
                  "tasks": [
                    {
                      "id": "write",
                      "description": "update service",
                      "type": "FILE_WRITE",
                      "dependencies": [],
                      "resources": {
                        "readPaths": ["src/main/java/com/acme/"],
                        "writePaths": ["src/main/java/com/acme/Service.java"],
                        "workspaceWrite": false
                      },
                      "acceptanceCriteria": ["compiles", "rollback test passes"],
                      "requiredEvidence": ["DIFF", "BUILD", "TEST", "UNKNOWN"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("analyze, update, and verify the service implementation");
        Task task = plan.getTask("task_1");

        assertEquals(List.of("src/main/java/com/acme/Service.java"),
                task.getResourceClaims().writePaths());
        assertEquals(List.of("compiles", "rollback test passes"), task.getAcceptanceCriteria());
        assertEquals(Set.of(EvidenceType.DIFF, EvidenceType.BUILD, EvidenceType.TEST),
                task.getRequiredEvidence());
    }

    @Test
    void acceptsLegacyPlannerJsonWithConservativeDefaults() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "legacy",
                  "tasks": [
                    {"id": "write", "description": "update file", "type": "FILE_WRITE", "dependencies": []}
                  ]
                }
                """);

        Task task = new Planner(client)
                .createPlan("analyze and update the legacy implementation")
                .getTask("task_1");

        assertTrue(task.getResourceClaims().workspaceWrite());
        assertTrue(task.getAcceptanceCriteria().isEmpty());
    }

    @Test
    void rejectsIllegalResourcePathFromPlanner() {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "unsafe",
                  "tasks": [
                    {
                      "id": "write",
                      "description": "escape project",
                      "type": "FILE_WRITE",
                      "dependencies": [],
                      "resources": {"writePaths": ["../outside.txt"]}
                    }
                  ]
                }
                """);

        assertThrows(IOException.class, () -> new Planner(client)
                .createPlan("analyze and update files outside the project"));
    }

    private static final class FailingGLMClient extends GLMClient {
        private FailingGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("simple goal should not call llm");
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;
        private String lastSystemPrompt = "";

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.lastSystemPrompt = messages.get(0).content();
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
