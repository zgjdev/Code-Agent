package com.codeagent.runtime.interaction;

import com.codeagent.hitl.ApprovalPolicy;
import com.codeagent.hitl.ApprovalRequest;
import com.codeagent.hitl.ApprovalResult;
import com.codeagent.hitl.HitlHandler;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class BrokerHitlHandler implements HitlHandler {
    private final InteractionBroker broker;
    private final Consumer<InteractionRequest> notifier;
    private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllByServer = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;

    public BrokerHitlHandler(
            boolean enabled,
            InteractionBroker broker,
            Consumer<InteractionRequest> notifier) {
        this.enabled = enabled;
        this.broker = broker;
        this.notifier = notifier == null ? request -> { } : notifier;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest approval) {
        String server = ApprovalPolicy.mcpServerName(approval.toolName());
        boolean sensitive = approval.sensitiveNotice() != null && !approval.sensitiveNotice().isBlank();
        if (!sensitive && isApprovedAllByTool(approval.toolName())) {
            return ApprovalResult.approveAll();
        }
        if (!sensitive && isApprovedAllByServer(server)) {
            return ApprovalResult.approveAllByServer();
        }
        ExecutionInteractionContext.Identity identity = ExecutionInteractionContext.current();
        if (identity == null) {
            return ApprovalResult.reject("缺少当前 Execution 身份，拒绝执行");
        }

        LinkedHashSet<String> actions = new LinkedHashSet<>(
                Set.of("approve", "reject", "skip", "modify"));
        if (!sensitive) {
            actions.add("approve_all");
            if (server != null && !server.isBlank()) {
                actions.add("approve_server");
            }
        }
        InteractionRequest request = new InteractionRequest(
                UUID.randomUUID().toString(),
                identity.executionId(),
                identity.sessionId(),
                InteractionKind.HITL,
                actions,
                approval.toDisplayText());
        try {
            var future = broker.request(request);
            notifier.accept(request);
            InteractionResponse response = future.join();
            return mapResponse(approval, response);
        } catch (CompletionException | IllegalStateException e) {
            return ApprovalResult.reject("审批交互已终止");
        }
    }

    private ApprovalResult mapResponse(ApprovalRequest request, InteractionResponse response) {
        return switch (response.action()) {
            case "approve" -> ApprovalResult.approve();
            case "approve_all" -> {
                approvedAllByTool.add(request.toolName());
                yield ApprovalResult.approveAll();
            }
            case "approve_server" -> {
                String server = ApprovalPolicy.mcpServerName(request.toolName());
                if (server == null || server.isBlank()) {
                    yield ApprovalResult.reject("工具不属于 MCP server");
                }
                approvedAllByServer.add(server);
                yield ApprovalResult.approveAllByServer();
            }
            case "reject" -> ApprovalResult.reject(response.text());
            case "skip" -> ApprovalResult.skip();
            case "modify" -> ApprovalResult.modify(response.text());
            default -> ApprovalResult.reject("未知审批决定");
        };
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllByTool.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllByServer.contains(serverName);
    }

    @Override
    public void clearApprovedAll() {
        approvedAllByTool.clear();
        approvedAllByServer.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllByServer.remove(serverName);
        }
    }
}
