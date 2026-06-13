package de.samujjal.java_net.controller;

import de.samujjal.java_net.TestcontainersConfiguration;
import de.samujjal.java_net.model.TradeException;
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
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test of the MCP tool surface against a real Postgres
 * (Testcontainers). Invokes the registered {@link ToolCallback}s exactly as the MCP
 * server does — JSON arguments in, JSON result out — exercising the full stack
 * (tool binding → service → jOOQ repository → database).
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
    void registersThePortfolioTools() {
        assertThat(Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder(
                        "getPortfolio", "listInstruments", "getQuote",
                        "executeTrade", "previewTrade", "getTradeHistory");
    }

    @Test
    void listInstrumentsReturnsPagedEnvelope() {
        JsonNode page = call("listInstruments", "{}");
        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items").size()).isEqualTo(5);
        assertThat(page.has("nextCursor")).isTrue(); // null here: all 5 fit in one page
    }

    @Test
    void listInstrumentsPaginatesWithCursor() {
        JsonNode first = call("listInstruments", "{\"limit\":2}");
        assertThat(first.get("items").size()).isEqualTo(2);
        String cursor = first.get("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        JsonNode second = call("listInstruments", "{\"limit\":2,\"cursor\":\"" + cursor + "\"}");
        assertThat(second.get("items").size()).isEqualTo(2);
        assertThat(second.get("items").get(0).get("symbol").asString()).isGreaterThan(cursor);
    }

    @Test
    void getQuoteReturnsIdentifiersAndIsoTimestamp() {
        JsonNode quote = call("getQuote", "{\"symbol\":\"AAPL\"}");
        assertThat(quote.get("isin").asString()).isEqualTo("US0378331005");
        assertThat(new BigDecimal(quote.get("lastPrice").asString())).isEqualByComparingTo("195.0000");
        assertThat(Instant.parse(quote.get("timestamp").asString())).isNotNull();
    }

    @Test
    void executeTradeWithNestedRequestThenPortfolioReflectsIt() {
        JsonNode buy = call("executeTrade", """
                {"request":{"customerId":1,"symbol":"AAPL","side":"BUY","quantity":10,
                 "idempotencyKey":"11111111-1111-1111-1111-111111111111","orderType":"MARKET"}}""");
        assertThat(buy.get("side").asString()).isEqualTo("BUY");
        assertThat(new BigDecimal(buy.get("cashBalance").asString())).isEqualByComparingTo("98050.0000");

        JsonNode portfolio = call("getPortfolio", "{\"customerId\":1}");
        assertThat(new BigDecimal(portfolio.get("cashBalance").asString())).isEqualByComparingTo("98050.0000");
        assertThat(portfolio.get("positions").size()).isEqualTo(1);
        assertThat(portfolio.get("positions").get(0).get("symbol").asString()).isEqualTo("AAPL");

        JsonNode history = call("getTradeHistory", "{\"customerId\":1}");
        assertThat(history.get("items").size()).isEqualTo(1);
        assertThat(history.get("items").get(0).get("symbol").asString()).isEqualTo("AAPL");
    }

    @Test
    void previewTradeProjectsOutcomeWithoutPersisting() {
        JsonNode preview = call("previewTrade", """
                {"request":{"customerId":1,"symbol":"AAPL","side":"BUY","quantity":10,
                 "idempotencyKey":"22222222-2222-2222-2222-222222222222","orderType":"MARKET"}}""");
        assertThat(preview.get("isValid").asBoolean()).isTrue();
        assertThat(new BigDecimal(preview.get("estimatedCost").asString())).isEqualByComparingTo("1950.0000");
        assertThat(new BigDecimal(preview.get("projectedCashBalance").asString())).isEqualByComparingTo("98050.0000");

        // Simulation must not persist: the portfolio is unchanged.
        JsonNode portfolio = call("getPortfolio", "{\"customerId\":1}");
        assertThat(new BigDecimal(portfolio.get("cashBalance").asString())).isEqualByComparingTo("100000.0000");
        assertThat(portfolio.get("positions").size()).isEqualTo(0);
    }

    @Test
    void executeTradeSurfacesDomainErrors() {
        // MethodToolCallback wraps the cause in a ToolExecutionException; assert on the root cause.
        assertThatThrownBy(() -> call("executeTrade", """
                {"request":{"customerId":1,"symbol":"MSFT","side":"SELL","quantity":1,
                 "idempotencyKey":"33333333-3333-3333-3333-333333333333"}}"""))
                .rootCause()
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient shares");
    }
}
