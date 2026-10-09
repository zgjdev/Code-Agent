package com.codeagent.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoIndexConfigTest {
    @Test void defaultsAndValidation() {
        var options = new CodeAgentConfig().getAutoIndex();
        assertTrue(options.isEnabled());
        assertEquals(1500, options.getDebounceMillis());
        assertEquals(300, options.getReconcileIntervalSeconds());
        assertDoesNotThrow(options::validate);
        options.setDebounceMillis(-1);
        assertThrows(IllegalArgumentException.class, options::validate);
    }
}
