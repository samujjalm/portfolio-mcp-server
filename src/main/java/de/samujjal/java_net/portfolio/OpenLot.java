package de.samujjal.java_net.portfolio;

import java.math.BigDecimal;

/** An open buy lot with shares still available to sell (FIFO source of truth). */
public record OpenLot(long id, BigDecimal buyPrice, int remainingQuantity) {
}
