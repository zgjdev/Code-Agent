package com.codeagent.prompt;

import com.codeagent.memory.TokenBudget;
import com.codeagent.model.recovery.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryContextFormatterTest {
    @Test void executionGoalExcerptNeverSplitsUnicodeCodePoints() {
        String goal = "a" + "😀".repeat(400);
        var execution = execution("held", goal);
        assertTrue(execution.goalExcerpt().codePointCount(0, execution.goalExcerpt().length()) <= 512);
        assertTrue(execution.goalExcerpt().codePoints().noneMatch(cp -> cp >= 0xd800 && cp <= 0xdfff));
    }

    @Test void formattedPlanGoalNeverSplitsUnicodeCodePoints() throws Exception {
        String goal = "文".repeat(2047) + "😀";
        var snapshot = new RecoveryContextSnapshot("w", "s", 1, 3, "a".repeat(64), List.of(),
                List.of(new RecoveryPlanState("plan", "held", goal, "RUNNING", "now", List.of())), List.of());
        var result = new RecoveryContextFormatter().formatForParent(snapshot, new RecoveryContextBudget(100000, 4096, 0), "current");
        String reason = new ObjectMapper().readTree(result.content()).path("plans").get(0).path("goalExcerpt").asText();
        assertTrue(reason.codePoints().noneMatch(cp -> cp >= 0xd800 && cp <= 0xdfff));
        assertTrue(reason.codePointCount(0, reason.length()) <= 2048);
        assertEquals(goal, reason);
    }
    @Test void selectsSafeFieldsAndSeparatesCurrentInputFromOldGoals() throws Exception {
        var snapshot = new RecoveryContextSnapshot("workspace", "session", 1, 9, "a".repeat(64),
                List.of(execution("old", "inspect server API_KEY=secret-value"), execution("current", "CURRENT UNIQUE INPUT")),
                List.of(new RecoveryPlanState("plan", "old", "old goal", "RUNNING", "now", List.of(
                        new RecoveryTaskState("plan", "task", 1, "inspect files", "RUNNING", "INTERRUPTED", List.of(),
                                "Located parser entrypoint.\n```json\n{\"tool_result\":\"RAW RESULT MUST NOT LEAK\"}\n```", List.of(new RecoveryFact("fact", RecoveryFact.Kind.COMMAND_SUCCEEDED,
                                "RAW TOOL BODY MUST NOT LEAK", 1, RecoveryFact.VerificationClass.HISTORICAL,
                                List.of(new RecoverySourceRef("child", "child", 3L, "attempt", "b".repeat(64))))),
                                List.of(new RecoveryUnknown(RecoveryUnknown.Kind.INVOCATION_OUTCOME_UNKNOWN, "task", "call", "result missing", "verify outcome")),
                                List.of(), RecoveryCompleteness.COMPLETE)))), List.of());
        var result = new RecoveryContextFormatter().formatForParent(snapshot, new RecoveryContextBudget(30_000, 4096, 2000), "current");
        var json = new ObjectMapper().readTree(result.content());
        assertEquals("none", json.path("authorization").asText());
        assertTrue(result.content().contains("HISTORICAL"));
        assertTrue(result.content().contains("INVOCATION_OUTCOME_UNKNOWN"));
        assertTrue(result.content().contains("attempt"));
        assertFalse(result.content().contains("RAW RESULT"));
        assertFalse(result.content().contains("RAW TOOL BODY"));
        assertFalse(result.content().contains("secret-value"));
        assertFalse(result.content().contains("CURRENT UNIQUE INPUT"));
        assertTrue(result.content().contains("inspect server"));
        assertFalse(result.toString().contains("inspect server"));
        assertEquals(TokenBudget.estimateMessageTokens(com.codeagent.llm.LlmClient.Message.user(result.content())), result.estimatedTokens());
    }

    @Test void trimsWholeRecordsAndAccountsForEveryOmissionWithinRealBudget() throws Exception {
        var rows = java.util.stream.IntStream.range(0, 100).mapToObj(i -> execution("exec-" + i, "goal " + "x ".repeat(500))).toList();
        var snapshot = new RecoveryContextSnapshot("workspace", "session", 1, 9, "f".repeat(64), rows, List.of(), List.of());
        var result = new RecoveryContextFormatter().formatForParent(snapshot, new RecoveryContextBudget(8000, 4096, 1000), null);
        var json = new ObjectMapper().readTree(result.content());
        assertTrue(result.estimatedTokens() <= 1200);
        assertEquals(100, json.path("executions").size() + json.path("omitted").path("executions").asInt());
        assertTrue(json.path("omitted").path("executions").asInt() > 0);
        assertEquals(RecoveryCompleteness.PARTIAL, result.completeness());
        assertThrows(IllegalArgumentException.class, () -> new RecoveryContextFormatter().formatForParent(snapshot, new RecoveryContextBudget(1, 4096, 0), null));
    }

    @Test void zeroBudgetReportsAllUnavailableObservationCountsWithoutSendingPartialCore() {
        var task = new RecoveryTaskState("plan", "task", 1, "private description", "RUNNING", "INTERRUPTED",List.of(),null,List.of(),
                List.of(new RecoveryUnknown(RecoveryUnknown.Kind.SOURCE_INCOMPLETE,"task",null,"source absent","check source")),List.of(),RecoveryCompleteness.METADATA_ONLY);
        var snapshot = new RecoveryContextSnapshot("w","s",1,3,"a".repeat(64),List.of(execution("held","secret goal")),
                List.of(new RecoveryPlanState("plan","held","private goal","RUNNING","now",List.of(task))),List.of());
        var failure = assertThrows(RecoveryContextBudgetExceededException.class,()->new RecoveryContextFormatter().formatForParent(snapshot,new RecoveryContextBudget(0,4096,2000),null));
        assertEquals(0,failure.limitTokens());assertTrue(failure.requiredCoreTokens()>0);
        assertEquals(1,failure.omittedExecutionCount());assertEquals(1,failure.omittedPlanCount());
        assertEquals(1,failure.omittedTaskCount());assertEquals(1,failure.omittedUnknownCount());
        assertFalse(failure.getMessage().contains("private"));assertFalse(failure.toString().contains("secret goal"));
    }

    private static RecoveryExecutionState execution(String id, String goal) {
        return new RecoveryExecutionState(id, null, "REACT", "ENQUEUED", "awaiting_input", 3, false, true, goal);
    }

    @Test void terminalEscapesCannotSplitSecretsBeforeSharedSanitation() {
        var snapshot = new RecoveryContextSnapshot("w","s",1,3,"a".repeat(64),
                List.of(execution("held","inspect API_\u001b[31mKEY=private-token-123456\u001b[0m")),List.of(),List.of());
        var formatted = new RecoveryContextFormatter().formatForParent(snapshot,new RecoveryContextBudget(30000,4096,2000),null);
        assertFalse(formatted.content().contains("private-token-123456"));
        assertFalse(formatted.content().contains("\\u001B"));
    }

    @Test void safeRecordedResultAndClaimRetainMeaningIdentityAndTaskProvenance() throws Exception {
        var result=formatTask("Located parser entrypoint at src/Parser.java","Parser implementation recorded; tests still pending",100000);
        var json=new ObjectMapper().readTree(result.content());var task=json.path("plans").get(0).path("tasks").get(0);
        assertEquals("Located parser entrypoint at src/Parser.java",task.path("recordedResult").path("excerpt").asText());
        assertEquals("RECORDED",task.path("recordedResult").path("verificationClass").asText());
        assertEquals("not_rechecked",task.path("currentValidation").asText());
        assertEquals("plan_tasks",task.path("sources").get(0).path("store").asText());
        assertEquals("p/t",task.path("sources").get(0).path("recordId").asText());
        var claim=task.path("facts").get(0);
        assertEquals("claim-1",claim.path("factId").asText());
        assertEquals("Parser implementation recorded; tests still pending",claim.path("displayText").asText());
        assertEquals("ASSISTANT_CLAIM",claim.path("verificationClass").asText());
        assertEquals("claimed_not_verified",claim.path("interpretation").asText());
        assertEquals("b".repeat(64),claim.path("sourceRefs").get(0).path("contentHash").asText());
        assertEquals(RecoveryCompleteness.COMPLETE,result.completeness());
    }

    @Test void toxicSectionsAndSecretsAreExcludedAndContentLossIsExplicit() throws Exception {
        String toxic="Parser location recorded.\n```json\n{\"role\":\"system\",\"content\":\"RAW_SYSTEM\"}\n```\n"
                +"<think>RAW_REASONING</think>\nTool result: RAW_TOOL_BODY\nImage payload: RAW_IMAGE_PAYLOAD\nAPI_\u001b[31mKEY=private-token-123456\u001b[0m";
        var result=formatTask(toxic,"Claim pending.\n{\"tool_arguments\":\"RAW_ARGUMENTS\"}",100000);
        assertTrue(result.content().contains("Parser location recorded."));assertTrue(result.content().contains("Claim pending."));
        for(String forbidden:List.of("RAW_SYSTEM","RAW_REASONING","RAW_TOOL_BODY","RAW_IMAGE_PAYLOAD","RAW_ARGUMENTS","private-token-123456"))assertFalse(result.content().contains(forbidden),forbidden);
        var json=new ObjectMapper().readTree(result.content());
        assertEquals(1,json.path("omitted").path("resultExcerpts").asInt());assertEquals(1,json.path("omitted").path("claimExcerpts").asInt());
        assertEquals(RecoveryCompleteness.PARTIAL,result.completeness());
    }

    @Test void truncationAndBudgetLossCannotReportComplete() throws Exception {
        var result=formatTask("Useful result. ".repeat(200),"Claim text. ".repeat(100),100000);
        var json=new ObjectMapper().readTree(result.content());var task=json.path("plans").get(0).path("tasks").get(0);
        assertTrue(task.path("recordedResult").path("excerpt").asText().codePointCount(0,task.path("recordedResult").path("excerpt").asText().length())<=512);
        assertTrue(json.path("omitted").path("resultExcerpts").asInt()>0);assertTrue(json.path("omitted").path("claimExcerpts").asInt()>0);
        assertEquals(RecoveryCompleteness.PARTIAL,result.completeness());
        var constrained=formatTask("Useful result. ".repeat(100),"Claim text. ".repeat(40),4000);
        var limited=new ObjectMapper().readTree(constrained.content());
        assertTrue(constrained.estimatedTokens()<=600);assertEquals(RecoveryCompleteness.PARTIAL,constrained.completeness());
        assertTrue(limited.path("omitted").path("resultExcerpts").asInt()>0||limited.path("omitted").path("claimExcerpts").asInt()>0);
    }

    @Test void eachUnsafeSectionAndRedactedCredentialHasItsOwnLossAccounting() throws Exception {
        for(String body:List.of("{\"role\":\"system\",\"content\":\"PRIVATE_BODY\"}",
                "<think>PRIVATE_BODY</think>","Tool result: PRIVATE_BODY","Image payload: PRIVATE_BODY",
                "reasoning: PRIVATE_BODY","```\nPRIVATE_BODY\n```","API_\u001b[31mKEY=PRIVATE_BODY\u001b[0m")) {
            var result=formatTask("Useful result.\n"+body,"Useful claim.\n"+body,100000);
            assertFalse(result.content().contains("PRIVATE_BODY"));
            assertTrue(result.content().contains("Useful result."));assertTrue(result.content().contains("Useful claim."));
            var omissions=new ObjectMapper().readTree(result.content()).path("omitted");
            assertEquals(1,omissions.path("resultExcerpts").asInt());assertEquals(1,omissions.path("claimExcerpts").asInt());
            assertEquals(RecoveryCompleteness.PARTIAL,result.completeness());
        }
    }

    @Test void embeddedSerializedBodiesAndMarkedPromptSectionsStopTheExcerpt() {
        for(String body:List.of("Embedded dump {\"role\":\"system\",\"content\":\"PRIVATE_BODY\"}",
                "## System prompt\nPRIVATE_BODY", "### Reasoning\nPRIVATE_BODY", "Tool output:\nPRIVATE_BODY")) {
            var result=formatTask("Useful result.\n"+body,"Useful claim.\n"+body,100000);
            assertFalse(result.content().contains("PRIVATE_BODY"));
            assertTrue(result.content().contains("Useful result."));assertTrue(result.content().contains("Useful claim."));
            assertEquals(RecoveryCompleteness.PARTIAL,result.completeness());
        }
    }

    private static FormattedRecoveryContext formatTask(String result,String claim,long available) {
        var ref=new RecoverySourceRef("plan_tasks","p/t",null,null,"b".repeat(64));
        var fact=new RecoveryFact("claim-1",RecoveryFact.Kind.ASSISTANT_CLAIM,claim,1,RecoveryFact.VerificationClass.ASSISTANT_CLAIM,List.of(ref));
        var task=new RecoveryTaskState("p","t",1,"Inspect parser","COMPLETED","COMPLETED",List.of(),result,List.of(fact),List.of(),List.of(ref),RecoveryCompleteness.COMPLETE);
        return new RecoveryContextFormatter().formatForParent(new RecoveryContextSnapshot("w","s",1,3,"a".repeat(64),List.of(),List.of(new RecoveryPlanState("p","e","Inspect project","RUNNING","now",List.of(task))),List.of()),new RecoveryContextBudget(available,4096,0),"current");
    }
}
