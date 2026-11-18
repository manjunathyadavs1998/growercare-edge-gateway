# SPEC-EDGE-01: Edge gateway
- **Requirements:** IAM-5 (OTP limits per IP), PRV-5, OWASP API4 (resource consumption), Phase 1 PRD UI scope (one origin for the apps) and M3 ("J5/J6/J8/J9 pass via API through the gateway"); architecture `docs/architecture/01_SERVICES/edge-gateway.md`
- **Status:** Implemented
- **Owner:** edge-gateway (new repository `growercare-edge-gateway`, port 8080)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Spring Cloud Gateway Server WebMVC** (spring-cloud-dependencies 2025.1.x) on gc-parent (Boot 4.1). The agreed spike (2026-11-18) proved it boots on Boot 4.1.2 with routes. Upstream timeouts use Boot's `spring.http.clients.*`. No gc-common/gc-security: they bring JDBC and per-service IAM principal resolution, which the edge does not do | Same Java stack, repo kit, CI and review as every service (option A, agreed); Envoy stays the fallback |
| D2 | **Routes by service prefix:** `/api/iam/**` → identity-access-service, `/api/cases/**` and `/api/case/**` → case-service, `/api/grower/**` → grower-service, `/api/catalog/**` → catalog-registry-service; the prefix is stripped. notification-service is not exposed (internal only). `/internal/**` and `/dev/**` paths are refused (404) on every route | One origin for the UI; internal APIs stay internal. The architecture's BFF route groups (`/grower`, `/staff`, `/partner`) come when there is something to aggregate |
| D3 | **The gateway checks tokens but does not authorise:** signature, issuer, expiry and the `gc-api` audience (Keycloak JWKS, cached); services still authenticate and authorise every request. Missing or invalid tokens are 401 at the edge; health is open | Defence in depth; the gateway has no business rules (no "god gateway") |
| D4 | **Request hygiene:** client headers that could steer routing or identity are removed (`X-Country`, `X-Shard`, `X-User-*`, `X-Forwarded-User`, `X-Internal-*`); every request gets an `X-Request-Id` (kept when the client sent a UUID) and the W3C `traceparent` is passed on; bodies over 1 MB are 413 (uploads go straight to object storage with presigned URLs) | ID-11/never trust client country or shard; uploads never pass the edge |
| D5 | **Rate limits (Redis token buckets):** per user (token subject) 20 requests/s, burst 40; per client IP for requests without a user 10/s; **starting a phone verification** (`POST /api/grower/growers/*/verifications`) 5 per 15 minutes per user and 20 per hour per IP. 429 Problem Details with `Retry-After` | IAM-5 at the network edge (grower-service keeps its per-grower limit, SPEC-GRW-03 D4) |
| D6 | **Redis down:** the OTP limit **fails closed** (503); general limits **fail open** | Fraud risk on OTP outweighs availability there; elsewhere availability wins |
| D7 | **CORS** for the web apps' origins (`GC_CORS_ALLOWED_ORIGINS`), methods and headers the APIs use; no credentials (tokens travel in headers). The `Origin` header is **not forwarded**, so services (which keep their own CORS for direct local use) never add a second `Access-Control-Allow-Origin` | The browser talks to one origin; duplicate CORS headers make browsers reject the response |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| EDGE-R1 | a valid user token | `GET /api/cases/cases?limit=1` | forwarded to case-service as `GET /cases?limit=1` with the same `Authorization`; the response passed back unchanged (status, body, `ETag`, `Location`) |
| EDGE-R2 | each prefix of D2 | a request | reaches its service with the prefix stripped |
| EDGE-R3 | any route | `…/internal/…` or `…/dev/…` segment (any case) · an unknown prefix · a path outside `/api/**` and health · an encoded (`%`), dot (`.`, `..`) or `;` segment | 404 Problem Details; nothing forwarded (checked before the token, so a probe learns nothing more with one) |
| EDGE-R4 | no token · expired · bad signature · wrong audience (a service token) | any `/api/**` route | 401 Problem Details; nothing forwarded |
| EDGE-R5 | `/actuator/health` | no token | 200 |
| EDGE-R6 | headers of D4 sent by the client | forwarded | removed; `X-Request-Id` present (the client's if it is a UUID, else a new one); `traceparent` passed |
| EDGE-R7 | a body over 1 MB | request | 413; nothing forwarded |
| EDGE-R8 | a user above 20 requests/s (burst 40) · an anonymous IP above 10/s | requests | 429 with `Retry-After`; requests below the limits pass |
| EDGE-R9 | a user starting a 6th phone verification within 15 minutes · an IP starting its 21st within an hour | request | 429; grower-service not called |
| EDGE-R10 | Redis unreachable | an OTP start · any other request | 503 · forwarded (D6) |
| EDGE-R11 | a downstream service down or too slow (3 s) | request | 503 / 504 Problem Details naming no internals |
| EDGE-R12 | a browser preflight from an allowed origin · another origin | `OPTIONS` | CORS headers · refused |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| EDGE-E1 | R1 | care.in token, `GET /api/cases/cases?limit=1` | 200 the same body as case-service directly |
| EDGE-E2 | R2 | `/api/iam/me`, `/api/grower/growers/me`, `/api/catalog/products?country=IN` | each service's answer |
| EDGE-E3 | R3 | `/api/grower/internal/growers/1` · `/api/notify/dev/mailbox` · `/api/unknown/x` · `/api/iam/Internal/…` · `/api/iam/%2e%2e/internal/…` · `/cases` | 404 each, downstream never called |
| EDGE-E4 | R4 | none · expired · forged · case-service client token | 401 ×4 |
| EDGE-E5 | R5 | `/actuator/health` | 200 |
| EDGE-E6 | R6 | `X-Country: BR`, `X-Shard: 7`, `X-Request-Id: <uuid>` | downstream sees no X-Country/X-Shard, the same request id |
| EDGE-E7 | R7 | 2 MB JSON | 413 |
| EDGE-E8 | R8 | 60 rapid requests by one user | some 429 with `Retry-After`; ≤ 40 + 20/s pass |
| EDGE-E9 | R9 | 6 verification starts by one user | 6th is 429 |
| EDGE-E10 | R10 | Redis stopped: an OTP start · `GET /api/iam/me` | 503 · 200 |
| EDGE-E11 | R11 | catalog-registry stopped | 503 on `/api/catalog/**`, other routes fine |
| EDGE-E12 | R12 | preflight from `http://localhost:5173` · from `http://evil.test` · a request with `Origin` | allowed · 403 · the service never sees `Origin`; only the gateway's CORS header comes back |
| EDGE-E13 | **smoke** | the J5, J6, J8, J9 steps of `smoke.sh` with `VIA=gateway` (all calls to `http://localhost:8080/api/…`) | same results as direct |

## 3. API contract
Routes only (D2); each service owns its contract. The gateway's own errors are Problem Details with `code` (`UNAUTHENTICATED`, `NOT_FOUND`, `PAYLOAD_TOO_LARGE`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE` (503), `DEPENDENCY_TIMEOUT` (504)).

## 4. Data & events
None persisted. Redis keys `rl:{user|ip|otp-user|otp-ip}:{id}` with the bucket's TTL. Configuration (env only): service base URLs, OIDC issuer and JWKS, Redis, CORS origins, limits (defaults as D5).

## 5. Security
| Concern | Rule |
|---|---|
| Internal APIs | never routed (R3) |
| Identity spoofing | routing/identity headers stripped (R6); services authorise from the token only |
| Abuse | per-user, per-IP and OTP limits (R8–R10) |
| Errors | Problem Details without stack traces or upstream details |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R1–R7, R12 / E1–E7, E12 | `GatewayRoutingIT` (JDK HTTP stub services), `RouteGuardFilterTest` (R3 path rule), `EdgeConfigTest` (D3 validators, start-up check, origins) |
| R11 / E11 | `UpstreamFailureIT` (a closed port; a 5 s stub) |
| R8, R9 / E8, E9 | `RateLimitIT` (Redis Testcontainer) |
| R10 / E10 | `RedisDownIT` (Redis address with nothing listening) |
| E13 | growercare-local-platform `smoke.sh` |
