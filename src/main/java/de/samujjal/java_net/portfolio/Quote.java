package de.samujjal.java_net.portfolio;

import java.math.BigDecimal;

/**
 * A point-in-time quote for an instrument. {@code timestamp} is an ISO-8601 UTC
 * instant marking when the price was read from the market-data provider.
 */
public record Quote(
        String symbol,
        String name,
        String isin,
        String wkn,
        BigDecimal lastPrice,
        String timestamp) {
}
