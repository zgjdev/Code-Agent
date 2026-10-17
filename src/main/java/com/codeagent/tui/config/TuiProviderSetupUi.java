package com.codeagent.tui.config;

import com.codeagent.cli.ProviderSetupController;
import com.codeagent.llm.ConnectionCheckResult;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Configuration fields live only in modal controls, never the conversation pane. */
public final class TuiProviderSetupUi implements ProviderSetupController.Ui, AutoCloseable {
    private final WindowBasedTextGUI gui;
    private final AtomicReference<ProviderSetupController.Probe> activeProbe = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BasicWindow activeDialog;

    public TuiProviderSetupUi(WindowBasedTextGUI gui) {
        this.gui = gui;
    }

    @Override public boolean secureInputAvailable() {
        return gui != null && !closed.get();
    }

    @Override public void message(String message) {
        if (closed.get()) return;
        new MessageDialogBuilder().setTitle("Provider 配置").setText(message)
                .addButton(MessageDialogButton.OK).build().showDialog(gui);
    }

    @Override public int choose(String title, List<String> options) {
        if (closed.get()) return -1;
        var builder = new ListSelectDialogBuilder<String>().setTitle("Provider 配置")
                .setDescription(title).setCanCancel(true);
        options.forEach(builder::addListItem);
        String choice = builder.build().showDialog(gui);
        return choice == null ? -1 : options.indexOf(choice);
    }

    public static TextBox newTextBox(boolean hidden) {
        TextBox box = new TextBox();
        if (hidden) box.setMask('*');
        return box;
    }

    @Override public String text(String title, String current) {
        return readText(title + (current == null || current.isBlank() ? "" : " [" + current + "]"), false);
    }

    @Override public char[] secret(String title) {
        String value = readText(title, true);
        return value == null ? null : value.toCharArray();
    }

    private String readText(String title, boolean hidden) {
        if (closed.get()) return null;
        var window = new BasicWindow("Provider 配置");
        window.setCloseWindowWithEscape(true);
        window.setHints(List.of(Window.Hint.MODAL, Window.Hint.CENTERED));
        var input = newTextBox(hidden);
        input.setPreferredSize(new com.googlecode.lanterna.TerminalSize(54, 1));
        var value = new AtomicReference<String>();
        var panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label(title));
        panel.addComponent(input);
        panel.addComponent(new Button("确定", () -> {
            value.set(input.getText());
            window.close();
        }));
        panel.addComponent(new Button("取消", window::close));
        window.setComponent(panel);
        activeDialog = window;
        try {
            gui.addWindowAndWait(window);
            return value.get();
        } finally {
            input.setText("");
            activeDialog = null;
        }
    }

    @Override public ConnectionCheckResult check(ProviderSetupController.Probe probe) {
        if (closed.get()) {
            probe.cancel();
            return new ConnectionCheckResult(ConnectionCheckResult.Status.CANCELED);
        }
        activeProbe.set(probe);
        var result = new AtomicReference<ConnectionCheckResult>();
        var window = new BasicWindow("测试连接") {
            @Override public void close() {
                result.compareAndSet(null, new ConnectionCheckResult(ConnectionCheckResult.Status.CANCELED));
                probe.cancel();
                super.close();
            }
        };
        window.setCloseWindowWithEscape(true);
        window.setHints(List.of(Window.Hint.MODAL, Window.Hint.CENTERED));
        var panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("正在发送短测试请求（最多 15 秒）…"));
        panel.addComponent(new Button("取消", window::close));
        window.setComponent(panel);
        activeDialog = window;
        ScheduledExecutorService workers = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "codeagent-tui-config-check");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> task = workers.submit(() -> {
            ConnectionCheckResult checked;
            try {
                checked = probe.execute();
            } catch (RuntimeException failure) {
                checked = new ConnectionCheckResult(ConnectionCheckResult.Status.UNKNOWN_ERROR);
            }
            result.compareAndSet(null, checked);
            gui.getGUIThread().invokeLater(window::close);
        });
        ScheduledFuture<?> timeout = workers.schedule(() -> {
            result.compareAndSet(null, new ConnectionCheckResult(ConnectionCheckResult.Status.TIMEOUT));
            probe.cancel();
            gui.getGUIThread().invokeLater(window::close);
        }, 17, TimeUnit.SECONDS);
        try {
            gui.addWindowAndWait(window);
            ConnectionCheckResult checked = result.get();
            return checked == null ? new ConnectionCheckResult(ConnectionCheckResult.Status.CANCELED) : checked;
        } finally {
            probe.cancel();
            task.cancel(true);
            timeout.cancel(false);
            workers.shutdownNow();
            activeProbe.compareAndSet(probe, null);
            activeDialog = null;
        }
    }

    @Override public void close() {
        closed.set(true);
        var probe = activeProbe.getAndSet(null);
        if (probe != null) probe.cancel();
        BasicWindow window = activeDialog;
        if (window != null && gui != null) gui.getGUIThread().invokeLater(window::close);
    }
}
