package de.samujjal.java_net.portfolio;

/** Thrown when a trade cannot be executed (unknown entity, insufficient funds/shares). */
public class TradeException extends RuntimeException {
    public TradeException(String message) {
        super(message);
    }
}
