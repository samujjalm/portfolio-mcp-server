package de.samujjal.java_net.portfolio;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tool surface for the trading domain. Each {@code @Tool} method is
 * exposed to MCP clients (Claude Code/Desktop, MCP Inspector). Descriptions are
 * written for an LLM caller — keep them explicit.
 *
 * <p>Schema constraints (minimum, pattern, enum, required) are expressed on record
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

    @Tool(description = "List tradable instruments (symbol, name, ISIN, WKN, current price), cursor-paginated. "
            + "Returns an object with `items` and a `nextCursor`; pass nextCursor back as `cursor` for the next page.")
    public Page<Instrument> listInstruments(
            @ToolParam(required = false, description = "Max items to return (default 50, max 100)") Integer limit,
            @ToolParam(required = false, description = "Pagination cursor from a previous nextCursor; omit for the first page") String cursor) {
        return portfolioService.listInstruments(limit, cursor);
    }

    @Tool(description = "Get the current quote and identifiers (ISIN, WKN) for a single instrument, "
            + "with an ISO-8601 UTC timestamp of when the price was read.")
    public Quote getQuote(
            @ToolParam(description = "Ticker symbol — 1–6 uppercase letters, e.g. AAPL") String symbol) {
        return portfolioService.getQuote(symbol);
    }

    @Tool(description = "Execute a trade for a customer and update their portfolio. BUY appends a new FIFO lot "
            + "and debits cash; SELL consumes the oldest lots first, credits cash and reports realised P&L. "
            + "MARKET fills at the current price; LIMIT fills only if marketable against limitPrice. Idempotent "
            + "on idempotencyKey — a repeat within 5 minutes returns the original receipt. Fails on unknown "
            + "customer/symbol, insufficient funds, insufficient shares, or a non-marketable limit.")
    public TradeResult executeTrade(
            @ToolParam(description = "The trade to execute") TradeRequest request) {
        return portfolioService.executeTrade(request);
    }

    @Tool(description = "Simulate a trade WITHOUT persisting it: runs the identical FIFO matching and "
            + "funds/shares checks as executeTrade but rolls back, returning the projected execution price, "
            + "estimated cost, slippage margin, resulting cash/position, and an isValid flag (with a reason "
            + "when the trade would fail). Use this to safely check a trade before executing it.")
    public TradePreview previewTrade(
            @ToolParam(description = "The trade to simulate (same shape as executeTrade)") TradeRequest request) {
        return portfolioService.previewTrade(request);
    }

    @Tool(description = "Get a customer's trade history (most recent first), cursor-paginated. Returns an object "
            + "with `items` (including realised P&L on sells) and a `nextCursor`; pass nextCursor back as `cursor`.")
    public Page<TradeRecord> getTradeHistory(
            @ToolParam(description = "The customer's numeric id") long customerId,
            @ToolParam(required = false, description = "Max items to return (default 50, max 100)") Integer limit,
            @ToolParam(required = false, description = "Pagination cursor from a previous nextCursor; omit for the first page") String cursor) {
        return portfolioService.tradeHistory(customerId, limit, cursor);
    }
}
