package com.growercare.edge.config;

import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * SPEC-PLAT-06: the gateway refuses to start when environment-specific configuration is missing, naming every missing
 * variable at once (before any bean would fail on the first one). Runs before regular beans are created.
 */
public class RequiredEnvironment implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Variable name → property it binds to. */
    static final List<String[]> REQUIRED = List.of(
            new String[] {"OIDC_ISSUER_URI", "spring.security.oauth2.resourceserver.jwt.issuer-uri"},
            new String[] {"OIDC_JWKS_URI", "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"},
            new String[] {"IAM_BASE_URL", "spring.cloud.gateway.server.webmvc.routes[0].uri"},
            new String[] {"CASE_BASE_URL", "spring.cloud.gateway.server.webmvc.routes[1].uri"},
            new String[] {"GROWER_BASE_URL", "spring.cloud.gateway.server.webmvc.routes[2].uri"},
            new String[] {"CATALOG_BASE_URL", "spring.cloud.gateway.server.webmvc.routes[3].uri"},
            new String[] {"REDIS_HOST", "spring.data.redis.host"},
            new String[] {"REDIS_PASSWORD", "spring.data.redis.password"});

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        var missing = missing(environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variables: " + String.join(", ", missing)
                    + " (see .env.example)");
        }
    }

    static List<String> missing(Environment environment) {
        return REQUIRED.stream()
                .filter(pair -> !present(environment, pair[0]) && !present(environment, pair[1]))
                .map(pair -> pair[0])
                .toList();
    }

    private static boolean present(Environment environment, String key) {
        try {
            var value = environment.getProperty(key);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException unresolvable) {      // "${X}" with X unset
            return false;
        }
    }
}
