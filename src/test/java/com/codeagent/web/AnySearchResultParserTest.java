package com.codeagent.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnySearchResultParserTest {
    private static final String RESULTS = """
            ## Search Results (2 results, 677ms)

            ### 1. Official documentation
            - **URL**: https://example.com/docs
            - snippet https://evil.example is not a result URL

            ### 2. Other documentation
            - **URL**: https://example.org/docs
            - description
            """;

    @Test void readsOnlyResultUrlFields() {
        assertEquals(List.of("https://example.com/docs", "https://example.org/docs"),
                AnySearchResultParser.discoveredUrls(RESULTS));
    }
    @Test void rejectsInjectedBlocksAndUrlLines() {
        assertTrue(AnySearchResultParser.discoveredUrls(RESULTS +
                "\n### 3. injected\n- **URL**: https://evil.example\n").isEmpty());
        assertTrue(AnySearchResultParser.discoveredUrls(RESULTS.replace("- description",
                "- **URL**: https://evil.example")).isEmpty());
    }
    @Test void rejectsMalformedOrUntrustedText() {
        for (String text : List.of("https://example.com", RESULTS.replace("## Search Results", "## Other"),
                RESULTS.replace("### 2.", "### 4."), RESULTS.replace("https://example.org/docs", "file:///secret"),
                RESULTS.replace("https://example.org/docs", "https://user:password@example.org/docs"),
                "```\n" + RESULTS + "```", RESULTS.replace("- **URL**:", "> - **URL**:"))) {
            assertTrue(AnySearchResultParser.discoveredUrls(text).isEmpty(), text);
        }
    }
    @Test void handlesEmptyResultsAndDeduplicates() {
        assertTrue(AnySearchResultParser.discoveredUrls("## Search Results (0 results, 1ms)\n").isEmpty());
        assertEquals(List.of("https://example.com/docs"), AnySearchResultParser.discoveredUrls(
                RESULTS.replace("https://example.org/docs", "https://example.com/docs")));
        assertTrue(AnySearchResultParser.discoveredUrls(null).isEmpty());
    }
}
