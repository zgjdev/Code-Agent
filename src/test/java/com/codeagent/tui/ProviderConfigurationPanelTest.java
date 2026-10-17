package com.codeagent.tui;

import com.codeagent.cli.ProviderSetupController;
import com.codeagent.config.*;
import com.codeagent.llm.*;
import com.codeagent.tui.config.*;
import com.codeagent.agent.Agent;
import com.codeagent.hitl.HitlHandler;
import com.codeagent.tui.pane.*;
import com.googlecode.lanterna.gui2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderConfigurationPanelTest {
    @BeforeAll static void initializeLoggingBeforeIsolatedStorage() {
        org.slf4j.LoggerFactory.getLogger(ProviderConfigurationPanelTest.class);
    }

    @TempDir Path temp;
    private String previousHome;
    private String previousUserHome;

    @BeforeEach void isolateStorage() {
        previousHome = System.getProperty("codeagent.home");
        previousUserHome = System.getProperty("user.home");
        System.setProperty("codeagent.home", temp.resolve("data").toString());
        System.setProperty("user.home", temp.resolve("profile").toString());
    }

    @AfterEach void restoreStorage() {
        restoreProperty("codeagent.home", previousHome);
        restoreProperty("user.home", previousUserHome);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test void lateSuccessfulProbeCannotOverrideAnExplicitCancel() throws Exception {
        var gui = mock(WindowBasedTextGUI.class);
        var guiThread = mock(TextGUIThread.class);
        when(gui.getGUIThread()).thenReturn(guiThread);
        var started = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var callback = new CountDownLatch(1);
        var probe = new ProviderSetupController.Probe() {
            public ConnectionCheckResult execute() {
                started.countDown();
                try {
                    assertTrue(released.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                return new ConnectionCheckResult(ConnectionCheckResult.Status.OK);
            }
            public void cancel() { }
        };
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            callback.countDown();
            return null;
        }).when(guiThread).invokeLater(any(Runnable.class));
        when(gui.addWindowAndWait(any())).thenAnswer(invocation -> {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ((Window) invocation.getArgument(0)).close();
            released.countDown();
            assertTrue(callback.await(2, TimeUnit.SECONDS));
            return gui;
        });
        try (var ui = new TuiProviderSetupUi(gui)) {
            assertEquals(ConnectionCheckResult.Status.CANCELED, ui.check(probe).status());
        }
    }

    @Test void hiddenControlStartsEmptyAndMasksSecret() {
        var input = TuiProviderSetupUi.newTextBox(true);
        assertEquals(Character.valueOf('*'), input.getMask());
        assertEquals("", input.getText());
    }

    @Test void panelSwitchOnlyPersistsFutureDefaultAndPreservesRuntime() throws Exception {
        CodeAgentConfig active = new CodeAgentConfig();
        active.setDefaultProvider("glm");
        var service = new ProviderConfigurationService(new UserConfigStore(temp.resolve("config.json")),
                LlmClientFactory.providerDescriptors(), ConfigurationValues.loadEnvironmentSnapshot(Map.of(),
                temp.resolve("none"), temp.resolve("none2")), new Properties());
        var panel = new TuiConfigPanel(active, null, service);
        panel.switchModel("step");
        assertEquals("glm", active.getDefaultProvider());
        assertTrue(Files.readString(temp.resolve("config.json")).contains("step"));
    }

    @Test void configCannotOpenWhileTaskOrApprovalIsPending() throws Exception {
        LlmClient client = mock(LlmClient.class);
        Agent agent = mock(Agent.class);
        Runnable show = mock(Runnable.class);
        try (var controller = new TuiSessionController(new CodeAgentConfig(), client, agent,
                mock(HitlHandler.class), mock(CenterPane.class), mock(StatusPane.class),
                () -> { }, show, Runnable::run)) {
            var field = TuiSessionController.class.getDeclaredField("currentTask");
            field.setAccessible(true);
            field.set(controller, new CompletableFuture<>());
            controller.submit("/config");
            verifyNoInteractions(show);
        }
    }

    @Test void secretCommandNeverReachesConversationOrModel() {
        LlmClient client = mock(LlmClient.class);
        Agent agent = mock(Agent.class);
        CenterPane center = mock(CenterPane.class);
        try (var controller = new TuiSessionController(new CodeAgentConfig(), client, agent,
                mock(HitlHandler.class), center, mock(StatusPane.class), () -> { }, () -> { }, Runnable::run)) {
            clearInvocations(client);
            for (String line : List.of("/config provider step --key fixture-secret",
                    "/config provider step ---key fixture-secret",
                    "/config provider step \"-----api-key\" \"fixture part two\"",
                    "/config provider step ----api_key 'fixture\nsecond'")) controller.submit(line);
            verify(center, never()).onUserMessage(anyString());
            verifyNoInteractions(client);
        }
    }
}
