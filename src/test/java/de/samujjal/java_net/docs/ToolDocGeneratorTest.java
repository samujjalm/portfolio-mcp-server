package de.samujjal.java_net.docs;

import de.samujjal.java_net.controller.PortfolioTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates {@code docs/TOOLS.md} from the MCP {@code @Tool} annotations.
 *
 * <p>This is intentionally a plain unit test (no {@code @SpringBootTest}): it does not
 * start the application context, touch the database, or open the MCP transport. It only
 * asks {@link MethodToolCallbackProvider} to introspect the tool methods — the tool bodies
 * are never invoked — so the generated reference always matches the code. Refresh with
 * {@code ./gradlew generateToolDocs}.
 */
class ToolDocGeneratorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path OUTPUT = Path.of("docs", "TOOLS.md");

    @Test
    void generatesToolDocumentation() throws IOException {
        // PortfolioTools' dependency is never used here — the provider reads metadata only.
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new PortfolioTools(null))
                .build()
                .getToolCallbacks();

        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(callbacks));
        tools.sort(Comparator.comparing(c -> c.getToolDefinition().name()));

        StringBuilder md = new StringBuilder();
        md.append("# Portfolio MCP Server — Tool Reference\n\n");
        md.append("> Generated from the `@Tool` annotations by `ToolDocGeneratorTest`. ")
                .append("Do not edit by hand — run `./gradlew generateToolDocs` to refresh.\n\n");
        md.append("- **Server:** `portfolio-mcp` v0.0.1\n");
        md.append("- **Transport:** HTTP/SSE — connect at `http://localhost:8089/sse`\n");
        md.append("- **Tools:** ").append(tools.size()).append("\n\n");

        md.append("| Tool | Summary |\n|------|---------|\n");
        for (ToolCallback c : tools) {
            ToolDefinition d = c.getToolDefinition();
            md.append("| [`").append(d.name()).append("`](#").append(anchor(d.name())).append(") | ")
                    .append(firstSentence(d.description())).append(" |\n");
        }
        md.append("\n");

        for (ToolCallback c : tools) {
            md.append(renderTool(c.getToolDefinition()));
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, md.toString());

        // Guard: the reference must list all tools with real schemas.
        assertThat(tools).hasSize(6);
        assertThat(Files.readString(OUTPUT)).contains("executeTrade").contains("previewTrade").contains("BUY");
    }

    private String renderTool(ToolDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append("## `").append(d.name()).append("`\n\n");
        sb.append(d.description()).append("\n\n");

        JsonNode schema = MAPPER.readTree(d.inputSchema());
        JsonNode props = schema.get("properties");

        Set<String> required = new HashSet<>();
        if (schema.has("required")) {
            for (JsonNode r : schema.get("required")) {
                required.add(r.asString());
            }
        }

        if (props != null && !props.isEmpty()) {
            sb.append("**Parameters**\n\n");
            sb.append("| Name | Type | Required | Constraints | Description |\n");
            sb.append("|------|------|----------|-------------|-------------|\n");
            appendParameterRows(sb, "", props, required);
            sb.append("\n");
        } else {
            sb.append("_No parameters._\n\n");
        }

        sb.append("<details><summary>JSON Schema</summary>\n\n```json\n")
                .append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema))
                .append("\n```\n\n</details>\n\n");
        return sb.toString();
    }

    /** Renders one table row per property, recursing into nested objects with a dotted name prefix. */
    private void appendParameterRows(StringBuilder sb, String prefix, JsonNode props, Set<String> required) {
        for (Map.Entry<String, JsonNode> e : props.properties()) {
            JsonNode p = e.getValue();
            String name = prefix + e.getKey();
            sb.append("| `").append(name).append("` | ")
                    .append(describeType(p)).append(" | ")
                    .append(required.contains(e.getKey()) ? "yes" : "no").append(" | ")
                    .append(constraintsOf(p)).append(" | ")
                    .append(p.has("description") ? p.get("description").asString() : "").append(" |\n");

            if (p.has("properties")) {
                Set<String> nestedRequired = new HashSet<>();
                if (p.has("required")) {
                    for (JsonNode r : p.get("required")) {
                        nestedRequired.add(r.asString());
                    }
                }
                appendParameterRows(sb, name + ".", p.get("properties"), nestedRequired);
            }
        }
    }

    private String describeType(JsonNode p) {
        String type = p.has("type") ? p.get("type").asString() : "object";
        if (p.has("enum")) {
            List<String> values = new ArrayList<>();
            for (JsonNode v : p.get("enum")) {
                values.add(v.asString());
            }
            return type + " (enum: " + String.join(", ", values) + ")";
        }
        return type;
    }

    private String constraintsOf(JsonNode p) {
        List<String> c = new ArrayList<>();
        if (p.has("minimum")) {
            c.add("min " + p.get("minimum").asString());
        }
        if (p.has("maximum")) {
            c.add("max " + p.get("maximum").asString());
        }
        if (p.has("pattern")) {
            c.add("pattern `" + p.get("pattern").asString() + "`");
        }
        if (p.has("format")) {
            c.add("format " + p.get("format").asString());
        }
        return c.isEmpty() ? "—" : String.join(", ", c);
    }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }

    private static String anchor(String name) {
        return name.toLowerCase();
    }
}
