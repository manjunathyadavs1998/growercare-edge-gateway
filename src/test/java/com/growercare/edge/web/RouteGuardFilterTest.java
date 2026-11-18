package com.growercare.edge.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-EDGE-01 R2/R3/R5 (EDGE-E2, E3, E5) for the path rule alone. */
class RouteGuardFilterTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(textBlock = """
            /api/iam/me,                              true
            /api/cases/cases,                         true
            /api/case/cases/1,                        true
            /api/grower/growers/me,                   true
            /api/catalog/products,                    true
            /actuator/health,                         true
            /actuator/health/liveness,                true
            /api/grower/internal/growers/1,           false
            /api/iam/INTERNAL/principals,             false
            /api/notify/dev/mailbox,                  false
            /api/unknown/x,                           false
            /api,                                     false
            /api/iam,                                 true
            /cases,                                   false
            /actuator/env,                            false
            /api/iam/%2e%2e/internal,                 false
            /api/iam/./me,                            false
            /api/iam/../case/cases,                   false
            /api/iam/me;jsessionid=1,                 false
            """)
    void EDGE_E3_onlyKnownPrefixesOutsideInternalAndDevAreRouted(String uri, boolean allowed) {
        assertThat(RouteGuardFilter.allowed(uri)).isEqualTo(allowed);
    }
}
