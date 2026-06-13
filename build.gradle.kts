import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Property

plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("nu.studer.jooq") version "10.2.1"
}

group = "de.samujjal"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(26)
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
	}
}

dependencies {
	implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
	// Swagger @Schema annotations enrich the generated tool JSON Schema (constraints, examples).
	// Spring AI's schema generator registers victools' Swagger2 module, which reads these.
	implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.38")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	// jOOQ code generation reads the schema straight from the Flyway migrations (no DB needed at build time).
	jooqGenerator("org.jooq:jooq-meta-extensions:3.21.5")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.flywaydb:flyway-database-postgresql")
	// In-memory idempotency cache for executeTrade (5-minute TTL).
	implementation("com.github.ben-manes.caffeine:caffeine")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Generate the type-safe jOOQ metamodel (tables, records, POJOs) from the Flyway migrations
// via jOOQ's DDLDatabase — no live database required. Output lands in build/generated-src.
jooq {
	version.set("3.21.5")
	configurations {
		create("main") {
			generateSchemaSourceOnCompilation.set(true)
			jooqConfiguration.apply {
				logging = Logging.WARN
				generator.apply {
					database.apply {
						name = "org.jooq.meta.extensions.ddl.DDLDatabase"
						properties.addAll(listOf(
							Property().withKey("scripts").withValue("src/main/resources/db/migration/*.sql"),
							Property().withKey("sort").withValue("flyway"),
							Property().withKey("defaultNameCase").withValue("lower"),
							// Parse vendor-specific DDL (timestamptz, identity columns, etc.) as PostgreSQL.
							Property().withKey("parseDialect").withValue("POSTGRES"),
						))
					}
					generate.apply {
						isRecords = true
						isPojos = true
						isDaos = false
						isFluentSetters = false
						isJavaTimeTypes = true
					}
					target.apply {
						packageName = "de.samujjal.java_net.jooq"
						directory = "build/generated-src/jooq/main"
					}
				}
			}
		}
	}
}

// Regenerates docs/TOOLS.md from the @Tool annotations. Pure reflection — no server,
// no database, tools are never executed. Runs the doc-generator test in isolation.
tasks.register<Test>("generateToolDocs") {
	description = "Generate docs/TOOLS.md from the MCP @Tool annotations (no server required)."
	group = "documentation"
	useJUnitPlatform()
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	filter { includeTestsMatching("de.samujjal.java_net.docs.ToolDocGeneratorTest") }
	outputs.upToDateWhen { false }
}
