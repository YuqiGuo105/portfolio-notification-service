package site.yuqi.notifications.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTest {

    @Test
    void productionOperationsPropertiesUsePortfolioNamespaceAndResolveSigningKey() throws Exception {
        var sources = new MutablePropertySources();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        var resolver = new PropertySourcesPropertyResolver(sources);

        assertThat(resolver.getProperty("portfolio.kafka.dlq-topic")).isEqualTo("portfolio.dlq");
        assertThat(resolver.getProperty("portfolio.operations.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty("portfolio.webhooks.signing-key"))
                .isEqualTo("dev-only-webhook-signing-key-change-me");
        assertThat(resolver.getProperty("springdoc.kafka.dlq-topic")).isNull();
    }
}
