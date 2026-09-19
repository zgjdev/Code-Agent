# Plan Evidence Gate and Conflict-Aware Scheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/plan` run only resource-compatible tasks in parallel and mark a task complete only when required deterministic evidence and semantic review succeed.

**Architecture:** Keep `PlanExecuteAgent` as the single state owner. Extend `Task` with immutable resource/evidence requirements, select stable non-conflicting DAG batches, apply task-local tool restrictions through `TurnToolPolicy`, collect evidence from actual tool executions and LSP diagnostics, then run a deterministic gate before the Reviewer. Reviewer infrastructure failure and exhausted rejection retries become `UNVERIFIED`, never `COMPLETED`.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven, existing `Planner`/`PlanExecuteAgent`/`TurnToolPolicy`/`ToolRegistry`/LSP/session infrastructure.

## Global Constraints

- Preserve `/plan`, `PipelineOptions.FULL_PRESET`, maximum 4-way task concurrency, maximum 2 review retries, and maximum 1 replan.
- Preserve tool-result input order and the authorization chain `TurnToolPolicy → HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard`.
- Resource claims only restrict scheduling/tools; they never grant URL, command, path, or HITL permission.
- Old planner JSON and existing `Task` constructors remain accepted with conservative defaults.
- Do not add worktrees, distributed workers, shared agent chat history, full DAG persistence, or arbitrary planner-generated verification commands.
- Raw evidence events must not persist source bodies, complete command output, images, memory text, or secrets.
- Implement with tests first and commit each independently reviewable task.

---

## File Structure

**New plan-domain files**

- `src/main/java/com/codeagent/plan/TaskResourceClaims.java`: normalized task read/write/workspace-exclusive declaration.
- `src/main/java/com/codeagent/plan/EvidenceType.java`: allowed planner evidence requirements.
- `src/main/java/com/codeagent/plan/ConflictAwareBatchSelector.java`: deterministic conflict predicate and stable batch selection.

**New tool-policy file**

- `src/main/java/com/codeagent/tool/ToolResourceScope.java`: tool-layer resource restriction independent of plan-domain types.

**New agent verification files**

- `src/main/java/com/codeagent/agent/EvidenceStatus.java`
- `src/main/java/com/codeagent/agent/VerificationOutcome.java`
- `src/main/java/com/codeagent/agent/TaskEvidence.java`
- `src/main/java/com/codeagent/agent/TaskVerificationReport.java`
- `src/main/java/com/codeagent/agent/TaskEvidenceCollector.java`
- `src/main/java/com/codeagent/agent/DeterministicEvidenceGate.java`
- `src/main/java/com/codeagent/agent/StepReviewRequest.java`

**Modified production files**

- `src/main/java/com/codeagent/plan/Task.java`
- `src/main/java/com/codeagent/plan/Planner.java`
- `src/main/resources/prompts/modes/planner.md`
- `src/main/java/com/codeagent/tool/TurnToolPolicy.java`
- `src/main/java/com/codeagent/lsp/LspDiagnosticReport.java`
- `src/main/java/com/codeagent/lsp/LspDiagnosticFormatter.java`
- `src/main/java/com/codeagent/agent/StepReviewer.java`
- `src/main/java/com/codeagent/agent/StepReviewDecision.java`
- `src/main/java/com/codeagent/agent/SubAgentStepReviewer.java`
- `src/main/java/com/codeagent/agent/PlanExecuteAgent.java`
- `docs/dev/03-multi-agent-collaboration.md`
- `AGENTS.md`

**New tests**

- `src/test/java/com/codeagent/plan/TaskResourceClaimsTest.java`
- `src/test/java/com/codeagent/plan/ConflictAwareBatchSelectorTest.java`
- `src/test/java/com/codeagent/agent/TaskEvidenceCollectorTest.java`
- `src/test/java/com/codeagent/agent/DeterministicEvidenceGateTest.java`

**Modified tests**

- `src/test/java/com/codeagent/plan/PlannerTest.java`
- `src/test/java/com/codeagent/plan/ExecutionPlanTest.java`
- `src/test/java/com/codeagent/tool/TurnToolPolicyTest.java`
- `src/test/java/com/codeagent/lsp/LspDiagnosticFormatterTest.java`
- `src/test/java/com/codeagent/agent/SubAgentStepReviewerTest.java`
- `src/test/java/com/codeagent/agent/PlanExecuteAgentTest.java`

---

### Task 1: Task resource and evidence requirement model

**Files:**
- Create: `src/main/java/com/codeagent/plan/TaskResourceClaims.java`
- Create: `src/main/java/com/codeagent/plan/EvidenceType.java`
- Modify: `src/main/java/com/codeagent/plan/Task.java`
- Modify: `src/main/java/com/codeagent/plan/Planner.java`
- Modify: `src/main/resources/prompts/modes/planner.md`
- Test: `src/test/java/com/codeagent/plan/TaskResourceClaimsTest.java`
- Test: `src/test/java/com/codeagent/plan/PlannerTest.java`

**Interfaces:**
- Produces: `TaskResourceClaims.normalize(Path projectRoot, Task.TaskType type, JsonNode resources)`.
- Produces: `Task#getResourceClaims()`, `getAcceptanceCriteria()`, `getRequiredEvidence()`.
- Existing three/four-argument `Task` constructors use `TaskResourceClaims.conservativeDefault(type)`.

- [ ] **Step 1: Write failing model and parser tests**

Cover exact file/directory normalization, rejected absolute/`..` paths, `FILE_WRITE` missing claims becoming `workspaceWrite=true`, old JSON compatibility, and parsing the new fields:

```java
assertEquals(List.of("src/main/java/com/acme/Service.java"),
        task.getResourceClaims().writePaths());
assertEquals(List.of("compiles", "rollback test passes"), task.getAcceptanceCriteria());
assertEquals(Set.of(EvidenceType.DIFF, EvidenceType.BUILD, EvidenceType.TEST),
        task.getRequiredEvidence());
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```text
mvn test -DskipTests=false -Dtest=TaskResourceClaimsTest,PlannerTest
```

Expected: compilation failure because the new types/getters do not exist.

- [ ] **Step 3: Implement immutable models and conservative constructors**

Use this public shape:

```java
public record TaskResourceClaims(List<String> readPaths,
                                 List<String> writePaths,
                                 boolean workspaceWrite) {
    public TaskResourceClaims {
        readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
        writePaths = writePaths == null ? List.of() : List.copyOf(writePaths);
    }
}
```

Add `REVIEWING` and `UNVERIFIED` to `TaskStatus`, plus `markReviewing()` and `markUnverified(String reason)`. Keep `COMPLETED` unchanged as the only successful terminal state.

- [ ] **Step 4: Extend planner JSON parsing and prompt**

Parse `resources`, `acceptanceCriteria`, and `requiredEvidence`. Unknown evidence names are ignored with a diagnostic; illegal resource paths fail plan parsing. Planner output examples must use project-relative forward-slash paths and no glob syntax.

- [ ] **Step 5: Run model/parser tests and verify GREEN**

Expected: all new and existing Planner tests pass.

- [ ] **Step 6: Commit**

```text
git add src/main/java/com/codeagent/plan/TaskResourceClaims.java src/main/java/com/codeagent/plan/EvidenceType.java src/main/java/com/codeagent/plan/Task.java src/main/java/com/codeagent/plan/Planner.java src/main/resources/prompts/modes/planner.md src/test/java/com/codeagent/plan/TaskResourceClaimsTest.java src/test/java/com/codeagent/plan/PlannerTest.java
git commit -m "feat: add plan task safety metadata"
```

### Task 2: Stable conflict-aware batch selection

**Files:**
- Create: `src/main/java/com/codeagent/plan/ConflictAwareBatchSelector.java`
- Create: `src/test/java/com/codeagent/plan/ConflictAwareBatchSelectorTest.java`
- Modify: `src/test/java/com/codeagent/plan/ExecutionPlanTest.java`

**Interfaces:**
- Consumes: immutable `TaskResourceClaims` from Task 1.
- Produces: `List<Task> select(List<Task> readyInExecutionOrder, int maxConcurrency)`.
- Produces: `boolean conflicts(TaskResourceClaims left, TaskResourceClaims right)`.

- [ ] **Step 1: Write failing conflict-matrix tests**

Test read/read compatibility; write/write and write/read conflicts; ancestor-directory overlap; disjoint writes; workspace-exclusive conflict; stable selection of four; skipping a conflicting middle task while selecting a compatible later task; first-task fallback.

- [ ] **Step 2: Run selector tests and verify RED**

```text
mvn test -DskipTests=false -Dtest=ConflictAwareBatchSelectorTest,ExecutionPlanTest
```

- [ ] **Step 3: Implement normalized overlap and greedy stable selection**

The selector must not mutate tasks or add DAG edges:

```java
public List<Task> select(List<Task> ready, int maxConcurrency) {
    List<Task> selected = new ArrayList<>();
    for (Task candidate : ready) {
        if (selected.size() >= Math.max(1, maxConcurrency)) break;
        if (selected.stream().noneMatch(existing -> conflicts(
                existing.getResourceClaims(), candidate.getResourceClaims()))) {
            selected.add(candidate);
        }
    }
    if (selected.isEmpty() && ready != null && !ready.isEmpty()) return List.of(ready.get(0));
    return List.copyOf(selected);
}
```

- [ ] **Step 4: Run tests and verify GREEN**

- [ ] **Step 5: Commit**

```text
git add src/main/java/com/codeagent/plan/ConflictAwareBatchSelector.java src/test/java/com/codeagent/plan/ConflictAwareBatchSelectorTest.java src/test/java/com/codeagent/plan/ExecutionPlanTest.java
git commit -m "feat: select conflict-free plan batches"
```

### Task 3: Enforce task-local tool resource scope

**Files:**
- Create: `src/main/java/com/codeagent/tool/ToolResourceScope.java`
- Modify: `src/main/java/com/codeagent/tool/TurnToolPolicy.java`
- Modify: `src/test/java/com/codeagent/tool/TurnToolPolicyTest.java`

**Interfaces:**
- Consumes: project root plus plan claims converted in `PlanExecuteAgent`.
- Produces: `TurnToolPolicy restrictTo(ToolResourceScope scope)`.
- `ToolResourceScope` has no dependency on `com.codeagent.plan`.

- [ ] **Step 1: Write failing policy tests**

Verify that `write_file` disappears without a write path, allowed writes stay visible and execute, writes outside claims return a synthetic denial without touching the registry, `execute_command/create_project/revert_turn` require workspace exclusivity, and original PathGuard/URL policy denials remain effective.

- [ ] **Step 2: Run tests and verify RED**

```text
mvn test -DskipTests=false -Dtest=TurnToolPolicyTest
```

- [ ] **Step 3: Implement tool-layer resource scope**

Use this shape:

```java
public record ToolResourceScope(Path projectRoot,
                                List<Path> readableRoots,
                                List<Path> writableRoots,
                                boolean workspaceWrite) {
    public boolean exposes(String toolName) { ... }
    public Optional<String> denialReason(ToolRegistry.ToolInvocation invocation) { ... }
}
```

`TurnToolPolicy.expose` applies scope filtering after existing web/browser visibility. `authorize` applies the resource decision before dispatch and returns a new `RESOURCE_SCOPE_DENIED` reason. It must not call HITL or the registry for denied calls.

- [ ] **Step 4: Run policy tests and verify GREEN**

- [ ] **Step 5: Run broader tool-policy regression**

```text
mvn test -DskipTests=false -Dtest=TurnToolPolicyTest,ToolRegistryTest,ApprovalPolicyTest
```

- [ ] **Step 6: Commit**

```text
git add src/main/java/com/codeagent/tool/ToolResourceScope.java src/main/java/com/codeagent/tool/TurnToolPolicy.java src/test/java/com/codeagent/tool/TurnToolPolicyTest.java
git commit -m "feat: restrict plan task resource access"
```

### Task 4: Structured task evidence and deterministic gate

**Files:**
- Create: `src/main/java/com/codeagent/agent/EvidenceStatus.java`
- Create: `src/main/java/com/codeagent/agent/VerificationOutcome.java`
- Create: `src/main/java/com/codeagent/agent/TaskEvidence.java`
- Create: `src/main/java/com/codeagent/agent/TaskVerificationReport.java`
- Create: `src/main/java/com/codeagent/agent/TaskEvidenceCollector.java`
- Create: `src/main/java/com/codeagent/agent/DeterministicEvidenceGate.java`
- Modify: `src/main/java/com/codeagent/lsp/LspDiagnosticReport.java`
- Modify: `src/main/java/com/codeagent/lsp/LspDiagnosticFormatter.java`
- Test: `src/test/java/com/codeagent/agent/TaskEvidenceCollectorTest.java`
- Test: `src/test/java/com/codeagent/agent/DeterministicEvidenceGateTest.java`
- Test: `src/test/java/com/codeagent/lsp/LspDiagnosticFormatterTest.java`

**Interfaces:**
- `TaskEvidenceCollector.observeTools(List<ToolInvocation>, List<ToolExecutionResult>, Path projectRoot)`.
- `TaskEvidenceCollector.observeLsp(LspDiagnosticReport)`.
- `TaskVerificationReport DeterministicEvidenceGate.evaluate(Task, List<TaskEvidence>)`.

- [ ] **Step 1: Write failing evidence tests**

Test successful `write_file` as DIFF evidence, failed write as FAILED, actual Maven/Gradle test commands as TEST, build/package commands as BUILD, plain shell commands as TOOL_RESULT only, LSP error counts as failed LSP, missing required evidence, and an execution-result string saying “tests passed” producing no evidence.

- [ ] **Step 2: Run evidence tests and verify RED**

```text
mvn test -DskipTests=false -Dtest=TaskEvidenceCollectorTest,DeterministicEvidenceGateTest,LspDiagnosticFormatterTest
```

- [ ] **Step 3: Implement immutable evidence records and collector**

Evidence summaries must be bounded and exclude full output/content. Extend `LspDiagnosticReport` with `errorCount` and `warningCount`, retaining a two-argument compatibility constructor.

- [ ] **Step 4: Implement deterministic gate**

For every required `EvidenceType`, require one `PASSED` item and reject any required type with `FAILED` or `MISSING`. Return `NOT_REQUIRED` only when the task has no requirements. Never infer evidence from assistant prose.

- [ ] **Step 5: Run tests and verify GREEN**

- [ ] **Step 6: Commit**

```text
git add src/main/java/com/codeagent/agent/EvidenceStatus.java src/main/java/com/codeagent/agent/VerificationOutcome.java src/main/java/com/codeagent/agent/TaskEvidence.java src/main/java/com/codeagent/agent/TaskVerificationReport.java src/main/java/com/codeagent/agent/TaskEvidenceCollector.java src/main/java/com/codeagent/agent/DeterministicEvidenceGate.java src/main/java/com/codeagent/lsp/LspDiagnosticReport.java src/main/java/com/codeagent/lsp/LspDiagnosticFormatter.java src/test/java/com/codeagent/agent/TaskEvidenceCollectorTest.java src/test/java/com/codeagent/agent/DeterministicEvidenceGateTest.java src/test/java/com/codeagent/lsp/LspDiagnosticFormatterTest.java
git commit -m "feat: add deterministic plan evidence gate"
```

### Task 5: Tri-state Reviewer protocol and fail-closed status

**Files:**
- Create: `src/main/java/com/codeagent/agent/StepReviewRequest.java`
- Modify: `src/main/java/com/codeagent/agent/StepReviewer.java`
- Modify: `src/main/java/com/codeagent/agent/StepReviewDecision.java`
- Modify: `src/main/java/com/codeagent/agent/SubAgentStepReviewer.java`
- Modify: `src/test/java/com/codeagent/agent/SubAgentStepReviewerTest.java`

**Interfaces:**
- Consumes: `TaskVerificationReport` from Task 4.
- Produces: `ReviewOutcome.APPROVED/REJECTED/UNAVAILABLE`.

- [ ] **Step 1: Write failing Reviewer tests**

Verify evidence appears in Reviewer input, approved JSON maps to APPROVED, rejected/unparseable maps to REJECTED, and `AgentMessage.Type.ERROR` maps to UNAVAILABLE rather than approve.

- [ ] **Step 2: Run test and verify RED**

```text
mvn test -DskipTests=false -Dtest=SubAgentStepReviewerTest
```

- [ ] **Step 3: Implement request and tri-state decision**

```java
public record StepReviewDecision(ReviewOutcome outcome, String feedback) {
    public enum ReviewOutcome { APPROVED, REJECTED, UNAVAILABLE }
    public boolean approved() { return outcome == ReviewOutcome.APPROVED; }
}
```

Reviewer input includes bounded evidence summaries and blocking reasons, but no source bodies or complete command output.

- [ ] **Step 4: Run tests and verify GREEN**

- [ ] **Step 5: Commit**

```text
git add src/main/java/com/codeagent/agent/StepReviewRequest.java src/main/java/com/codeagent/agent/StepReviewer.java src/main/java/com/codeagent/agent/StepReviewDecision.java src/main/java/com/codeagent/agent/SubAgentStepReviewer.java src/test/java/com/codeagent/agent/SubAgentStepReviewerTest.java
git commit -m "fix: fail closed when plan review is unavailable"
```

### Task 6: Integrate safe batches, evidence, review, retry, and ledger

**Files:**
- Modify: `src/main/java/com/codeagent/agent/PlanExecuteAgent.java`
- Modify: `src/test/java/com/codeagent/agent/PlanExecuteAgentTest.java`

**Interfaces:**
- Consumes all prior task interfaces.
- Produces task terminal semantics: only approved/verified results call `markCompleted`; unavailable/exhausted review calls `markUnverified`.

- [ ] **Step 1: Write failing integration tests**

Add tests for disjoint tasks running concurrently, conflicting tasks running in separate batches, workspace-exclusive serialization, required evidence missing causing retry without Reviewer, LSP failure blocking Reviewer, Reviewer unavailable becoming UNVERIFIED, retry exhaustion becoming UNVERIFIED, and an UNVERIFIED dependency never running.

- [ ] **Step 2: Run integration tests and verify RED**

```text
mvn test -DskipTests=false -Dtest=PlanExecuteAgentTest
```

- [ ] **Step 3: Select a conflict-free subset before executeTaskBatch**

Replace “submit all ready tasks” with `batchSelector.select(getExecutableTasksInOrder(plan), 4)`. Leave deferred ready tasks PENDING for the next loop.

- [ ] **Step 4: Apply resource scope per task**

After `forkWithTrustedUrls`, call `restrictTo(...)` using normalized claims. Never mutate the root policy or sibling branches.

- [ ] **Step 5: Thread the evidence collector through task execution**

Observe tool invocations/results immediately around `taskToolPolicy.execute(...)`; observe each flushed LSP report; freeze evidence when the task returns. Extend `TaskRunResult`/`TaskExecutionResult` with the immutable verification report and outcome.

- [ ] **Step 6: Gate before Reviewer and enforce terminal state**

Deterministic rejection creates retry feedback without invoking Reviewer. Reviewer UNAVAILABLE returns a distinct unverified result. Retry exhaustion returns unverified rather than a normal `TaskRunResult`. Outer batch handling calls `markCompleted` only for verified success and `markUnverified` otherwise.

- [ ] **Step 7: Append bounded audit events**

Record normalized claim counts, selected/deferred batch IDs, evidence status counts, review outcome, and unverified reason codes. Do not store evidence bodies or full feedback.

- [ ] **Step 8: Run integration and plan regression tests**

```text
mvn test -DskipTests=false -Dtest=PlanExecuteAgentTest,ExecutionPlanTest,PlannerTest,StepBriefingTest,SubAgentStepReviewerTest,PipelineOptionsTest,MainPlanAgentFactoryTest,CliCommandParserTest
```

- [ ] **Step 9: Commit**

```text
git add src/main/java/com/codeagent/agent/PlanExecuteAgent.java src/test/java/com/codeagent/agent/PlanExecuteAgentTest.java
git commit -m "feat: enforce verified conflict-safe plan execution"
```

### Task 7: Documentation and full verification

**Files:**
- Modify: `docs/dev/03-multi-agent-collaboration.md`
- Modify: `docs/dev/12-plan-evidence-and-conflict-aware-scheduling.md`
- Modify: `AGENTS.md`
- Modify only if configuration is added: `.env.example`, `README.md`

- [ ] **Step 1: Update factual documentation**

Mark document 12 implemented only for delivered behavior. Update document 03’s scheduling, review, status, failure, security, test, resume, and interview sections. Update AGENTS runtime constraints and test matrix.

- [ ] **Step 2: Run targeted policy and plan suites**

```text
mvn test -DskipTests=false -Dtest=TaskResourceClaimsTest,ConflictAwareBatchSelectorTest,TaskEvidenceCollectorTest,DeterministicEvidenceGateTest,PlannerTest,ExecutionPlanTest,TurnToolPolicyTest,SubAgentStepReviewerTest,PlanExecuteAgentTest
```

- [ ] **Step 3: Run project regression/build gates**

```text
mvn test -Pquick -DskipTests=false
mvn test -DskipTests=false
mvn clean package
git diff --check
```

Record pre-existing environment/platform failures separately; do not claim a green suite when failures remain.

- [ ] **Step 4: Check documentation consistency**

Search for obsolete claims that Reviewer ERROR approves, retry exhaustion completes, or all DAG-ready tasks always run together. Confirm no design-only feature is described as delivered unless its tests pass.

- [ ] **Step 5: Commit**

```text
git add AGENTS.md docs/dev/03-multi-agent-collaboration.md docs/dev/12-plan-evidence-and-conflict-aware-scheduling.md
git commit -m "docs: document verified conflict-safe plan execution"
```

---

## Self-Review Result

- Spec coverage: data model, parser compatibility, conflict matrix, safe batch selection, task-local enforcement, evidence provenance, LSP/tool collection, deterministic gate, tri-state Reviewer, UNVERIFIED state, retries, replan, audit, docs, and regression gates are assigned to Tasks 1-7.
- Scope: worktree isolation, distributed agents, dynamic shell write inference, and full DAG recovery remain explicitly excluded.
- Type consistency: plan-domain claims convert to tool-domain `ToolResourceScope`; evidence flows from `TaskEvidenceCollector` to `TaskVerificationReport` to `StepReviewRequest`; only `COMPLETED` unlocks dependencies.
- Placeholder scan: no unresolved placeholder or cross-task shorthand remains.
