package de.samujjal.java_net.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Input for {@link PortfolioTools#executeTrade} and {@link PortfolioTools#previewTrade}.
 * Constraints declared here flow into the tool's JSON Schema.
 */
public record TradeRequest(
        @Schema(description = "The customer's numeric id", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long customerId,

        @Schema(description = "Ticker symbol to trade, e.g. AAPL", pattern = "^[A-Z]{1,6}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String symbol,

        @Schema(description = "Trade direction", requiredMode = Schema.RequiredMode.REQUIRED)
        Side side,

        @Schema(description = "Number of shares to trade", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int quantity,

        @Schema(description = "Client-generated idempotency key (UUID). A repeated key within 5 minutes "
                + "returns the original receipt instead of executing again.",
                pattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String idempotencyKey,

        @Schema(description = "Order type; defaults to MARKET when omitted",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        OrderType orderType,

        @Schema(description = "Limit price per share. Required when orderType is LIMIT; ignored for MARKET.",
                minimum = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        BigDecimal limitPrice) {

    /** Effective order type, treating a missing value as MARKET. */
    public OrderType effectiveOrderType() {
        return orderType == null ? OrderType.MARKET : orderType;
    }
}
