# edge-gateway: project guide for Claude

The single entry point for the GrowerCare web apps (ADR-017: its own repository). It routes `/api/<service>/**` to the services, checks tokens, strips client headers that could steer routing or identity, limits request rates (stricter on phone verification) and answers CORS. **No business logic and no aggregation**: every service still authenticates and authorises each request itself.

## Where things are
| Topic | Location |
|---|---|
| Feature spec | `docs/specs/edge/01-edge-gateway.md` (SPEC-EDGE-01) |
| Routes | `src/main/resources/application.yml` (`spring.cloud.gateway.server.webmvc.routes`) |
| Architecture | growercare `docs/architecture/01_SERVICES/edge-gateway.md` |

## Conventions
1. **Stack:** Spring Cloud Gateway Server WebMVC on gc-parent (Boot 4.1). Upstream timeouts are Boot's `spring.http.clients.*`. Do not add gc-common or gc-security (JDBC and IAM principal resolution do not belong at the edge).
2. **Never route** `internal` or `dev` path segments, nor an unknown prefix (404, nothing forwarded).
3. **Rate limits** live in Redis (token buckets, Lua). Redis down: phone verification fails closed (503), everything else fails open.
4. **Configuration only from environment variables** (SPEC-PLAT-06), documented in `.env.example`; the gateway refuses to start naming every missing variable.
5. **Spec-driven development:** spec → parameterized test rows named after spec example IDs → code → green.
6. **Reporting:** run `mvn -B verify` before claiming success.
7. **Git workflow:** never commit to `main`; branch `<type>/<name>`; PR body with `Spec: docs/specs/...`; squash-merge when CI, policy and review are green.
