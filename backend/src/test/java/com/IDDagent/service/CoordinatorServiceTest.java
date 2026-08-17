package com.IDDagent.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CoordinatorService 模型级联升级判定单元测试。
 * 覆盖 shouldEscalate 的全部分支：skill 置信度、clarify/multi 升级、chat 兜底区分、
 * 异常/未知 action 升级。
 */
class CoordinatorServiceTest {

    private static Map<String, Object> decision(String action, Double confidence, Boolean degraded) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("action", action);
        if (confidence != null) d.put("confidence", confidence);
        if (degraded != null) d.put("degraded", degraded);
        return d;
    }

    @Test
    void highConfidenceSkillDoesNotEscalate() {
        assertFalse(CoordinatorService.shouldEscalate(decision("skill", 0.95, null)));
    }

    @Test
    void boundaryConfidenceDoesNotEscalate() {
        assertFalse(CoordinatorService.shouldEscalate(decision("skill", 0.6, null)));
    }

    @Test
    void lowConfidenceSkillEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(decision("skill", 0.4, null)));
    }

    @Test
    void skillWithoutConfidenceTreatsAsHighAndDoesNotEscalate() {
        assertFalse(CoordinatorService.shouldEscalate(decision("skill", null, null)));
    }

    @Test
    void stringConfidenceIsParsed() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("action", "skill");
        d.put("confidence", "0.3");
        assertTrue(CoordinatorService.shouldEscalate(d));
    }

    @Test
    void clarifyAlwaysEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(decision("clarify", null, null)));
    }

    @Test
    void multiAlwaysEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(decision("multi", null, null)));
    }

    @Test
    void normalChatDoesNotEscalate() {
        assertFalse(CoordinatorService.shouldEscalate(decision("chat", null, null)));
    }

    @Test
    void degradedChatEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(decision("chat", null, true)));
    }

    @Test
    void nullDecisionEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(null));
    }

    @Test
    void nullActionEscalates() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("confidence", 0.9);
        assertTrue(CoordinatorService.shouldEscalate(d));
    }

    @Test
    void unknownActionEscalates() {
        assertTrue(CoordinatorService.shouldEscalate(decision("unknown_action", null, null)));
    }
}
