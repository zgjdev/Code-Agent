package com.codeagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRelationClassifierTest {

    @Test
    void parsesDuplicateDecisionForKnownCandidate() {
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"duplicate\",\"targetId\":\"old\"}");
        MemoryRelationClassifier classifier = new MemoryRelationClassifier(client);
        MemoryEntry old = new MemoryEntry("old", "用户偏好使用 Java",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5);

        var decision = classifier.classify(
                "记住，Java 是我首选的开发语言",
                "Java 是用户首选的开发语言",
                List.of(old));

        assertEquals(MemoryRelationClassifier.Action.DUPLICATE, decision.action());
        assertEquals("old", decision.targetId());
    }

    @Test
    void acceptsSupersedeOnlyWithExactCurrentInputEvidence() {
        String input = "以后不要记我喜欢 Java 了，我现在更喜欢 Python";
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"supersede\",\"targetId\":\"old\","
                        + "\"evidence\":\"我现在更喜欢 Python\"}");
        MemoryRelationClassifier classifier = new MemoryRelationClassifier(client);
        MemoryEntry old = new MemoryEntry("old", "用户偏好使用 Java",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5);

        var decision = classifier.classify(input, "用户偏好使用 Python", List.of(old));

        assertEquals(MemoryRelationClassifier.Action.SUPERSEDE, decision.action());
        assertEquals("old", decision.targetId());
        assertEquals("我现在更喜欢 Python", decision.evidence());
    }

    @Test
    void invalidSupersedeEvidenceFallsBackToCreate() {
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"supersede\",\"targetId\":\"old\","
                        + "\"evidence\":\"用户明确说要替换\"}");
        MemoryRelationClassifier classifier = new MemoryRelationClassifier(client);
        MemoryEntry old = new MemoryEntry("old", "用户偏好使用 Java",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5);

        var decision = classifier.classify(
                "我最近在写 Python",
                "用户偏好使用 Python",
                List.of(old));

        assertEquals(MemoryRelationClassifier.Action.CREATE, decision.action());
    }

    @Test
    void unknownTargetOrInvalidJsonFallsBackToCreate() {
        MemoryEntry old = new MemoryEntry("old", "用户偏好使用 Java",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5);

        MemoryRelationClassifier unknownTarget = new MemoryRelationClassifier(
                new MemoryTestLlmClient("{\"action\":\"duplicate\",\"targetId\":\"missing\"}"));
        assertEquals(MemoryRelationClassifier.Action.CREATE,
                unknownTarget.classify("记住这个偏好", "用户偏好 Java", List.of(old)).action());

        MemoryRelationClassifier invalidJson = new MemoryRelationClassifier(
                new MemoryTestLlmClient("not-json"));
        assertEquals(MemoryRelationClassifier.Action.CREATE,
                invalidJson.classify("记住这个偏好", "用户偏好 Java", List.of(old)).action());
    }
}
