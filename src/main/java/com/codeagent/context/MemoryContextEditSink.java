package com.codeagent.context;

public final class MemoryContextEditSink implements ContextEditSink {
    private final ContextWindowState state;
    private final java.util.Map<String, String> committed = new java.util.HashMap<>();
    public MemoryContextEditSink(ContextWindowState state) { this.state = java.util.Objects.requireNonNull(state); }
    @Override public CommitResult commit(ContextEditBatch batch) {
        synchronized (state) {
            String digest = ContextDigests.canonicalDigest(batch);
            String previous = committed.get(batch.operationId());
            if (previous != null) {
                if (!previous.equals(digest)) throw new IllegalArgumentException("Context operation identity conflict");
                return CommitResult.ALREADY_APPLIED;
            }
            var snapshot = state.snapshot();
            var scope = new ContextScopeState(snapshot.scopeId(), snapshot.sourceRevision(), snapshot.contextGeneration(),
                    snapshot.sourceNodes(), snapshot.activeEdits());
            try {
                var next = ContextEditReplay.apply(scope, batch, snapshot.protection());
                state.publishCommittedEdits(next.contextGeneration(), next.activeEdits());
            } catch (ContextEditReplay.StaleContextException stale) { return CommitResult.STALE; }
            committed.put(batch.operationId(), digest);
            return CommitResult.APPLIED;
        }
    }
}
