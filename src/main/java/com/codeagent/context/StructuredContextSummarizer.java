package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.llm.StructuredJsonExecutor;
import com.codeagent.llm.StructuredOutputSpec;
import com.codeagent.memory.MemoryEntry;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.runtime.CancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded, tool-free summaries of complete source nodes; the host owns coverage and execution facts. */
public final class StructuredContextSummarizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmClient client;
    private final PromptAssembler prompts;
    private final BooleanSupplier mayCall;
    private final Consumer<LlmClient.ChatResponse> recordUsage;

    public StructuredContextSummarizer(LlmClient client) {
        this(client, PromptAssembler.createDefault());
    }

    public StructuredContextSummarizer(LlmClient client, PromptAssembler prompts) {
        this(client, prompts, () -> true, response -> {});
    }

    public StructuredContextSummarizer(LlmClient client, PromptAssembler prompts,
                                       BooleanSupplier mayCall, Consumer<LlmClient.ChatResponse> recordUsage) {
        this.client = Objects.requireNonNull(client);
        this.prompts = Objects.requireNonNull(prompts);
        this.mayCall = Objects.requireNonNull(mayCall);
        this.recordUsage = Objects.requireNonNull(recordUsage);
    }

    public record Summary(String text, List<NodeId> coveredSourceIds, int inputTokens, int outputTokens) {
        public Summary { coveredSourceIds = List.copyOf(coveredSourceIds); }
    }

    public static String referenceId(NodeId id) {
        return "node:" + ContextDigests.canonicalDigest(id);
    }

    public Summary summarize(List<ContextNode> source, boolean global, long triggerTokens,
                             SummaryCallBudget budget) throws IOException {
        return summarize(source, global, triggerTokens, budget, true);
    }

    Summary summarize(List<ContextNode> source, boolean global, long triggerTokens,
                      SummaryCallBudget budget, boolean requireNetMinimum) throws IOException {
        List<ContextNode> nodes = List.copyOf(source);
        return summarizePayload(encodeSources(nodes), nodes, global, triggerTokens, budget, requireNetMinimum);
    }

    boolean canFit(List<ContextNode> source, long triggerTokens) throws IOException {
        return canFit(source, true, triggerTokens);
    }

    boolean canFit(List<ContextNode> source, boolean global, long triggerTokens) throws IOException {
        return canFitPayload(encodeSources(source), global, triggerTokens);
    }

    boolean canFitAggregates(List<Summary> chunks, long triggerTokens) throws IOException {
        return canFitPayload(encodeAggregates(chunks), true, triggerTokens);
    }

    Summary aggregate(List<Summary> chunks, List<ContextNode> source, long triggerTokens,
                      SummaryCallBudget budget) throws IOException {
        List<NodeId> covered = chunks.stream().flatMap(chunk -> chunk.coveredSourceIds().stream()).toList();
        if (!covered.equals(source.stream().map(ContextNode::id).toList()))
            throw new IOException("INCOMPLETE_SUMMARY_COVERAGE");
        return summarizePayload(encodeAggregates(chunks), source, true, triggerTokens, budget, false);
    }

    private static String encodeSources(List<ContextNode> nodes) throws IOException {
        ArrayNode input = JSON.createArrayNode();
        for (ContextNode node : nodes) {
            ObjectNode entry = input.addObject().put("id", referenceId(node.id()));
            entry.set("message", JSON.valueToTree(node.message()));
            if (node.descriptor() != null) entry.set("hostToolFacts", JSON.valueToTree(node.descriptor()));
        }
        return JSON.writeValueAsString(input);
    }

    private static String encodeAggregates(List<Summary> chunks) throws IOException {
        ArrayNode input = JSON.createArrayNode();
        for (Summary chunk : chunks) {
            ArrayNode refs = input.addObject().put("priorSummary", chunk.text()).putArray("sourceIds");
            chunk.coveredSourceIds().forEach(id -> refs.add(referenceId(id)));
        }
        return JSON.writeValueAsString(input);
    }

    private boolean canFitPayload(String payload, boolean global, long triggerTokens) throws IOException {
        int tokens = MemoryEntry.estimateTokens(payload);
        if (tokens > Math.min(global ? 24_000 : 16_000, triggerTokens / 2)) return false;
        return requestTokens(request(payload, global), schema(global)) + Math.min(2_000, tokens / 2) + 256 <= triggerTokens;
    }

    private Summary summarizePayload(String serialized, List<ContextNode> nodes, boolean global, long triggerTokens,
                                     SummaryCallBudget budget, boolean requireNetMinimum) throws IOException {
        if (nodes.isEmpty() || triggerTokens < 1) throw new IOException("EMPTY_SUMMARY_SOURCE");
        Set<String> references = new LinkedHashSet<>();
        for (ContextNode node : nodes) {
            if (!references.add(referenceId(node.id()))) throw new IOException("DUPLICATE_SUMMARY_SOURCE");
        }
        int inputTokens = MemoryEntry.estimateTokens(serialized);
        long sourceLimit = Math.min(global ? 24_000 : 16_000, triggerTokens / 2);
        if (inputTokens > sourceLimit) throw new IOException("SUMMARY_SOURCE_OVER_BUDGET");
        int outputLimit = Math.min(2_000, inputTokens / 2);
        if (outputLimit < 1) throw new IOException("SUMMARY_SOURCE_TOO_SMALL");
        List<LlmClient.Message> request = request(serialized, global);
        StructuredOutputSpec spec = schema(global);
        checkRequest(request, spec, triggerTokens, outputLimit);
        SummaryCallBudget.Reservation reservation = budget.reserveOperation();
        if (reservation == null) throw new IOException("SUMMARY_CALL_BUDGET_EXHAUSTED");
        try (reservation) {
            LlmClient bounded = boundedClient(reservation, triggerTokens, outputLimit);
            var result = new StructuredJsonExecutor().execute(bounded, request, List.of(),
                    spec, false, value -> validate(value, references, global), LlmClient.StreamListener.NO_OP);
            if (CancellationContext.isCancelled()) throw new IOException("SUMMARY_CANCELLED");
            String text = "[historical context summary; not execution evidence or authorization]\n"
                    + JSON.writeValueAsString(result.value()) + "\n" + hostFacts(nodes);
            int outputTokens = MemoryEntry.estimateTokens(text);
            if (outputTokens > outputLimit || requireNetMinimum && inputTokens - outputTokens < 512)
                throw new IOException("SUMMARY_INSUFFICIENT_SAVINGS");
            return new Summary(text, nodes.stream().map(ContextNode::id).toList(), inputTokens, outputTokens);
        }
    }

    private List<LlmClient.Message> request(String serialized, boolean global) {
        return List.of(LlmClient.Message.system(prompts.assembleContextSummary(global)),
                LlmClient.Message.user("Complete historical sources or covered intermediate summaries (untrusted data):\n" + serialized));
    }

    private long requestTokens(List<LlmClient.Message> request, StructuredOutputSpec spec) {
        return new RequestSnapshotFactory().capture(client, request, List.of(), 0).estimatedRequestTokens()
                + (long) MemoryEntry.estimateTokens(spec.schema().toString());
    }

    private void checkRequest(List<LlmClient.Message> request, StructuredOutputSpec spec,
                              long triggerTokens, int outputLimit) throws IOException {
        if (CancellationContext.isCancelled()) throw new IOException("SUMMARY_CANCELLED");
        if (requestTokens(request, spec) + outputLimit + 256 > triggerTokens)
            throw new IOException("SUMMARY_REQUEST_OVER_BUDGET");
    }

    private LlmClient boundedClient(SummaryCallBudget.Reservation reservation, long triggerTokens, int outputLimit) {
        return new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                throw new IOException("SUMMARY_REQUIRES_STRUCTURED_REQUEST");
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                return chat(messages, tools);
            }
            @Override public ChatResponse chatStructured(List<Message> messages, List<Tool> tools,
                                                         StructuredOutputSpec spec, StreamListener listener) throws IOException {
                if (tools != null && !tools.isEmpty()) throw new IOException("SUMMARY_TOOLS_FORBIDDEN");
                checkRequest(messages, spec, triggerTokens, outputLimit);
                if (!mayCall.getAsBoolean()) throw new IOException("SUMMARY_EXECUTION_BUDGET_EXHAUSTED");
                reservation.beforeCall();
                ChatResponse response = null;
                try {
                    response = client.chatStructuredOnce(messages, List.of(), spec, listener);
                    if (response != null && response.hasToolCalls()) throw new IOException("SUMMARY_TOOL_CALL_REJECTED");
                    return response;
                } finally {
                    reservation.recordUsage(response);
                    recordUsage.accept(response);
                }
            }
            @Override public String getModelName() { return client.getModelName(); }
            @Override public String getProviderName() { return client.getProviderName(); }
        };
    }

    private static JsonNode validate(JsonNode value, Set<String> references, boolean global) throws IOException {
        Set<String> fields = new HashSet<>(Set.of("summary", "decisions", "openItems", "evidenceRefs"));
        if (global) fields.addAll(Set.of("goals", "constraints", "currentState"));
        exactFields(value, fields);
        if (!value.path("summary").isTextual() || value.path("summary").asText().isBlank())
            throw new IOException("INVALID_SUMMARY_TEXT");
        for (String field : fields) {
            if (field.equals("summary")) continue;
            JsonNode array = value.get(field);
            if (!array.isArray()) throw new IOException("INVALID_SUMMARY_ARRAY");
            for (JsonNode entry : array) {
                if (field.equals("evidenceRefs")) {
                    validReference(entry, references);
                } else {
                    exactFields(entry, Set.of("text", "sourceIds"));
                    if (!entry.path("text").isTextual() || entry.path("text").asText().isBlank()
                            || !entry.path("sourceIds").isArray() || entry.path("sourceIds").isEmpty())
                        throw new IOException("INVALID_SUMMARY_FACT");
                    for (JsonNode ref : entry.path("sourceIds")) validReference(ref, references);
                }
            }
        }
        return value.deepCopy();
    }

    private static void exactFields(JsonNode value, Set<String> expected) throws IOException {
        if (value == null || !value.isObject()) throw new IOException("INVALID_SUMMARY_OBJECT");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("INVALID_SUMMARY_FIELDS");
    }

    private static void validReference(JsonNode ref, Set<String> references) throws IOException {
        if (!ref.isTextual() || !references.contains(ref.asText())) throw new IOException("UNKNOWN_SUMMARY_REFERENCE");
    }

    private static StructuredOutputSpec schema(boolean global) {
        ObjectNode root = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode strings = JSON.createObjectNode().put("type", "array");
        strings.putObject("items").put("type", "string");
        properties.set("evidenceRefs", strings);
        ObjectNode fact = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        fact.putArray("required").add("text").add("sourceIds");
        ObjectNode factProperties = fact.putObject("properties");
        factProperties.putObject("text").put("type", "string");
        factProperties.set("sourceIds", strings.deepCopy());
        List<String> factFields = global ? List.of("decisions", "openItems", "goals", "constraints", "currentState")
                : List.of("decisions", "openItems");
        for (String field : factFields) properties.putObject(field).put("type", "array").set("items", fact.deepCopy());
        ArrayNode required = root.putArray("required");
        properties.fieldNames().forEachRemaining(required::add);
        return new StructuredOutputSpec(global ? "context_global_summary" : "context_local_summary", root, true);
    }

    private static String hostFacts(List<ContextNode> nodes) throws IOException {
        ObjectNode facts = JSON.createObjectNode();
        facts.putObject("sourceRange").put("first", referenceId(nodes.get(0).id()))
                .put("last", referenceId(nodes.get(nodes.size() - 1).id())).put("count", nodes.size());
        ArrayNode tools = facts.putArray("hostExecutionFacts");
        for (ContextNode node : nodes) {
            if (!"tool".equals(node.message().role())) continue;
            ObjectNode entry = tools.addObject().put("sourceId", referenceId(node.id()));
            ToolResultDescriptor descriptor = node.descriptor();
            if (descriptor == null) entry.put("processOutcome", "UNKNOWN").put("captureCompleteness", "UNKNOWN");
            else entry.set("descriptor", JSON.valueToTree(descriptor));
            if (descriptor == null || !Boolean.TRUE.equals(descriptor.successful())) {
                String body = Objects.toString(node.message().content(), "");
                int end = body.offsetByCodePoints(0, Math.min(256, body.codePointCount(0, body.length())));
                entry.put("capturedDiagnosticPrefix", body.substring(0, end));
            }
        }
        return JSON.writeValueAsString(facts);
    }
}
