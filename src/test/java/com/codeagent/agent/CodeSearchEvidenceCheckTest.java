package com.codeagent.agent;

import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeSearchEvidenceCheckTest {
    @Test void classHeaderAndOutOfRangeReadDoNotEstablishMethodEvidence() throws Exception {
        var evidence=CodeSearchAgentHarness.JSON.readTree("{\"Store.java\":[\"!transactionActive\",\"throw new IllegalStateException\"]}");
        assertFalse(CodeSearchAgentHarness.hasEvidence(List.of(read("class Store {")),evidence));
        assertFalse(CodeSearchAgentHarness.hasEvidence(List.of(read("offset 超出范围")),evidence));
        assertTrue(CodeSearchAgentHarness.hasEvidence(List.of(read("if (!transactionActive) throw new IllegalStateException(\"transaction required\");")),evidence));
    }
    @Test void mentionsOfLineThreeOrWrongValueDoNotPassRetryConclusion() throws Exception {
        var expected=CodeSearchAgentHarness.JSON.readTree("{\"maxRetries\":3}");
        assertFalse(CodeSearchAgentHarness.matchesFacts("第3行说明最多重试1次",expected));
        assertFalse(CodeSearchAgentHarness.matchesFacts("{\"maxRetries\":1}",expected));
        assertTrue(CodeSearchAgentHarness.matchesFacts("当前源码：{\"maxRetries\":3}",expected));
        assertFalse(CodeSearchAgentHarness.matchesFacts("{\"maxRetries\":\"3\"}",expected));
        assertFalse(CodeSearchAgentHarness.matchesFacts("{\"maxRetries\":3}\n最终结论：{\"maxRetries\":1}",expected));
        assertFalse(CodeSearchAgentHarness.matchesFacts("{\"maxRetries\":3}\n但实际是1次",expected));
        assertTrue(CodeSearchAgentHarness.matchesFacts("```json\n{\"maxRetries\":3}\n```",expected));
    }
    @Test void noToolActivityCannotEstablishAbsentFeatureEvidence() throws Exception {
        assertFalse(CodeSearchAgentHarness.hasEvidence(List.of(),CodeSearchAgentHarness.JSON.createObjectNode()));
    }
    @Test void projectMemorySentinelIsRejectedBeforeCallingTheLiveDelegate() {
        var delegate=org.mockito.Mockito.mock(com.codeagent.llm.LlmClient.class);
        var guarded=new CodeSearchAgentHarness.CountingClient(delegate);
        assertThrows(java.io.IOException.class,()->guarded.chat(List.of(
                new com.codeagent.llm.LlmClient.Message("system","CODEAGENT.md 项目记忆\nPRIVATE_SENTINEL")),List.of()));
        org.mockito.Mockito.verifyNoInteractions(delegate);
    }
    private static ToolRegistry.ToolExecutionResult read(String content) {
        return new ToolRegistry.ToolExecutionResult("read","read_file","{\"path\":\"Store.java\",\"offset\":1,\"limit\":1}",content,0,false,List.of());
    }
}
