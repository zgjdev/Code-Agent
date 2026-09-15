package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.context.ContextTokenTracker;
import com.codeagent.context.MeasuredUsage;
import com.codeagent.context.RequestSnapshot;
import com.codeagent.context.RequestSnapshotFactory;
import com.codeagent.memory.TokenBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in contract probes against real providers. These tests deliberately stay out of normal CI.
 * Enable with -Dcodeagent.live.provider-usage=true and configure provider keys through CodeAgent.
 */
@EnabledIfSystemProperty(named = "codeagent.live.provider-usage", matches = "true")
class ProviderUsageLiveContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deepSeekReportsCompletePromptAndAssistantUsage() throws Exception {
        CodeAgentConfig config = CodeAgentConfig.load();
        String apiKey = config.getApiKey("deepseek");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY is not configured");

        String model = config.getModel("deepseek");
        DeepSeekClient client = new DeepSeekClient(apiKey, model);
        verifyCompleteUsageContract("deepseek", client);
    }

    @Test
    void glmReportsCompletePromptAndAssistantUsage() throws Exception {
        CodeAgentConfig config = CodeAgentConfig.load();
        String apiKey = config.getApiKey("glm");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GLM_API_KEY is not configured");

        String model = config.getModel("glm");
        GLMClient client = new GLMClient(apiKey, model);
        verifyCompleteUsageContract("glm", client);
    }

    private static void verifyCompleteUsageContract(String provider, LlmClient client) throws Exception {
        List<LlmClient.Message> baselineMessages = List.of(
                LlmClient.Message.system("You are concise."),
                LlmClient.Message.user("Reply with OK only."));
        String longPolicy = "Provider usage system marker. ".repeat(300);
        List<LlmClient.Message> longSystemMessages = List.of(
                LlmClient.Message.system(longPolicy),
                LlmClient.Message.user("Reply with OK only."));
        LlmClient.Tool largeTool = new LlmClient.Tool(
                "lookup_marker",
                "Provider usage tool schema marker. ".repeat(300),
                JSON.createObjectNode()
                        .put("type", "object")
                        .set("properties", JSON.createObjectNode()
                                .set("query", JSON.createObjectNode().put("type", "string"))));

        LlmClient.ChatResponse baseline = client.chat(baselineMessages, null);
        LlmClient.ChatResponse withLongSystem = client.chat(longSystemMessages, null);
        LlmClient.ChatResponse withTools = client.chat(baselineMessages, List.of(largeTool));
        LlmClient.ChatResponse toolCall = client.chat(
                List.of(
                        LlmClient.Message.system("Call lookup_marker. Do not answer directly."),
                        LlmClient.Message.user("Use lookup_marker with query usage-contract.")),
                List.of(new LlmClient.Tool(
                        "lookup_marker",
                        "Return a marker by query.",
                        JSON.createObjectNode()
                                .put("type", "object")
                                .set("properties", JSON.createObjectNode()
                                        .set("query", JSON.createObjectNode().put("type", "string"))))));

        MeasuredUsage normalized = client.normalizeUsage(toolCall);

        System.out.printf(
                "%s usage baseline[in=%d,out=%d,cache=%d] longSystem[in=%d,out=%d,cache=%d] "
                        + "tools[in=%d,out=%d,cache=%d] toolCall[in=%d,out=%d,cache=%d,hasCall=%s]%n",
                provider,
                baseline.inputTokens(), baseline.outputTokens(), baseline.cachedInputTokens(),
                withLongSystem.inputTokens(), withLongSystem.outputTokens(), withLongSystem.cachedInputTokens(),
                withTools.inputTokens(), withTools.outputTokens(), withTools.cachedInputTokens(),
                toolCall.inputTokens(), toolCall.outputTokens(), toolCall.cachedInputTokens(),
                toolCall.hasToolCalls());

        assertTrue(baseline.inputTokens() > 0, "provider must report prompt/input usage");
        assertTrue(baseline.outputTokens() > 0, "provider must report assistant output usage");
        assertTrue(withLongSystem.inputTokens() > baseline.inputTokens() + 100,
                "prompt usage must include system content");
        assertTrue(withTools.inputTokens() > baseline.inputTokens() + 100,
                "prompt usage must include tools schema");
        assertTrue(toolCall.outputTokens() > 0,
                "assistant tool call must be represented by completion/output usage");
        assertTrue(toolCall.hasToolCalls(), "provider must return the requested tool call");
        assertNotNull(toolCall.toolCalls().get(0).function());
        assertTrue(normalized.trusted(), "verified provider usage must be trusted");
        assertTrue(normalized.inputScope() == MeasuredUsage.InputScope.TOTAL_PROMPT,
                "verified prompt_tokens must represent total prompt pressure");
        assertTrue(normalized.includesSystem(), "verified prompt usage includes system content");
        assertTrue(normalized.includesTools(), "verified prompt usage includes tools schema");

        RequestSnapshotFactory snapshots = new RequestSnapshotFactory();
        RequestSnapshot anchorRequest = snapshots.capture(client, baselineMessages, null, 1);
        LlmClient.Message assistant = LlmClient.Message.assistant(
                baseline.reasoningContent(), baseline.content(), baseline.toolCalls());
        ContextTokenTracker tracker = new ContextTokenTracker();
        tracker.recordSuccessfulCall(
                anchorRequest, TokenBudget.estimateMessageTokens(assistant),
                client.normalizeUsage(baseline));
        RequestSnapshot nextRequest = snapshots.capture(
                client,
                List.of(
                        baselineMessages.get(0),
                        baselineMessages.get(1),
                        LlmClient.Message.assistant(baseline.content()),
                        LlmClient.Message.user("Reply with OK again.")),
                null,
                2);

        assertTrue(tracker.hasUsableAnchor(nextRequest),
                "verified provider usage must establish a reusable context anchor");
        assertTrue(tracker.predict(nextRequest).mode() == ContextTokenTracker.Mode.USAGE_ANCHORED_DELTA,
                "next request must use usage-anchored surface delta");
    }
}
