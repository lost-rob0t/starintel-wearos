# Quasar workflow widget control boundary

The Quasar home-screen widget is a projection of the native FBP runtime. It is
not an evaluator and is not a second workflow engine.

## Runtime contract

`SharedPreferencesWorkflowRepository` implements the in-process
`WorkflowRuntimeBridge`:

1. The trusted Common Lisp FBP control plane publishes a bounded
   `WorkflowWidgetState` projection (at most 32 workflows).
2. The widget and in-app status surface can request only `RUN`, `PAUSE`,
   `RESUME`, or `STOP` through `BoundedWorkflowActionController`.
3. Accepted operations enter a durable, typed outbox with an idempotency token.
   The runtime reads at most 32 at once, applies its own authority checks, and
   acknowledges each consumed token.
4. The runtime publishes a fresh authoritative snapshot after applying or
   rejecting a command. Publishing refreshes every widget instance.

The UI never changes an authoritative run status. It marks only the typed
command as pending while the control plane decides and executes it.

## Security invariants

- Workflow identifiers accept only 1–128 ASCII letters, digits, `.`, `_`, and
  `-`; an identifier must start with a letter or digit.
- There is no payload, source, form, expression, entrypoint, or Lisp field in a
  widget command.
- Command transitions are allowlisted against the published workflow state.
- Request ids make launcher replay idempotent and the outbox is capped at 64.
- Control `PendingIntent`s are explicit and immutable. They target a
  non-exported receiver.
- The widget provider and status activity are non-exported. Status links are
  explicit intents with strict `quasar://workflow/<id>` parsing.
- Labels and failure summaries are normalized to one bounded line before they
  enter widget state.

These constraints let the Common Lisp side map a typed operation onto its
canonical FBP network semantics without allowing Android UI input to become an
arbitrary Lisp evaluation path.
