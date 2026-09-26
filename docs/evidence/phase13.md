# Phase 13 implementation and verification status

Implemented: shared non-root multi-stage runtime Dockerfile with readiness checks and OCI metadata; tagged images for four deployables; production-like runtime Compose overlay; full business smoke script; CI test/build/scan/Compose smoke gates; separate controlled GHCR publishing of the tested image bytes; dependency update configuration; startup, migration, tagging and rollback documentation.

## Checks executed locally

- Ruby syntax checks passed for `container-smoke.rb` and `observability-smoke.rb`.
- The container smoke script refused to run without `SMOKE_DISPOSABLE=true`, before accessing the stack or creating data.
- YAML parsing passed for CI, Dependabot, development Compose and runtime overlay.
- Official actionlint 1.7.12 (download SHA-256 verified against its published checksum file) accepted the workflow with no diagnostics. Optional shellcheck/pyflakes integrations were disabled because those tools were unavailable.
- All workflow shell blocks passed `bash -n` syntax validation.
- Review corrected the payment-ledger assertion to filter by the generated order idempotency key, permitting repeated smoke runs with retained development data.

## Docker verification — 2026-09-26

Docker Engine 29.8.0 and Compose 5.5.1 ran the full production-like stack on Apple Silicon. `mvn clean verify` inside the Java 21 Maven image passed **88 tests, zero failures/errors/skips**, using real PostgreSQL/Redis Testcontainers and embedded Kafka. This includes the security-patched dependency set, not only the earlier Phase 12 baseline.

All four images built successfully. Compose configuration/merge validation and startup readiness passed. The business smoke passed checkout replay, HTTP inventory/payment, CONFIRMED status, one durable Kafka notification receipt, stock quantities and one payment charge per order. The dashboard smoke passed 100 catalog requests, 18-panel provisioning, all PromQL queries, live HTTP/JVM/pool metrics and Grafana data-source health. All six alert rules passed promtool scenarios. Tempo's `/ready` returned ready.

Trivy 0.74.0 scanned all four final images' OS and Java packages with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1`: **zero blocking findings in each image**. This excludes lower-severity and unfixed vulnerabilities; it is not a claim of vulnerability-free software. Scanner databases were shared between runs and timeout increased to 15 minutes after a cold Java database download exhausted the default timeout.

Fixes discovered during verification:

- Docker 29 requires Testcontainers 1.21.4, now supplied by the updated Boot BOM.
- Runtime Compose keeps database/cache ports internal to avoid an existing host PostgreSQL conflict.
- A one-time, network-isolated CHOWN initializer grants Tempo's UID 10001 ownership of its data volume; Tempo remains non-root.
- Updated Boot to 3.5.16 and applied Netty 4.1.137.Final, Tomcat 10.1.60, PostgreSQL JDBC 42.7.12 and LZ4 1.8.1 security patches. No business Java code changed. Regression tests passed after these changes.

Verified local ARM64 image config IDs (not registry manifest digests):

| Image | Config ID |
| --- | --- |
| core-platform | `sha256:584d3974afcd4553631c9ede70ff2ce9776c93e2861229fb01f74d5a2e92161b` |
| inventory-service | `sha256:e0712d2db198a9dde2b8f8e9f9853178f7c37528ee1cf06af6da1ebe537404f9` |
| notification-service | `sha256:c0a62a340cc0079a97e11688e05524c9787a207c4909bce606960f5bd8e53b4e` |
| mock-gateway | `sha256:fda66043d911d4bf6b5885cf132daae73b7d22b39f3a28519686f149f07b4e8e` |

## Hosted CI and publication

The [GitHub Actions run](https://github.com/riisav147/Event-Driven-Order-Inventory-Platform/actions/runs/36253657254) completed successfully for commit `181d4e6e5ec8aa6c58ea43875be0dd124163a163`. The Linux AMD64 runner passed `verify`, `containers` and `publish`: Java 21 tests, fresh image builds, all image scans, complete Compose startup, business/dashboard/alert smoke, artifact capture and GHCR publishing of the saved tested images.

Published image names under `ghcr.io/riisav147/event-driven-order-inventory-platform/`:

- `core-platform:sha-181d4e6e5ec8aa6c58ea43875be0dd124163a163`
- `inventory-service:sha-181d4e6e5ec8aa6c58ea43875be0dd124163a163`
- `notification-service:sha-181d4e6e5ec8aa6c58ea43875be0dd124163a163`
- `mock-gateway:sha-181d4e6e5ec8aa6c58ea43875be0dd124163a163`

Registry manifest digests are recorded in the publishing job summary; local ARM64 config IDs above are not substitutes. Scan reports, Compose logs/status and tested images are attached as `container-evidence` (7-day retention), with test reports retained for 14 days. Registry package visibility/access and branch/tag/environment reviewer rules remain repository-owner settings; their enforcement was not configured or audited by this implementation.

Follow [the rehearsal and repository setup guide](../container-ci.md) for later releases. A future scan finding must still block publishing until fixed/reviewed. No suppression was added to pass this run.

**Status: Phase 13 implementation, local execution, hosted CI and image publication verified.** No AWS resources or deployment were created.
