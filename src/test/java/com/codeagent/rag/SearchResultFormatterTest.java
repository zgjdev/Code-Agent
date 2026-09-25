package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultFormatterTest {

    @Test
    void cliFormatIncludesReadableSummaryBeforeResults() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "/Users/dev/Documents/GitHub/codeagent/src/main/java/com/codeagent/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环：读取用户输入，思考，调用工具，再继续下一轮。",
                        1.42
                )
        );

        String output = SearchResultFormatter.formatForCli("Agent的ReAct循环是怎么实现的", results);

        assertTrue(output.contains("搜索摘要:"));
        assertTrue(output.contains("最相关的入口是 [method:Agent.run(String userInput)]"));
        assertTrue(output.contains("1. [method:Agent.run(String userInput)]"));
    }

    @Test
    void cliAndToolFormattersPreserveTheSharedResponseOrder() {
        RetrievalResponse response = new RetrievalResponse(List.of(
                hit("First.java", "First"), hit("Second.java", "Second")), Optional.empty(),
                new RetrievalDiagnostics("off", Map.of(), Map.of(), List.of(), 2), false);

        String cli = SearchResultFormatter.formatForCli("query", response);
        String tool = SearchResultFormatter.formatForTool("query", response);

        assertTrue(cli.indexOf("First.java") < cli.indexOf("Second.java"));
        assertTrue(tool.indexOf("First.java") < tool.indexOf("Second.java"));
    }

    private static RetrievalHit hit(String file, String symbol) {
        return new RetrievalHit(file, 1, 2, "class", symbol, "class " + symbol + " {}",
                0.5, Set.of(RetrievalSource.FTS_TERMS));
    }
}
