# Project Memory

## Current State
- Current Phase: **Maintenance & Documentation**
- Repo: E-Com Bank (8 services, Angular frontend)
- Status: 8/8 services running, ledger balanced, tests passing.

## Lasting Learnings (Feedback)
- Run `mvn -o` for offline builds to avoid DSH tool timeouts.
- Never use PowerShell `Out-File -Encoding utf8` for source files (BOM).
- Do not run detached processes; use the harness background job runner.
- Keycloak realm changes require `docker-compose up -d --force-recreate keycloak`.
