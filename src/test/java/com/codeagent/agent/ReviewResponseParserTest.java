package com.codeagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewResponseParserTest {

    @Test
    void approvesExplicitTrueField() {
        assertTrue(ReviewResponseParser.parseApproved(
                "{\"approved\":true,\"summary\":\"ok\",\"issues\":[],\"suggestions\":[]}"));
    }

    @Test
    void rejectsExplicitFalseField() {
        assertFalse(ReviewResponseParser.parseApproved(
                "{\"approved\":false,\"summary\":\"bad\",\"issues\":[\"缺少测试\"],\"suggestions\":[]}"));
    }

    @Test
    void rejectsWhenRequiredFieldsMissing() {
        assertFalse(ReviewResponseParser.parseApproved("{\"approved\":true,\"issues\":[]}"));
    }

    @Test
    void rejectsEmptyContent() {
        assertFalse(ReviewResponseParser.parseApproved(""));
        assertFalse(ReviewResponseParser.parseApproved(null));
    }

    @Test
    void rejectsNaturalLanguageApprovalWhenJsonUnparseable() {
        assertFalse(ReviewResponseParser.parseApproved("检查完毕，结果合格"));
        assertFalse(ReviewResponseParser.parseApproved("检查完毕，结果不合格"));
        assertFalse(ReviewResponseParser.parseApproved("检查完毕，不通过"));
    }

    @Test
    void rejectsUnexpectedFields() {
        assertFalse(ReviewResponseParser.parseApproved(
                "{\"approved\":true,\"summary\":\"ok\",\"issues\":[],\"suggestions\":[],\"confidence\":1}"));
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() {
        String fence = String.valueOf((char) 96).repeat(3);
        assertTrue(ReviewResponseParser.parseApproved(
                fence + "json\n{\"approved\":true,\"summary\":\"ok\",\"issues\":[],\"suggestions\":[]}\n"
                        + fence));
    }

    @Test
    void extractsIssuesArray() {
        assertEquals("- 缺少测试\n- 未处理空值",
                ReviewResponseParser.parseIssues(
                        "{\"approved\":false,\"summary\":\"bad\","
                                + "\"issues\":[\"缺少测试\",\"未处理空值\"],\"suggestions\":[]}"));
    }

    @Test
    void fallsBackToSuggestionsThenSummary() {
        assertEquals("- 建议补充日志",
                ReviewResponseParser.parseIssues(
                        "{\"approved\":false,\"summary\":\"bad\",\"issues\":[],"
                                + "\"suggestions\":[\"建议补充日志\"]}"));
        assertEquals("整体可用",
                ReviewResponseParser.parseIssues(
                        "{\"approved\":false,\"summary\":\"整体可用\",\"issues\":[],\"suggestions\":[]}"));
    }

    @Test
    void defaultIssueMessageWhenNothingParseable() {
        assertEquals("审查未通过，请改进执行结果", ReviewResponseParser.parseIssues("not json at all"));
        assertEquals("", ReviewResponseParser.parseIssues(""));
    }
}
