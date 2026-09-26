# Containers and CI image delivery

## Local rehearsal

Requires Docker Engine/Desktop with Compose v2.24+ (or v5), Ruby for smoke scripts, and Java 21 for host tests. Allow at least 8 GB memory for the entire learning stack and free the application/monitoring ports in Compose. The runtime overlay removes host database/cache port mappings; inspect those through `docker compose exec`. From the repository root, using disposable development data:

On macOS, if Docker Desktop is installed but `docker` is not on PATH, first run `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"` in your terminal.

```bash
export COMPOSE_PROJECT_NAME=phase13-demo
export IMAGE_TAG=local-phase13
docker compose -f docker-compose.dev.yml -f docker-compose.runtime.yml build --pull
docker compose -f docker-compose.dev.yml -f docker-compose.runtime.yml up -d --no-build --wait --wait-timeout 240
SMOKE_DISPOSABLE=true ruby scripts/container-smoke.rb
ruby scripts/observability-smoke.rb
docker compose -f docker-compose.dev.yml -f docker-compose.runtime.yml down
```

`down` retains volumes. Only for disposable data, explicitly add `--volumes` to remove that project's databases and monitoring history. Existing pre-extraction data requires the Phase 11 cutover procedure. The smoke creates unique users/products/orders, temporarily promoting only its new user for catalog setup, then restoring CUSTOMER before checkout. It proves checkout replay, inventory reservation/confirmation, HTTP payment, outbox publication, Kafka receipt, remaining stock and one charge per order. Do not run it against shared/production data.

The runtime overlay hardens all four Java processes: UID 10001, read-only roots, bounded writable `/tmp`, dropped capabilities, no-new-privileges, CPU/memory limits and graceful shutdown. The shared multi-stage Dockerfile copies only the chosen service JAR and runtime dependencies into a Java 21 JRE stage. Build-context allowlisting excludes host targets and environment files. No credentials are supplied as build arguments. Current Compose/CI use `infra/services/Dockerfile` for all four images; the earlier standalone mock-gateway Dockerfile is historical.

Docker checks readiness on core port 8080 and inventory/notification HEALTH_PORT 8082/8083. The simulated gateway uses 8081 and `/health` (process-level only). When changing a service port, also change HEALTH_PORT. Database failure can refuse traffic while the JVM remains alive. Docker health status alone does not restart unhealthy containers. Startup ordering is not a substitute for runtime recovery.

Development Compose credentials are public local examples, not deployment secrets. Networking is plaintext with loopback host bindings; do not expose this stack publicly. For later deployment inject fresh DB passwords, JWT_SECRET, service passwords, METRICS_TOKEN and Grafana credentials using a deployment secret mechanism. Never pass secrets in image build arguments or commit environment files. Development configuration defaults are not production credentials.

## CI and publishing

The `CI` workflow runs on PRs, main pushes, version tags and manual dispatch:

1. Java 21 `./mvnw -B -ntp clean verify`, including real PostgreSQL/Redis Testcontainers and embedded Kafka tests. Migration/test failures stop the pipeline.
2. Build all four deployables with `--pull --no-cache` from source. Maven's dependency download cache stays in the build stage.
3. Scan OS and Java dependencies in every image with Trivy. Fixable HIGH/CRITICAL findings and scanner failures block publication. Unfixed findings are excluded from this blocking policy; this does not mean zero vulnerabilities.
4. Start full Compose, await readiness, run business and dashboard smoke tests and deterministic alert tests.
5. Save tested images and diagnostics. A separate `registry` environment job publishes these exact images without rebuilding, with package-write permission only in that job. PR/manual runs never publish.

Images use `ghcr.io/<owner>/<repository>/<service>:sha-<full-commit-sha>`, plus a human `vMAJOR.MINOR.PATCH[-suffix]` release tag when applicable. No `latest` deployment identity is used. Registries can overwrite tags: treat tags as write-once and deploy by the digest recorded in the job summary. Base tags can receive updates; rebuilding a commit is not guaranteed byte-identical. Publishing the saved tested images avoids that mismatch. Images target the Linux runner architecture, not a multi-architecture manifest.

Workflow artifacts retain test reports, vulnerability JSON, Compose status/logs and tested image archives. Scanning needs registry/vulnerability-database access. Findings must be resolved or explicitly reviewed, not suppressed wholesale. No AWS deployment is included.

## Repository owner setup

The repository is `riisav147/Event-Driven-Order-Inventory-Platform`; its GHCR prefix is `ghcr.io/riisav147/event-driven-order-inventory-platform`. Initial source publication, hosted CI and image publishing succeeded; see [recorded evidence](evidence/phase13.md). Repository owners should still configure and review:

- Enable Actions and GHCR publishing with GITHUB_TOKEN.
- Create the `registry` environment with reviewers and restrictions to main/intended release tags. The YAML environment name alone does not enforce approval.
- Protect main: require reviewed PRs and passing `verify` and `containers` checks; block force pushes. Protect release tags and restrict creation to reviewed commits.
- Review workflow/Dockerfile changes. Before production use, pin actions and scanner/base images to reviewed immutable revisions/digests. Dependabot covers Actions, Maven and the shared Dockerfile.
- Substitute the actual repository in this badge template; do not present a green status before a successful run:

```markdown
[![CI](https://github.com/OWNER/REPOSITORY/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/REPOSITORY/actions/workflows/ci.yml)
```

## Migrations and rollback

Flyway migrates and validates each service's own database during startup before readiness. Never edit applied migrations, disable validation or automatically repair failed history. Use additive expand/contract schema changes while versions overlap. Destructive contraction follows backfill and retirement of old readers. The mock provider retains its existing local-only startup ledger table.

Record current and previous digests before rollout. For a disposable rollback rehearsal, set IMAGE_PREFIX to `ghcr.io/<owner>/<repository>` and IMAGE_TAG to a previous `sha-<commit>`, pull and run `up --no-build --wait`. Actual deployments should override each service image with its digest. Recheck readiness, outbox/lag and a new smoke order. Code rollback does not roll back schema; if older code is incompatible, stop and use a reviewed forward-fix or backup/restore procedure. Never drop production volumes as rollback.

References: [Docker health checks](https://docs.docker.com/reference/dockerfile/#healthcheck), [GitHub registry publishing](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images), [Trivy release](https://github.com/aquasecurity/trivy/releases/tag/v0.74.0).
