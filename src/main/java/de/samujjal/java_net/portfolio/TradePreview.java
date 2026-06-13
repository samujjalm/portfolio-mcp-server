package de.samujjal.java_net.portfolio;

import java.math.BigDecimal;

/**
 * Projected outcome of a simulated trade (see {@link PortfolioService#previewTrade}).
 * The simulation runs the identical FIFO/validation logic as a real trade but its
 * database transaction is rolled back, so nothing is persisted.
 *
 * <p>When {@code isValid} is {@code false}, {@code reason} explains why the trade would
 * fail and the projection fields are {@code null}.
 */
public record TradePreview(
        boolean isValid,
        String reason,
        String symbol,
        Side side,
        int quantity,
        BigDecimal estimatedPrice,
        BigDecimal estimatedCost,
        BigDecimal slippageMargin,
        BigDecimal projectedCashBalance,
        Position projectedPosition) {
}
