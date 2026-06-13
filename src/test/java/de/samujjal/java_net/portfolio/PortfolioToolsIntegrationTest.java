package de.samujjal.java_net.portfolio;

import de.samujjal.java_net.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test of the MCP tool surface against a real Postgres
 * (Testcontainers). Invokes the registered {@link ToolCallback}s exactly as the MCP
 * server does — JSON arguments in, JSON result out — exercising the full stack
 * (tool binding → service → jOOQ repository → database), including the nested
 * {@code executeTrade} request shape produced by the {@link TradeRequest} schema.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PortfolioToolsIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    @Qualifier("portfolioToolCallbacks")
    ToolCallbackProvider provider;

    private ToolCallback tool(String name) {
        return Arrays.stream(provider.getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No MCP tool named " + name));
    }

    private JsonNode call(String name, String argumentsJson) {
        return MAPPER.readTree(tool(name).call(argumentsJson));
    }

    @Test
    void registersTheFivePortfolioTools() {
        assertThat(Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder(
                        "getPortfolio", "listInstruments", "getQuote", "executeTrade", "getTradeHistory");
    }

    @Test
    void listInstrumentsReturnsSeededUniverse() {
        JsonNode instruments = call("listInstruments", "{}");
        assertThat(instruments.isArray()).isTrue();
        assertThat(instruments.size()).isEqualTo(5);
    }

    @Test
    void getQuoteReturnsIdentifiers() {
        JsonNode quote = call("getQuote", "{\"symbol\":\"AAPL\"}");
        assertThat(quote.get("isin").asString()).isEqualTo("US0378331005");
        assertThat(new BigDecimal(quote.get("lastPrice").asString())).isEqualByComparingTo("195.0000");
    }

    @Test
    void executeTradeWithNestedRequestThenPortfolioReflectsIt() {
        // BUY 10 AAPL @ 195 via the nested request shape the schema advertises.
        JsonNode buy = call("executeTrade",
                "{\"request\":{\"customerId\":1,\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":10}}");
        assertThat(buy.get("side").asString()).isEqualTo("BUY");
        assertThat(buy.get("quantity").asInt()).isEqualTo(10);
        assertThat(new BigDecimal(buy.get("cashBalance").asString())).isEqualByComparingTo("98050.0000");

        JsonNode portfolio = call("getPortfolio", "{\"customerId\":1}");
        assertThat(new BigDecimal(portfolio.get("cashBalance").asString())).isEqualByComparingTo("98050.0000");
        JsonNode positions = portfolio.get("positions");
        assertThat(positions.size()).isEqualTo(1);
        assertThat(positions.get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(positions.get(0).get("quantity").asInt()).isEqualTo(10);

        JsonNode history = call("getTradeHistory", "{\"customerId\":1}");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).get("symbol").asString()).isEqualTo("AAPL");
    }

    @Test
    void executeTradeSurfacesDomainErrors() {
        // Selling shares the customer does not own must fail through the tool layer.
        // MethodToolCallback wraps the cause in a ToolExecutionException, so assert on the root cause.
        assertThatThrownBy(() -> call("executeTrade",
                "{\"request\":{\"customerId\":1,\"symbol\":\"MSFT\",\"side\":\"SELL\",\"quantity\":1}}"))
                .rootCause()
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient shares");
    }
}
