package com.hospitalmanagement.hospital.metrics;

import com.hospitalmanagement.hospital.config.ServiceClientConfig;
import com.hospitalmanagement.hospital.config.ServiceEndpointProperties;
import com.hospitalmanagement.hospital.integration.user.client.UserServiceClient;
import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.security.jwks.CallerMetadataInterceptor;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.LoggerContext;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalClientMetricsTest {
    @TempDir
    Path logs;

    @Test
    void managedBuilderRecordsEachExchangeOnceWithTemplatesAndCorrelation() throws Exception {
        String previous = System.getProperty("APP_LOG_DIR");
        System.setProperty("APP_LOG_DIR", logs.toString());

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var correlation = new AtomicReference<String>();

        server.createContext("/internal/users", exchange -> {
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));

            String id = exchange.getRequestURI().getPath().substring("/internal/users/".length());

            byte[] bytes = ("{\"user\":{\"id\":\"" + id + "\",\"username\":\"fixture\",\"email\":\"fixture@example.invalid\",\"isActivated\":true,\"createdAt\":\"2026-11-01T10:00:00\",\"updatedAt\":null}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(id.endsWith("0000") ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();

        try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run("--spring.config.location=file:src/main/resources/application.properties", "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration")) {
            var properties = new ServiceEndpointProperties();

            properties.setUser(new ServiceEndpointProperties.UserEndpoint("http://127.0.0.1:" + server.getAddress().getPort(),
                    "/internal/users", Duration.ofSeconds(1), Duration.ofSeconds(2)));

            var configuration = new ServiceClientConfig(properties);
            var restClient = configuration.userRestClient(context.getBean(RestClient.Builder.class),
                    new CallerMetadataInterceptor("hospital-api", "test", "test"), null, false);
            var client = new UserServiceClient(restClient, properties);
            var first = UUID.fromString("00000000-0000-0000-0000-000000000001");
            var second = UUID.fromString("00000000-0000-0000-0000-000000000002");

            MDC.put("correlationId", "client-correlation-secret");

            client.getUser(first);
            client.getUser(second);

            assertThat(correlation.get()).isEqualTo("client-correlation-secret");
            assertThatThrownBy(() -> client.getUser(UUID.fromString("00000000-0000-0000-0000-000000000000")))
                    .isInstanceOf(BusinessException.class);

            var registry = context.getBean(MeterRegistry.class);

            assertThat(registry.find("http.client.requests").timers().stream().mapToLong(Timer::count).sum()).isEqualTo(3);
            assertThat(registry.get("http.client.requests").tags("uri", "/internal/users/{id}", "status", "200").timer().count()).isEqualTo(2);
            assertThat(registry.get("http.client.requests").tags("uri", "/internal/users/{id}", "status", "404").timer().count()).isEqualTo(1);

            var scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

            assertThat(scrape).contains("http_client_requests_seconds_bucket")
                    .doesNotContain(first.toString(), second.toString(), "client-correlation-secret", "Authorization");
        } finally {
            MDC.remove("correlationId");

            server.stop(0);
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();

            if (previous == null)
                System.clearProperty("APP_LOG_DIR");
            else System.setProperty("APP_LOG_DIR", previous);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(MetricsConfiguration.class)
    static class Fixture {
    }
}
