package de.samujjal.java_net.portfolio;

import java.math.BigDecimal;

/**
 * An aggregated holding in a single instrument, derived from the customer's
 * open FIFO lots. {@code averagePrice} is the weighted average cost of the
 * remaining shares.
 */
public record Position(
        String symbol,
        String name,
        int quantity,
        BigDecimal averagePrice,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl) {
}
