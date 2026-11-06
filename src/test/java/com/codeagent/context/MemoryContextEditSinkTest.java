package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryContextEditSinkTest {
    private ContextWindowState state() {
        return new ContextWindowState("s", 1, 0, List.of(new ContextNode(NodeId.raw("s", 1),
                LlmClient.Message.assistant("source"), SourceKind.ASSISTANT, null, null, null, null)),
                List.of(), ProtectionSet.empty(), new ContextTokenTracker(),
                new ContextWindowState.Options(true, 10000, 6500, 6000, 7500, 6500, 9000, 8000, 0, 100, 10, 2),
                new SummaryCallBudget(16));
    }
    private ContextEditBatch batch(ContextWindowState s, String operation, String editId) {
        return new ContextEditBatch(operation, "s", s.sourceRevision(), s.contextGeneration(),
                s.protection().digest(), ContextDigests.sourceDigest(s.sourceNodes()),
                List.of(new ContextEdit(editId, ContextEdit.Layer.SNIP, ContextEdit.Kind.REMOVE_SPAN,
                        s.sourceNodes().stream().map(ContextNode::id).toList(), ContextDigests.sourceDigest(s.sourceNodes()),
                        List.of(), List.of(), List.of(), 1)));
    }
    @Test void duplicateIsRecognizedBeforeGenerationCas() throws Exception {
        var s = state(); var sink = new MemoryContextEditSink(s); var batch = batch(s, "op", "edit");
        assertEquals(ContextEditSink.CommitResult.APPLIED, sink.commit(batch));
        assertEquals(ContextEditSink.CommitResult.ALREADY_APPLIED, sink.commit(batch));
        assertEquals(1, s.contextGeneration());
    }
    @Test void sameOperationWithDifferentContentIsRejected() throws Exception {
        var s = state(); var sink = new MemoryContextEditSink(s); var first = batch(s, "op", "edit");
        sink.commit(first);
        assertThrows(IllegalArgumentException.class, () -> sink.commit(batch(s, "op", "other")));
        assertEquals(1, s.contextGeneration());
    }
    @Test void sourceAppendMakesCandidateStaleWithoutPublishingEdits() throws Exception {
        var s = state(); var sink = new MemoryContextEditSink(s); var candidate = batch(s, "op", "edit");
        s.append(new ContextNode(NodeId.raw("s", 2), LlmClient.Message.assistant("later"), SourceKind.ASSISTANT, null, null, null, null));
        assertEquals(ContextEditSink.CommitResult.STALE, sink.commit(candidate));
        assertTrue(s.activeEdits().isEmpty());
    }
}
