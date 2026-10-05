# Draft: Reduce overhead of around-invoke interception

Status: not ready to submit. Final CI and performance validation are pending; see [the status report](weld-proxy-4-status.md).

Repeated invocations of an intercepted method currently perform reflective dispatch, interception-chain lookup and per-call context/argument allocation. This candidate caches the first method chain per handler, reuses initialized chain checks, reduces context and empty-argument allocations, and reuses the creator thread's interception stack when proceeding on that thread. Other threads retain their own stack lookup.

The candidate also caches MethodHandle invokers with reflective fallback and preserves argument conversions and InvocationTargetException wrapping. A cached privileged handle cannot authorize a Method whose accessibility override is absent or revoked. Whether to retain MethodHandles is pending the attribution benchmark; the initial isolated measurement showed no significant gain. LambdaMetafactory remains a separate experiment.

The Weld 6 backport preserves its WeldInvocationContext/context-data behavior. Changes build on proxy-3; fork-only CI commits must be excluded from an upstream submission.

Validation pending for the corrected candidate:

- Full Weld tests and CDI TCK on both versions.
- Regression tests for access-override changes and cross-thread stack reuse.
- Complete same-runner benchmark, longer four-thread measurements and boot/shutdown comparison.

The recovered earlier incremental run improved methodIntercepted from 22.39 to 28.32 ops/µs before stack reuse and the access correction. These numbers must not be presented as measurements of the final candidate.
