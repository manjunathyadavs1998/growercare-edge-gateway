package com.growercare.edge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

/** SPEC-EDGE-01 D3 (token checks) and SPEC-PLAT-06 (start-up refuses missing configuration, naming all of it). */
class EdgeConfigTest {

    static final String ISSUER = "http://localhost:8180/realms/growercare";

    static Jwt jwt(String issuer, List<String> audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").issuer(issuer).audience(audience)
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            EDGE-E4 a user token                     | http://localhost:8180/realms/growercare | gc-api      | false
            EDGE-E4 a service token (gc-internal)    | http://localhost:8180/realms/growercare | gc-internal | true
            EDGE-E4 another issuer                   | https://evil.example/realms/growercare  | gc-api      | true
            """)
    void tokensNeedTheIssuerAndTheApiAudience(String name, String issuer, String audience, boolean rejected) {
        var result = EdgeSecurity.validator(ISSUER).validate(jwt(issuer, List.of(audience)));
        assertThat(result.hasErrors()).isEqualTo(rejected);
    }

    @Test
    void PLAT_E90_everyMissingVariableIsNamed() {
        var env = new MockEnvironment().withProperty("OIDC_ISSUER_URI", ISSUER).withProperty("REDIS_HOST", "redis");
        assertThat(RequiredEnvironment.missing(env)).containsExactly(
                "OIDC_JWKS_URI", "IAM_BASE_URL", "CASE_BASE_URL", "GROWER_BASE_URL", "CATALOG_BASE_URL", "REDIS_PASSWORD");
        var check = new RequiredEnvironment();
        check.setEnvironment(env);
        assertThatThrownBy(() -> check.postProcessBeanFactory(null))
                .hasMessageContaining("OIDC_JWKS_URI").hasMessageContaining("REDIS_PASSWORD").hasMessageNotContaining("REDIS_HOST");
    }

    @Test
    void PLAT_E90_completeConfigurationStarts() {
        var env = new MockEnvironment();
        RequiredEnvironment.REQUIRED.forEach(pair -> env.setProperty(pair[0], "x"));
        assertThat(RequiredEnvironment.missing(env)).isEmpty();
    }

    @ParameterizedTest(name = "origins \"{0}\" → {1}")
    @CsvSource(delimiter = '|', textBlock = """
            ''                                             | 0
            http://localhost:5173                          | 1
            'http://localhost:5173, http://localhost:5174' | 2
            """)
    void EDGE_E12_allowedOriginsAreAList(String origins, int count) {
        assertThat(new EdgeProperties(origins, 1, null).allowedOrigins()).hasSize(count);
    }
}
