package com.codeagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewResponseParserTest {

    @Test
    void approvesExplicitTrueField() {
        assertTrue(ReviewResponseParser.parseApproved("{\"approved\": true, \"issues\": []}"));
    }

    @Test
    void rejectsExplicitFalseField() {
        assertFalse(ReviewResponseParser.parseApproved("{\"approved\": false, \"issues\": [\"缺少测试\"]}"));
    }

    @Test
    void rejectsWhenApprovedFieldMissing() {
        assertFalse(ReviewResponseParser.parseApproved("{\"summary\": \"看起来还行\"}"));
    }

    @Test
    void rejectsEmptyContent() {
        assertFalse(ReviewResponseParser.parseApproved(""));
        assertFalse(ReviewResponseParser.parseApproved(null));
    }

    @Test
    void fallsBackToKeywordsWhenJsonUnparseable() {
        assertTrue(ReviewResponseParser.parseApproved("检查完毕，结果合格"));
        assertFalse(ReviewResponseParser.parseApproved("检查完毕，结果不合格"));
        assertFalse(ReviewResponseParser.parseApproved("检查完毕，不通过"));
    }

    @Test
    void rejectsUnparseableContentWithoutExplicitApproval() {
        assertFalse(ReviewResponseParser.parseApproved("嗯，我再看看"));
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() {
        assertTrue(ReviewResponseParser.parseApproved("```json\n{\"approved\": true}\n```"));
    }

    @Test
    void extractsIssuesArray() {
        assertEquals("- 缺少测试\n- 未处理空值",
                ReviewResponseParser.parseIssues("{\"approved\": false, \"issues\": [\"缺少测试\", \"未处理空值\"]}"));
    }

    @Test
    void fallsBackToSuggestionsThenSummary() {
        assertEquals("- 建议补充日志",
                ReviewResponseParser.parseIssues("{\"approved\": false, \"suggestions\": [\"建议补充日志\"]}"));
        assertEquals("整体可用", ReviewResponseParser.parseIssues("{\"approved\": false, \"summary\": \"整体可用\"}"));
    }

    @Test
    void defaultIssueMessageWhenNothingParseable() {
        assertEquals("审查未通过，请改进执行结果", ReviewResponseParser.parseIssues("not json at all"));
        assertEquals("", ReviewResponseParser.parseIssues(""));
    }
}
