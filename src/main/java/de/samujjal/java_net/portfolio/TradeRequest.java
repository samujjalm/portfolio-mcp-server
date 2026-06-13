package de.samujjal.java_net.portfolio;

import io.swagger.v3.oas.annotations.media.Schema;

/** Input for {@link PortfolioTools#executeTrade}. Constraints here flow into the tool's JSON Schema. */
public record TradeRequest(
        @Schema(description = "The customer's numeric id", minimum = "1")
        long customerId,

        @Schema(description = "Ticker symbol to trade, e.g. AAPL", pattern = "^[A-Z]{1,6}$")
        String symbol,

        @Schema(description = "Trade direction")
        Side side,

        @Schema(description = "Number of shares to trade", minimum = "1")
        int quantity) {
}
