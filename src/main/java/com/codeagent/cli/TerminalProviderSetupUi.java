package com.codeagent.cli;

import com.codeagent.llm.ConnectionCheckResult;
import org.jline.reader.*;
import org.jline.terminal.*;
import org.jline.terminal.spi.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sole terminal input owner, reused before and during the interactive runtime. */
public final class TerminalProviderSetupUi implements ProviderSetupController.Ui {
    private final Terminal terminal; private final LineReader reader; private final PrintStream out;
    public TerminalProviderSetupUi(Terminal terminal, LineReader reader, PrintStream out) {
        this.terminal = terminal; this.reader = reader; this.out = out;
    }
    @Override public boolean secureInputAvailable() { return isInteractiveTerminal(terminal); }
    public static boolean systemTerminalAvailable() {
        try {
            return TerminalBuilder.builder().getProviders(null, new IllegalStateException()).stream()
                    .anyMatch(provider -> provider.isSystemStream(SystemStream.Input) && provider.isSystemStream(SystemStream.Output));
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
    public static boolean isInteractiveTerminal(Terminal terminal) {
        if (terminal == null || Terminal.TYPE_DUMB.equals(terminal.getType()) || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) return false;
        if (!(terminal instanceof TerminalExt ext) || ext.getProvider() == null) return false;
        try {
            return ext.getProvider().isSystemStream(SystemStream.Input)
                    && ext.getProvider().isSystemStream(SystemStream.Output);
        } catch (RuntimeException failure) { return false; }
    }
    public void message(String message) { out.println(message); out.flush(); }
    public int choose(String title, List<String> options) {
        message(title);
        for (int i = 0; i < options.size(); i++) message((i + 1) + ". " + options.get(i));
        while (true) {
            String answer = read("选择编号（0 取消）: ", false);
            if (answer == null || answer.equals("0") || answer.equalsIgnoreCase("cancel")) return -1;
            try { int choice = Integer.parseInt(answer.trim()) - 1; if (choice >= 0 && choice < options.size()) return choice; }
            catch (NumberFormatException ignored) { }
            message("请输入列表中的编号，或输入 0 取消。");
        }
    }
    public String text(String title, String current) {
        return read(title + (current == null || current.isBlank() ? "" : " [" + current + "]") + ": ", false);
    }
    public char[] secret(String title) {
        if (!secureInputAvailable()) throw new IllegalStateException("Secure terminal required");
        // Fail closed if this terminal cannot disable echo, rather than fall back to visible input.
        boolean originalEcho = terminal.echo();
        try {
            terminal.echo(false);
            if (terminal.echo()) throw new IllegalStateException("Cannot disable echo");
            String answer = read(title + ": ", true);
            return answer == null ? null : answer.toCharArray();
        } finally { terminal.echo(originalEcho); }
    }
    private String read(String prompt, boolean secret) {
        Object oldHistory = reader.getVariable(LineReader.DISABLE_HISTORY);
        Object oldCompletion = reader.getVariable(LineReader.DISABLE_COMPLETION);
        boolean oldExpansion = reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION);
        try {
            reader.setVariable(LineReader.DISABLE_HISTORY, true);
            reader.setVariable(LineReader.DISABLE_COMPLETION, true);
            reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            return reader.readLine(prompt, null, masking(secret), null);
        } catch (EndOfFileException eof) { return null; }
        finally {
            reader.setVariable(LineReader.DISABLE_HISTORY, oldHistory);
            reader.setVariable(LineReader.DISABLE_COMPLETION, oldCompletion);
            reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, oldExpansion);
        }
    }
    static MaskingCallback masking(boolean secret) {
        return new MaskingCallback() {
            public String display(String line) { return secret ? "*".repeat(line.length()) : line; }
            public String history(String line) { return null; }
        };
    }
    @Override public ConnectionCheckResult check(ProviderSetupController.Probe probe) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "codeagent-config-check"); t.setDaemon(true); return t; });
        AtomicBoolean interrupted = new AtomicBoolean();
        Attributes attributes = terminal.enterRawMode();
        Terminal.SignalHandler prior = terminal.handle(Terminal.Signal.INT, signal -> { interrupted.set(true); probe.cancel(); });
        Future<ConnectionCheckResult> result = worker.submit(probe::execute);
        try {
            message("正在测试（最多 15 秒，Esc / Ctrl+C 取消）…");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(17);
            while (!result.isDone()) {
                if (interrupted.get()) throw new UserInterruptException("");
                if (System.nanoTime() >= deadline) { probe.cancel(); return new ConnectionCheckResult(ConnectionCheckResult.Status.TIMEOUT); }
                int key = terminal.reader().read(100);
                if (key == 3) { probe.cancel(); throw new UserInterruptException(""); }
                if (key == 4 || key == -1) { probe.cancel(); throw new EndOfFileException(); }
                if (key == 27) { probe.cancel(); return new ConnectionCheckResult(ConnectionCheckResult.Status.CANCELED); }
            }
            if (interrupted.get()) throw new UserInterruptException("");
            return result.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); probe.cancel(); throw new UserInterruptException("");
        } catch (IOException | ExecutionException failure) {
            probe.cancel(); return new ConnectionCheckResult(ConnectionCheckResult.Status.UNKNOWN_ERROR);
        } finally {
            probe.cancel(); result.cancel(true); worker.shutdownNow();
            terminal.handle(Terminal.Signal.INT, prior); terminal.setAttributes(attributes);
        }
    }
}
