package com.codeagent.cli;

import org.junit.jupiter.api.Test;
import org.jline.terminal.spi.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecretInputHistoryTest {
    @Test void arbitraryDashPrefixesAreProtectedAcrossAllInputChannels() throws Exception {
        var history = new CodeAgentHistory();
        var input = new com.codeagent.tui.pane.InputBar(null, null, ignored -> fail("Unexpected submit"));
        var field = input.getClass().getDeclaredField("inputBox");
        field.setAccessible(true);
        var textBox = (com.googlecode.lanterna.gui2.TextBox) field.get(input);
        for (String key : java.util.List.of("---key", "----api-key", "-----api_key", "------apikey",
                "\"---key\"", "'----api-key'", "---\"api-key\"",
                "\u2003---key\u2003", "\u1680----api-key\u1680", "\u001c---key\u001c")) {
            for (String value : java.util.List.of("fixture-private", "\"fixture part two\"", "'fixture\nsecond'")) {
                String line = "/config provider step " + key + " " + value;
                assertTrue(SecretCommandGuard.rejected(line), "Must reject before submit");
                assertEquals("[密钥参数已隐藏，请使用 /config]", SecretCommandGuard.commandMask().display(line));
                assertEquals("[密钥参数已隐藏，请使用 /config]", Main.redactSensitiveInput(line));
                assertNull(SecretCommandGuard.commandMask().history(line));
                history.add(line);
                assertEquals(0, history.size());
                assertEquals(SecretCommandGuard.message(), Main.parseProviderConfigUpdate(line.substring(8)).error());
                textBox.setText(line);
                assertEquals(Character.valueOf('*'), textBox.getMask());
            }
        }
    }

    @Test void quotedAndMultilineRejectedArgumentsAreEntirelyHidden() {
        for (String line : java.util.List.of(
                "/config provider step --key \"fixture part two\"",
                "/config provider step --api_key=first\nsecond",
                "/config provider step \"--api-key\" \"fixture secret\"",
                "/config \"provider\" step --api-key fixture",
                "/config provider step --\"api-key\" fixture")) {
            assertTrue(SecretCommandGuard.rejected(line));
            String display = SecretCommandGuard.commandMask().display(line);
            assertFalse(display.contains("fixture"));
            assertFalse(display.contains("part"));
            assertFalse(display.contains("second"));
            assertNull(SecretCommandGuard.commandMask().history(line));
        }
    }

    @Test void secretMaskNeverStoresOrDisplaysValue() {
        var mask = TerminalProviderSetupUi.masking(true);
        assertFalse(mask.display("fixture-hidden").contains("fixture-hidden"));
        assertNull(mask.history("fixture-hidden"));
    }

    @Test void allAliasesAreExcludedFromHistory() {
        var history = new CodeAgentHistory();
        for (String alias : java.util.List.of("--api-key", "--api_key", "--apikey", "--key", "key")) {
            history.add("/config provider step " + alias + " fixture-secret");
        }
        assertEquals(0, history.size());
    }

    @Test void actualPtyCanWorkWithoutSystemConsoleButRedirectedStreamsCannot() {
        TerminalExt terminal = mock(TerminalExt.class);
        TerminalProvider provider = mock(TerminalProvider.class);
        when(terminal.getType()).thenReturn("xterm");
        when(terminal.getProvider()).thenReturn(provider);
        when(provider.isSystemStream(SystemStream.Input)).thenReturn(true);
        when(provider.isSystemStream(SystemStream.Output)).thenReturn(true);
        assertTrue(TerminalProviderSetupUi.isInteractiveTerminal(terminal));
        when(provider.isSystemStream(SystemStream.Input)).thenReturn(false);
        assertFalse(TerminalProviderSetupUi.isInteractiveTerminal(terminal));
    }
}
