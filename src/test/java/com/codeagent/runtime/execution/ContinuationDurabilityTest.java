package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationDurabilityTest {
    @TempDir Path temp;

    @Test void durableStatusCannotFallThroughOrdinaryCoordinator() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"))) {
            store.enqueue(temp, "session", "what is the progress?", null);
            var request = store.claimNext(temp).orElseThrow();
            request = store.commitRequestSemantics(request.claim(), RequestSemantics.STATUS_QUESTION);
            var coordinator = new TopLevelExecutionCoordinator(store, e -> e.submittedInput(),
                    (input, history) -> { throw new AssertionError("status is already routed"); }, id -> java.util.List.of(),
                    (mode, input, action) -> action.run(),
                    (e, input, token) -> new TopLevelExecutionResult(null, null, ExecutionOutcome.SUCCEEDED, "unsafe normal adapter", null),
                    (e, input, token) -> { throw new AssertionError(); });
            var durable = request;
            assertThrows(IllegalStateException.class, () -> coordinator.run(durable, null));
        }
    }

    @Test void statusContractSurvivesFreezeAndReopenAndCannotBeChanged() throws Exception {
        Path db = temp.resolve("runtime.db");
        String id;
        try (var store = new RuntimeExecutionStore(db);
             var owner = WorkspaceOwnerLease.acquire(temp.resolve("locks"), new WorkspaceIdentityResolver().resolve(temp), "owner")) {
            id = store.enqueue(temp, "session", "status?", null).id();
            var running = store.claimCandidate(owner, id).orElseThrow();
            var status = store.commitRequestSemantics(running.claim(), RequestSemantics.STATUS_QUESTION);
            assertEquals(com.codeagent.agent.ExecutionMode.REACT, status.selectedMode());
            assertEquals(com.codeagent.agent.RoutingSource.AUTO_MODEL, status.routingSource());
            assertThrows(IllegalStateException.class, () -> store.commitRequestSemantics(running.claim(), RequestSemantics.NEW_REQUEST));
            store.freezeForUser(owner);
        }
        try (var store = new RuntimeExecutionStore(db)) {
            assertEquals(RequestSemantics.STATUS_QUESTION, store.find(id).orElseThrow().requestSemantics());
            assertTrue(store.find(id).orElseThrow().awaitingInput());
        }
    }

    @Test void schemaFourKeepsUnknownSemanticsDistinctFromNewRequest() throws Exception {
        Path db = temp.resolve("runtime.db");
        try (var store = new RuntimeExecutionStore(db)) {
            store.enqueue(temp, "session", "status?", null);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var query = connection.createStatement();
                 var versions = query.executeQuery("SELECT MAX(version) FROM runtime_schema_migrations")) {
                assertTrue(versions.next());
                assertEquals(4, versions.getInt(1));
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var query = connection.createStatement();
                 var row = query.executeQuery("SELECT request_semantics FROM runtime_executions")) {
                assertTrue(row.next());
                assertNull(row.getString(1));
            }
        }
    }

    @Test void prepareCommitsControlRouteBeforeAnyReceipt() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"));
             var owner = WorkspaceOwnerLease.acquire(temp.resolve("locks"),
                     new WorkspaceIdentityResolver().resolve(temp), "owner")) {
            var target = store.enqueue(temp, "session", "old goal", null);
            store.freezeForUser(owner);
            var request = store.enqueue(temp, "session", "continue old goal", null);
            request = store.claimCandidate(owner, request.id()).orElseThrow();
            var prepared = store.prepareContinuation(request.claim(), target.id(), 1);
            assertEquals(com.codeagent.agent.ExecutionMode.REACT, prepared.selectedMode());
            assertEquals("CONTINUATION_CONTROL", prepared.routingSource().name());
            assertTrue(store.find(target.id()).orElseThrow().awaitingInput());
        }
    }

    @Test void leaseCannotBeUsedOnAnotherThread() throws Exception {
        try (var registry = new SessionExecutionContextRegistry<AutoCloseable>(id -> () -> {}, id -> false, id -> false);
             var lease = registry.acquire("session")) {
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread thread = new Thread(() -> {
                try { lease.context(); } catch (Throwable denied) { failure.set(denied); }
            });
            thread.start(); thread.join(2000);
            assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }
}
