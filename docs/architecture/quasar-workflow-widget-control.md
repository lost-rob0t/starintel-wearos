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

## Cached observation freshness

The launcher always labels the snapshot as cached, with an absolute last-report
time (or an explicit missing/future-time warning). Persisted ONLINE is a past
observation, never verified live connectivity. A report is eligible for command
enqueue only when it reports ONLINE and its timestamp is between now minus five
minutes and now, inclusive. The repository checks this again when a retained
launcher PendingIntent fires. Enqueue changes only the pending marker, not the
observation timestamp. Runtime publication remains the source of freshness.

No timer or background service is added. Launcher delivery can be suspended;
controls rendered earlier may remain visible but expired requests are rejected.
Opening the secure status activity refreshes its projection and the widget.
Failure text is shown only in that activity, not on the launcher. Widget touch
targets are at least 48dp and action descriptions include the workflow name.

The current consumer is FlowStudioActivity.consumeWidgetCommands on resume.
Queue acceptance does not mean execution or continuous background consumption.
Flow Studio currently maps RESUME to runGraph and PAUSE to stopGraph; this change
does not add canonical pause/resume semantics. Tokens are deduplicated while in
the outbox; there is no durable consumed-token ledger, so runtime-side idempotency
is still required after acknowledgement. A publication cannot enable another
command for a workflow while an earlier command remains in the outbox.

Unit tests exercise policy boundaries and the repository against an in-memory
SharedPreferences interface. This is not evidence of Android disk persistence,
launcher rendering, or lifecycle behavior on a device.

The current UI exposes Run and Stop only. Pause/Resume remain typed protocol
operations for a future capable adapter, but are not presented as supported UI
capabilities while Flow Studio maps them to stop/run. The status view states this
limitation. At heights below 480dp the widget omits secondary rows; its header
still opens all workflows. The minimum size is 250×360dp. Resize callbacks rerender
the projection without refreshing the runtime observation.

`Quasar widget journey` uses a widget-only Gradle init script to build the Android
instrumentation APK without changing shared production build/manifest files.
Its API 35 fixture binds the real AppWidgetProvider into an AppWidgetHost inside
the secure status activity (security flags are cleared only by instrumentation
for screenshots). It is native host evidence, not a third-party launcher test.
