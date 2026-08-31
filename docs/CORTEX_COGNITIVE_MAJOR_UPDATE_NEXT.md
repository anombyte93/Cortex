# Single next validation step

Run the S26 Ultra latency benchmark against the current branch after installing the matching debug/test APKs. The benchmark must run with both Shadow OFF and Shadow ON and enforce the acceptance thresholds in `CognitiveLatencyBenchmark`.

If it passes, proceed immediately to one controlled real notification with temporary 100% canary, verify V2 authority completion and persistence, then restore 5% and audit the DB for absence of Legacy fallback on that signal.

Do not repeat model download, model SHA verification, generic self-test, router instrumentation, or canary routing instrumentation unless the model/code involved in those checks changes.
