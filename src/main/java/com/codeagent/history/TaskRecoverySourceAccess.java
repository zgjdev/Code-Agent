package com.codeagent.history;

import com.codeagent.model.recovery.RecoverySourceRef;
import com.codeagent.model.recovery.TaskAttemptBinding;
import com.codeagent.model.recovery.TaskRecoveryCheckpoint;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Narrow read-only binding/provenance boundary; no Plan or mutable Task dependency. */
public interface TaskRecoverySourceAccess {
    void requireExact(TaskAttemptBinding binding) throws SQLException;
    Optional<TaskAttemptBinding> findAttempt(String attemptId) throws SQLException;
    TaskRecoveryCheckpoint readCheckpoint(TaskAttemptBinding binding) throws SQLException;
    List<RecoverySourceRef> readInvocationSources(TaskAttemptBinding binding) throws SQLException;
}
