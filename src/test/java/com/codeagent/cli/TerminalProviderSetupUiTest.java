package com.codeagent.cli;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.llm.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.jline.reader.*;
import org.jline.terminal.*;
import org.jline.terminal.spi.*;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TerminalProviderSetupUiTest {
    @TempDir Path temporary;

    @Test void hiddenReadDisablesHistoryCompletionAndExpansionAndRestoresOptions() {
        TerminalExt terminal = mock(TerminalExt.class);
        TerminalProvider provider = mock(TerminalProvider.class);
        when(terminal.getType()).thenReturn("xterm");
        when(terminal.getProvider()).thenReturn(provider);
        when(provider.isSystemStream(SystemStream.Input)).thenReturn(true);
        when(provider.isSystemStream(SystemStream.Output)).thenReturn(true);
        when(terminal.echo()).thenReturn(true, false);
        LineReader reader = mock(LineReader.class);
        Map<String, Object> variables = new HashMap<>();
        variables.put(LineReader.DISABLE_HISTORY, "previous-history-setting");
        variables.put(LineReader.DISABLE_COMPLETION, false);
        when(reader.getVariable(anyString())).thenAnswer(invocation -> variables.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            variables.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(reader).setVariable(anyString(), any());
        when(reader.readLine(anyString(), isNull(), any(MaskingCallback.class), isNull())).thenAnswer(invocation -> {
            assertEquals(true, variables.get(LineReader.DISABLE_HISTORY));
            assertEquals(true, variables.get(LineReader.DISABLE_COMPLETION));
            verify(reader).setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            MaskingCallback mask = invocation.getArgument(2);
            assertNull(mask.history("fixture-secret"));
            assertFalse(mask.display("fixture-secret").contains("fixture-secret"));
            return "fixture-secret";
        });
        var output = new ByteArrayOutputStream();
        var ui = new TerminalProviderSetupUi(terminal, reader, new PrintStream(output));

        char[] secret = ui.secret("API Key");

        assertArrayEquals("fixture-secret".toCharArray(), secret);
        java.util.Arrays.fill(secret, '\0');
        assertEquals("previous-history-setting", variables.get(LineReader.DISABLE_HISTORY));
        assertEquals(false, variables.get(LineReader.DISABLE_COMPLETION));
        verify(reader).option(LineReader.Option.DISABLE_EVENT_EXPANSION, false);
        verify(terminal).echo(true);
        assertFalse(output.toString().contains("fixture-secret"));
    }

    @Test void ctrlCWhileProbeIsBlockedCancelsTheActualHttpCall() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            var config = CodeAgentConfig.load(temporary.resolve("config.json"), Map.of(
                    "CODEAGENT_PROVIDER", "step", "STEP_API_KEY", "fixture-key",
                    "STEP_BASE_URL", server.url("/v1").toString()));
            var session = new ProviderConnectionChecker().newCheck(config, LlmClientOptions.CONFIGURATION_CHECK);
            var completed = new CountDownLatch(1);
            var checked = new AtomicReference<ConnectionCheckResult>();
            var probe = new ProviderSetupController.Probe() {
                public ConnectionCheckResult execute() {
                    try {
                        var result = session.execute();
                        checked.set(result);
                        return result;
                    } finally {
                        completed.countDown();
                    }
                }
                public void cancel() { session.cancel(); }
            };
            Terminal terminal = mock(Terminal.class);
            NonBlockingReader input = mock(NonBlockingReader.class);
            when(terminal.reader()).thenReturn(input);
            when(terminal.enterRawMode()).thenReturn(new Attributes());
            when(input.read(100)).thenAnswer(invocation -> {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                return 3;
            });
            var ui = new TerminalProviderSetupUi(terminal, mock(LineReader.class),
                    new PrintStream(new ByteArrayOutputStream()));

            assertThrows(UserInterruptException.class, () -> ui.check(probe));

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(ConnectionCheckResult.Status.CANCELED, checked.get().status());
            verify(terminal).setAttributes(any(Attributes.class));
        }
    }
}
