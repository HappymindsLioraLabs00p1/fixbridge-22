package com.fixbridge.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Railway, Heroku and Fly all inject a libpq-style connection string that the JDBC driver refuses.
 * These cover the translation and, just as importantly, the cases where it must keep its hands off:
 * an operator who set DB_HOST deliberately must not have it silently overridden.
 */
class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor =
            new DatabaseUrlEnvironmentPostProcessor();

    private MockEnvironment process(String... pairs) {
        MockEnvironment env = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) env.setProperty(pairs[i], pairs[i + 1]);
        processor.postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void aPlatformConnectionStringBecomesAJdbcUrl() {
        var env = process("DATABASE_URL",
                "postgresql://fixuser:s3cret@monorail.proxy.rlwy.net:41234/railway");

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://monorail.proxy.rlwy.net:41234/railway");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("fixuser");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    void aMissingPortFallsBackToThePostgresDefault() {
        var env = process("DATABASE_URL", "postgresql://u:p@db.internal/fixbridge");

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/fixbridge");
    }

    @Test
    void sslModeIsCarriedThrough() {
        var env = process("DATABASE_URL", "postgresql://u:p@host/db?sslmode=require");

        assertThat(env.getProperty("spring.datasource.url")).endsWith("?sslmode=require");
    }

    @Test
    void channelBindingIsDroppedBecauseTheJdbcDriverRejectsIt() {
        var env = process("DATABASE_URL",
                "postgresql://u:p@host/db?sslmode=require&channel_binding=require");

        assertThat(env.getProperty("spring.datasource.url"))
                .contains("sslmode=require")
                .doesNotContain("channel_binding");
    }

    @Test
    void anExplicitDbHostWins() {
        var env = process("DB_HOST", "chosen.example.com",
                "DATABASE_URL", "postgresql://u:p@platform.example.com/db");

        assertThat(env.getProperty("spring.datasource.url")).isNull();   // left to application.yml
    }

    @Test
    void anExplicitDbUrlWins() {
        var env = process("DB_URL", "jdbc:postgresql://chosen/db",
                "DATABASE_URL", "postgresql://u:p@platform/db");

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void anAlreadyJdbcValueIsLeftAlone() {
        var env = process("DATABASE_URL", "jdbc:postgresql://host/db");

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void nothingHappensWithoutAPlatformUrl() {
        var env = process();

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void aMalformedValueIsIgnoredRatherThanCrashingStartup() {
        var env = process("DATABASE_URL", "not a url at all");

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void aUrlWithNoCredentialsStillYieldsAUsableJdbcUrl() {
        var env = process("DATABASE_URL", "postgresql://host:5432/db");

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host:5432/db");
        assertThat(env.getProperty("spring.datasource.username")).isNull();
    }
}
