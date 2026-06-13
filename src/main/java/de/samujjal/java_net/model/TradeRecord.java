package de.samujjal.java_net.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A single entry in the trade ledger. */
public record TradeRecord(
        long id,
        String symbol,
        Side side,
        int quantity,
        BigDecimal price,
        BigDecimal realizedPnl,
        OffsetDateTime executedAt) {
}
