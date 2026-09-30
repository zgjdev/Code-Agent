package com.codeagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResourceSchedulingTest {

    @TempDir
    Path projectRoot;

    @Test
    void serializesWriteAndReadOfSamePathInDeclaredOrder() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = trackingRegistry(current, peak, bothStarted, executionOrder);

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "write", "write_file", "{\"path\":\"src/App.java\",\"content\":\"x\"}"),
                new ToolRegistry.ToolInvocation(
                        "read", "read_file", "{\"path\":\"src/App.java\"}")
        ));

        assertEquals(1, peak.get(), "同一路径的写/读不得并发");
        assertEquals(List.of("write_file", "read_file"), executionOrder);
        assertEquals(List.of("write", "read"), results.stream()
                .map(ToolRegistry.ToolExecutionResult::id)
                .toList());
    }

    @Test
    void serializesCommandAndWorkspaceRead() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = trackingRegistry(current, peak, bothStarted, executionOrder);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "command", "execute_command", "{\"command\":\"git status\"}"),
                new ToolRegistry.ToolInvocation(
                        "read", "read_file", "{\"path\":\"README.md\"}")
        ));

        assertEquals(1, peak.get(), "execute_command 应按 workspace write 与文件读取串行");
        assertEquals(List.of("execute_command", "read_file"), executionOrder);
    }

    @Test
    void keepsDisjointWritesParallel() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = trackingRegistry(current, peak, bothStarted, executionOrder);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "a", "write_file", "{\"path\":\"src/A.java\",\"content\":\"a\"}"),
                new ToolRegistry.ToolInvocation(
                        "b", "write_file", "{\"path\":\"src/B.java\",\"content\":\"b\"}")
        ));

        assertEquals(2, peak.get(), "不同路径写入仍应保持并发");
    }

    @Test
    void serializesDirectoryReadWithDescendantWrite() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = trackingRegistry(current, peak, bothStarted, executionOrder);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "list", "list_dir", "{\"path\":\"src\"}"),
                new ToolRegistry.ToolInvocation(
                        "write", "write_file", "{\"path\":\"src/main/App.java\",\"content\":\"x\"}")
        ));

        assertEquals(1, peak.get(), "目录读取与其后代路径写入不得并发");
        assertEquals(List.of("list_dir", "write_file"), executionOrder);
    }

    private ToolRegistry trackingRegistry(AtomicInteger current,
                                          AtomicInteger peak,
                                          CountDownLatch bothStarted,
                                          List<String> executionOrder) {
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(previous -> Math.max(previous, now));
                executionOrder.add(name);
                bothStarted.countDown();
                try {
                    bothStarted.await(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }
}
