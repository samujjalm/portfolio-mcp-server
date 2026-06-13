package de.samujjal.java_net.portfolio;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The MCP tool surface for the trading domain. Each {@code @Tool} method is
 * exposed to MCP clients (Claude Code/Desktop, MCP Inspector). Descriptions are
 * written for an LLM caller — keep them explicit.
 *
 * <p>Schema constraints (minimum, pattern, examples) are expressed on record
 * types like {@link TradeRequest}: Spring AI's schema generator reads Swagger
 * {@code @Schema} on a type's components, not on loose method parameters. For
 * single-parameter tools we keep the parameter flat and fold any constraint into
 * the human-readable description instead.
 */
@Component
public class PortfolioTools {

    private final PortfolioService portfolioService;

    public PortfolioTools(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @Tool(description = "Get a customer's full portfolio: cash balance, every stock position "
            + "(quantity, average cost, current price, market value, unrealised P&L) and total account value.")
    public PortfolioView getPortfolio(
            @ToolParam(description = "The customer's numeric id") long customerId) {
        return portfolioService.getPortfolio(customerId);
    }

    @Tool(description = "List all tradable instruments with their symbol, name, ISIN, WKN and current price.")
    public List<Instrument> listInstruments() {
        return portfolioService.listInstruments();
    }

    @Tool(description = "Get the current quote and identifiers (ISIN, WKN) for a single instrument by its ticker symbol.")
    public Instrument getQuote(
            @ToolParam(description = "Ticker symbol — 1–6 uppercase letters, e.g. AAPL") String symbol) {
        return portfolioService.getQuote(symbol);
    }

    @Tool(description = "Execute a trade for a customer at the instrument's current price and update their "
            + "portfolio. BUY appends a new FIFO lot and debits cash; SELL consumes the oldest lots first, "
            + "credits cash and reports realised P&L. Fails on unknown customer/symbol, insufficient funds, "
            + "or insufficient shares.")
    public TradeResult executeTrade(
            @ToolParam(description = "The trade to execute") TradeRequest request) {
        return portfolioService.executeTrade(
                request.customerId(), request.symbol(), request.side(), request.quantity());
    }

    @Tool(description = "Get a customer's recent trade history (most recent first), including realised P&L on sells.")
    public List<TradeRecord> getTradeHistory(
            @ToolParam(description = "The customer's numeric id") long customerId) {
        return portfolioService.tradeHistory(customerId);
    }
}
