package com.codeagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StepSearchResultParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsOnlyResultUrlFieldsAndDeduplicates() throws Exception {
        assertEquals(List.of("https://example.com/doc"), StepSearchResultParser.discoveredUrls(mapper.readTree("""
                {"code":0,"results":[{"url":"https://example.com/doc","snippet":"https://evil.example"},{"url":"https://example.com/doc"}]}
                """)));
    }

    @Test
    void rejectsBusinessCodesInvalidUrlsAndUnsupportedShapes() throws Exception {
        for (String json : List.of(
                "{\"code\":4294967296,\"results\":[{\"url\":\"https://evil.example\"}]}",
                "{\"code\":\"0\",\"results\":[]}",
                "{\"results\":[{\"url\":\"https://user:pass@example.com\"}]}",
                "{\"results\":[{\"url\":\"file:///etc/passwd\"}]}",
                "{\"results\":[{\"snippet\":\"https://evil.example\"}]}",
                "{\"data\":{\"results\":[{\"url\":\"https://evil.example\"}]}}")) {
            assertTrue(StepSearchResultParser.discoveredUrls(mapper.readTree(json)).isEmpty(), json);
        }
        assertTrue(StepSearchResultParser.discoveredUrls(null).isEmpty());
    }
}
