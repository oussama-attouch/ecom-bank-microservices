# ---------------------------------------------------------------------------
# keycloak — identity provider for the deployed stack (Render Docker runtime).
#
# Unlike the eight Spring services, this image is built with its OWN directory
# as the context, so it carries no Maven reactor and no source tree:
#
#   docker build -f keycloak-deploy/Dockerfile -t ecom-keycloak ./keycloak-deploy
#
# `--optimized` bakes the build-time configuration in at image build, which is
# what makes a cold start on the free plan tolerable. It also means everything
# that affects the *build* (db, health, features) has to be set here, as ENV,
# before the build step runs — the runtime `start` below then refuses any
# build-time option it was not built with.
# ---------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.0

# Build-time configuration. KC_DB=postgres is fixed here rather than injected
# because a different database would need a different image build, not a
# different environment variable.
ENV KC_DB=postgres
ENV KC_HTTP_ENABLED=true
# Render terminates TLS at its edge and forwards plain HTTP, so Keycloak must
# not try to be the TLS endpoint. KC_PROXY=edge is what makes it honour
# X-Forwarded-* from that edge and emit https:// in discovery documents and
# redirects. Strict hostname checking is off because the public hostname
# arrives per-request via X-Forwarded-Host rather than being knowable at build
# time.
ENV KC_PROXY=edge
ENV KC_HOSTNAME_STRICT=false
ENV KC_HEALTH_ENABLED=true
# Health checks live on the management port (9000) by default. Render probes a
# single port — the one the service listens on — so /health/ready on 9000 is
# unreachable and the deploy would never pass its health check. Setting this to
# false keeps monitoring endpoints on the main HTTP port instead. Keycloak's own
# guidance is to block external traffic to /health at the proxy when this is
# off; on Render there is no such control and the endpoint is public, which is
# accepted here because it exposes no realm data — it reports UP/DOWN and a
# database-connection check, nothing more.
ENV KC_HTTP_MANAGEMENT_HEALTH_ENABLED=false
# The realm is imported on every boot and IGNORE_EXISTING skips it when the
# realm is already in the database — which is the desired behaviour on a
# persistent Postgres, and the reason a realm change is applied by editing the
# realm in the admin console rather than by redeploying (see README-DEPLOY.md).
CMD ["start", "--import-realm", "--optimized"]

# The realm export is copied in as /opt/keycloak/data/import/realm-export.json,
# which is the directory --import-realm scans. This is the same file
# docker-compose.yml mounts for local dev, kept as a copy so that the deploy
# directory is a self-contained build context.
COPY realm-export.json /opt/keycloak/data/import/realm-export.json

# KC_DB_URL, KC_DB_USERNAME and KC_DB_PASSWORD are deliberately NOT set here.
# They are secrets, they differ per environment, and Render injects them from
# render.yaml. Baking them in would put database credentials in a pushed image.
EXPOSE 8080
