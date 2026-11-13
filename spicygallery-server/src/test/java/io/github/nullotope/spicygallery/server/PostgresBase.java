package io.github.nullotope.spicygallery.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton Postgres 17 for server tests (the sanctioned infra exception, spec §11).
 * Started manually in a static block — NOT via @Container, whose per-class lifecycle
 * stops the container while later classes reuse the cached Spring context.
 */
public abstract class PostgresBase {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("spicygallery")
            .withUsername("spicygallery")
            .withPassword("spicygallery");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}
