package com.codeagent.plan;

import com.codeagent.llm.GLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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
    void priorConversationForcesPlannerAndCurrentGoalAppearsOnce() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "上下文任务",
                  "tasks": [
                    {
                      "id": "t1",
                      "description": "列出之前讨论的目录",
                      "type": "COMMAND",
                      "dependencies": []
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        String goal = "列出当前目录的文件";

        ExecutionPlan plan = planner.createPlan(new Planner.PlannerRequest(
                goal,
                "[历史会话上下文]\n[User] 刚才我们在看 src/main"));

        assertEquals("上下文任务", plan.getSummary());
        assertEquals(1, client.calls);
        assertTrue(client.lastUserPrompt.contains("[历史会话上下文]"));
        assertTrue(client.lastUserPrompt.contains("刚才我们在看 src/main"));
        assertEquals(1, occurrences(client.lastUserPrompt, goal));
    }

    @Test
    void repairsMalformedPlannerJsonOnce() throws Exception {
        SequenceGLMClient client = new SequenceGLMClient(
                "not json",
                "{\"summary\":\"修复后计划\",\"tasks\":["
                        + "{\"id\":\"t1\",\"description\":\"执行命令\","
                        + "\"type\":\"COMMAND\",\"dependencies\":[]}]}");
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先执行命令然后验证结果");

        assertEquals("修复后计划", plan.getSummary());
        assertEquals(2, client.calls);
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
    }

    @Test
    void repairsExplicitUnknownTaskTypeInsteadOfSilentlyDowngrading() throws Exception {
        SequenceGLMClient client = new SequenceGLMClient(
                "{\"summary\":\"bad\",\"tasks\":[{\"id\":\"t1\","
                        + "\"description\":\"执行任务\",\"type\":\"MAGIC\",\"dependencies\":[]}]}",
                "{\"summary\":\"fixed\",\"tasks\":[{\"id\":\"t1\","
                        + "\"description\":\"执行任务\",\"type\":\"COMMAND\",\"dependencies\":[]}]}");
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先执行任务然后验证结果");

        assertEquals("fixed", plan.getSummary());
        assertEquals(Task.TaskType.COMMAND, plan.getTask("task_1").getType());
        assertEquals(2, client.calls);
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
                      "requiredEvidence": ["DIFF", "BUILD", "TEST"]
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

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":123,\"description\":\"step\"}",
            "{\"id\":\"t1\",\"description\":123}",
            "{\"id\":true,\"description\":\"step\"}",
            "{\"id\":\"t1\",\"description\":false}"
    })
    void rejectsNonTextualTaskIdentityFields(String taskJson) {
        assertInvalidPlannerOutput("{\"summary\":\"plan\",\"tasks\":[" + taskJson + "]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"acceptanceCriteria\":\"compiles\"",
            "\"acceptanceCriteria\":{}",
            "\"acceptanceCriteria\":null",
            "\"acceptanceCriteria\":[\"compiles\",123]",
            "\"acceptanceCriteria\":[\"compiles\",false]",
            "\"acceptanceCriteria\":[\"compiles\",null]",
            "\"acceptanceCriteria\":[\"compiles\",{}]"
    })
    void rejectsInvalidAcceptanceCriteriaWithoutDroppingMembers(String fields) {
        assertInvalidPlannerOutput(planWithTaskFields(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"requiredEvidence\":\"TEST\"",
            "\"requiredEvidence\":{}",
            "\"requiredEvidence\":null",
            "\"requiredEvidence\":[\"DIFF\",123]",
            "\"requiredEvidence\":[\"DIFF\",false]",
            "\"requiredEvidence\":[\"DIFF\",null]",
            "\"requiredEvidence\":[\"DIFF\",{}]",
            "\"requiredEvidence\":[\"DIFF\",\"UNKNOWN\"]",
            "\"requiredEvidence\":[\"diff\"]",
            "\"requiredEvidence\":[\" TEST \"]",
            "\"requiredEvidence\":[\"\"]"
    })
    void rejectsInvalidRequiredEvidenceWithoutDroppingMembers(String fields) {
        assertInvalidPlannerOutput(planWithTaskFields(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"type\":null",
            "\"type\":123",
            "\"type\":\"command\"",
            "\"type\":\" COMMAND \"",
            "\"type\":\"\"",
            "\"type\":\"PLANNING\"",
            "\"dependencies\":null",
            "\"dependencies\":\"t1\"",
            "\"dependencies\":[123]",
            "\"dependencies\":[null]"
    })
    void rejectsInvalidPresentTypeAndDependencies(String fields) {
        assertInvalidPlannerOutput(planWithTaskFields(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"resources\":null",
            "\"resources\":[]",
            "\"resources\":{\"workspaceWrite\":\"false\"}",
            "\"resources\":{\"workspaceWrite\":0}",
            "\"resources\":{\"workspaceWrite\":null}",
            "\"resources\":{\"workspaceWrite\":{}}",
            "\"resources\":{\"readPaths\":null}",
            "\"resources\":{\"writePaths\":null}",
            "\"resources\":{\"readPaths\":\"src\"}",
            "\"resources\":{\"writePaths\":[false]}",
            "\"resources\":{\"readPaths\":[\"src/\",123]}"
    })
    void rejectsInvalidPresentResourceFields(String fields) {
        assertInvalidPlannerOutput(planWithTaskFields(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"summary\":\"plan\",\"tasks\":[{\"id\":\"t1\",\"description\":\"step\"}],\"extra\":true}",
            "{\"summary\":\"plan\",\"tasks\":[{\"id\":\"t1\",\"description\":\"step\",\"extra\":true}]}",
            "{\"summary\":\"plan\",\"tasks\":[{\"id\":\"t1\",\"description\":\"step\",\"resources\":{\"extra\":true}}]}"
    })
    void rejectsAdditionalPropertiesAtEveryObjectLevel(String content) {
        assertInvalidPlannerOutput(content);
    }

    @Test
    void repairsInvalidEvidenceOnceAndPreservesCorrectedRequirements() throws Exception {
        SequenceGLMClient client = new SequenceGLMClient(
                planWithTaskFields("\"requiredEvidence\":[\"DIFF\",\"UNKNOWN\"]"),
                planWithTaskFields("\"requiredEvidence\":[\"DIFF\",\"TEST\"]"));

        Task task = new Planner(client).createPlan("analyze and verify the implementation")
                .getTask("task_1");

        assertEquals(Set.of(EvidenceType.DIFF, EvidenceType.TEST), task.getRequiredEvidence());
        assertEquals(2, client.calls);
    }

    @Test
    void acceptsAllOmittedLegacyFieldsWithConservativeDefaults() throws Exception {
        StubGLMClient client = new StubGLMClient(
                "{\"summary\":\"legacy\",\"tasks\":[{\"id\":\"t1\",\"description\":\"step\"}]}");

        Task task = new Planner(client).createPlan("analyze and verify the implementation")
                .getTask("task_1");

        assertEquals(Task.TaskType.ANALYSIS, task.getType());
        assertTrue(task.getDependencies().isEmpty());
        assertEquals(TaskResourceClaims.conservativeDefault(Task.TaskType.ANALYSIS), task.getResourceClaims());
        assertTrue(task.getAcceptanceCriteria().isEmpty());
        assertTrue(task.getRequiredEvidence().isEmpty());
        assertEquals(1, client.calls);
    }

    @Test
    void acceptsSchemaEnumsAndEmptyOptionalCollections() throws Exception {
        StubGLMClient client = new StubGLMClient(planWithTaskFields(
                "\"type\":\"VERIFICATION\",\"dependencies\":[],"
                        + "\"resources\":{\"readPaths\":[],\"writePaths\":[],\"workspaceWrite\":true},"
                        + "\"acceptanceCriteria\":[],"
                        + "\"requiredEvidence\":[\"DIFF\",\"BUILD\",\"TEST\",\"LSP\",\"TOOL_RESULT\"]"));

        Task task = new Planner(client).createPlan("analyze and verify the implementation")
                .getTask("task_1");

        assertEquals(Task.TaskType.VERIFICATION, task.getType());
        assertTrue(task.getResourceClaims().workspaceWrite());
        assertTrue(task.getAcceptanceCriteria().isEmpty());
        assertEquals(Set.of(EvidenceType.values()), task.getRequiredEvidence());
        assertEquals(1, client.calls);
    }

    private static String planWithTaskFields(String fields) {
        return "{\"summary\":\"plan\",\"tasks\":[{\"id\":\"t1\",\"description\":\"step\","
                + fields + "}]}";
    }

    private static void assertInvalidPlannerOutput(String content) {
        StubGLMClient client = new StubGLMClient(content);

        assertThrows(IOException.class, () -> new Planner(client)
                .createPlan("analyze and verify the implementation"));
        assertEquals(2, client.calls);
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while (text != null && value != null && !value.isEmpty()) {
            int index = text.indexOf(value, offset);
            if (index < 0) break;
            count++;
            offset = index + value.length();
        }
        return count;
    }

    private static final class SequenceGLMClient extends GLMClient {
        private final Queue<String> contents = new ArrayDeque<>();
        private int calls;

        private SequenceGLMClient(String... contents) {
            super("test-key");
            this.contents.addAll(List.of(contents));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            calls++;
            String content = contents.isEmpty() ? "" : contents.remove();
            return new ChatResponse("assistant", content, null, 100, 20);
        }
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
        private String lastUserPrompt = "";
        private int calls;

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            calls++;
            this.lastSystemPrompt = messages.get(0).content();
            this.lastUserPrompt = messages.get(messages.size() - 1).content();
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
