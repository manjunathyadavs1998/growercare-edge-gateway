# growercare-edge-gateway

The single entry point for the GrowerCare web apps: routes `/api/<service>/**` to the services, checks tokens, strips routing and identity headers, rate-limits (stricter on phone verification) and answers CORS. One of the independent service repositories of the GrowerCare platform (ADR-017).

- **Spec:** [`docs/specs/edge/01-edge-gateway.md`](docs/specs/edge/01-edge-gateway.md)
- **Port:** 8080 · **Image:** `ghcr.io/manjunathyadavs1998/edge-gateway`
- **Configuration:** environment variables only, see [`.env.example`](.env.example)

| Prefix | Service |
|---|---|
| `/api/iam/**` | identity-access-service |
| `/api/cases/**`, `/api/case/**` | case-service |
| `/api/grower/**` | grower-service |
| `/api/catalog/**` | catalog-registry-service |

## Build and run
```bash
mvn -B verify                                   # tests (Docker for the Redis Testcontainer), coverage gate input
cp .env.example .env && mvn -B package jib:dockerBuild -DskipTests && docker compose up -d
```
Redis, Keycloak and the services come from `growercare-local-platform`.
