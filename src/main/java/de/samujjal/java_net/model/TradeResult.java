package de.samujjal.java_net.model;

import java.math.BigDecimal;

/**
 * Outcome of an executed trade. {@code realizedPnl} is populated for SELLs
 * (proceeds minus FIFO cost basis) and {@code null} for BUYs.
 * {@code resultingPosition} is {@code null} when the position is fully closed.
 */
public record TradeResult(
        long tradeId,
        String symbol,
        Side side,
        int quantity,
        BigDecimal price,
        BigDecimal realizedPnl,
        BigDecimal cashBalance,
        Position resultingPosition) {
}
