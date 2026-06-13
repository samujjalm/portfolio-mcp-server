package de.samujjal.java_net.repository;

import de.samujjal.java_net.model.Customer;
import de.samujjal.java_net.model.Instrument;
import de.samujjal.java_net.model.OpenLot;
import de.samujjal.java_net.model.Position;
import de.samujjal.java_net.model.Side;
import de.samujjal.java_net.model.TradeRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static de.samujjal.java_net.jooq.Tables.CUSTOMER;
import static de.samujjal.java_net.jooq.Tables.HOLDING_LOT;
import static de.samujjal.java_net.jooq.Tables.INSTRUMENT;
import static de.samujjal.java_net.jooq.Tables.TRADE;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.sum;

/**
 * Data access for the trading domain, using the type-safe jOOQ DSL over the metamodel
 * generated from the Flyway migrations ({@code de.samujjal.java_net.jooq.Tables}). Query
 * results are mapped into the immutable domain records the rest of the app uses.
 */
@Repository
public class PortfolioRepository {

    // Aggregates over a customer's open lots, shared by the position queries.
    private static final Field<BigDecimal> OPEN_QUANTITY = sum(HOLDING_LOT.REMAINING_QUANTITY);
    private static final Field<BigDecimal> COST_BASIS =
            sum(HOLDING_LOT.BUY_PRICE.times(HOLDING_LOT.REMAINING_QUANTITY));

    private final DSLContext dsl;

    public PortfolioRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Customer> findCustomer(long customerId) {
        return dsl.selectFrom(CUSTOMER)
                .where(CUSTOMER.ID.eq(customerId))
                .fetchOptional()
                .map(r -> new Customer(r.getId(), r.getName(), r.getCashBalance()));
    }

    public void updateCash(long customerId, BigDecimal newBalance) {
        dsl.update(CUSTOMER)
                .set(CUSTOMER.CASH_BALANCE, newBalance)
                .where(CUSTOMER.ID.eq(customerId))
                .execute();
    }

    public Optional<Instrument> findInstrument(String symbol) {
        return dsl.selectFrom(INSTRUMENT)
                .where(INSTRUMENT.SYMBOL.eq(symbol))
                .fetchOptional()
                .map(r -> new Instrument(r.getSymbol(), r.getName(), r.getIsin(), r.getWkn(), r.getLastPrice()));
    }

    /** Instruments ordered by symbol, keyset-paginated: up to {@code limit} rows with symbol &gt; {@code afterSymbol}. */
    public List<Instrument> listInstruments(String afterSymbol, int limit) {
        return dsl.selectFrom(INSTRUMENT)
                .where(afterSymbol == null ? noCondition() : INSTRUMENT.SYMBOL.gt(afterSymbol))
                .orderBy(INSTRUMENT.SYMBOL)
                .limit(limit)
                .fetch(r -> new Instrument(r.getSymbol(), r.getName(), r.getIsin(), r.getWkn(), r.getLastPrice()));
    }

    public void insertLot(long customerId, String symbol, BigDecimal buyPrice, int quantity) {
        dsl.insertInto(HOLDING_LOT)
                .set(HOLDING_LOT.CUSTOMER_ID, customerId)
                .set(HOLDING_LOT.SYMBOL, symbol)
                .set(HOLDING_LOT.BUY_PRICE, buyPrice)
                .set(HOLDING_LOT.ORIGINAL_QUANTITY, quantity)
                .set(HOLDING_LOT.REMAINING_QUANTITY, quantity)
                .execute();
    }

    /** Open lots for a holding, ordered oldest-first for FIFO consumption. */
    public List<OpenLot> openLotsFifo(long customerId, String symbol) {
        return dsl.select(HOLDING_LOT.ID, HOLDING_LOT.BUY_PRICE, HOLDING_LOT.REMAINING_QUANTITY)
                .from(HOLDING_LOT)
                .where(HOLDING_LOT.CUSTOMER_ID.eq(customerId))
                .and(HOLDING_LOT.SYMBOL.eq(symbol))
                .and(HOLDING_LOT.REMAINING_QUANTITY.gt(0))
                .orderBy(HOLDING_LOT.ACQUIRED_AT, HOLDING_LOT.ID)
                .fetch(r -> new OpenLot(
                        r.get(HOLDING_LOT.ID),
                        r.get(HOLDING_LOT.BUY_PRICE),
                        r.get(HOLDING_LOT.REMAINING_QUANTITY)));
    }

    public void updateLotRemaining(long lotId, int remainingQuantity) {
        dsl.update(HOLDING_LOT)
                .set(HOLDING_LOT.REMAINING_QUANTITY, remainingQuantity)
                .where(HOLDING_LOT.ID.eq(lotId))
                .execute();
    }

    public long insertTrade(long customerId, String symbol, Side side, int quantity,
                            BigDecimal price, BigDecimal realizedPnl) {
        return dsl.insertInto(TRADE)
                .set(TRADE.CUSTOMER_ID, customerId)
                .set(TRADE.SYMBOL, symbol)
                .set(TRADE.SIDE, side.name())
                .set(TRADE.QUANTITY, quantity)
                .set(TRADE.PRICE, price)
                .set(TRADE.REALIZED_PNL, realizedPnl)
                .returningResult(TRADE.ID)
                .fetchSingle()
                .value1();
    }

    /**
     * Trade ledger newest-first, keyset-paginated: up to {@code limit} rows with id &lt; {@code beforeId}
     * (pass {@code null} for the first page). Ordering by id desc matches executed-at desc for our inserts.
     */
    public List<TradeRecord> tradeHistory(long customerId, Long beforeId, int limit) {
        return dsl.select(TRADE.ID, TRADE.SYMBOL, TRADE.SIDE, TRADE.QUANTITY,
                        TRADE.PRICE, TRADE.REALIZED_PNL, TRADE.EXECUTED_AT)
                .from(TRADE)
                .where(TRADE.CUSTOMER_ID.eq(customerId))
                .and(beforeId == null ? noCondition() : TRADE.ID.lt(beforeId))
                .orderBy(TRADE.ID.desc())
                .limit(limit)
                .fetch(r -> new TradeRecord(
                        r.get(TRADE.ID),
                        r.get(TRADE.SYMBOL),
                        Side.valueOf(r.get(TRADE.SIDE)),
                        r.get(TRADE.QUANTITY),
                        r.get(TRADE.PRICE),
                        r.get(TRADE.REALIZED_PNL),
                        r.get(TRADE.EXECUTED_AT)));
    }

    /** All aggregated positions for a customer, derived from open FIFO lots. */
    public List<Position> positions(long customerId) {
        return dsl.select(HOLDING_LOT.SYMBOL, INSTRUMENT.NAME, INSTRUMENT.LAST_PRICE, OPEN_QUANTITY, COST_BASIS)
                .from(HOLDING_LOT)
                .join(INSTRUMENT).on(INSTRUMENT.SYMBOL.eq(HOLDING_LOT.SYMBOL))
                .where(HOLDING_LOT.CUSTOMER_ID.eq(customerId))
                .and(HOLDING_LOT.REMAINING_QUANTITY.gt(0))
                .groupBy(HOLDING_LOT.SYMBOL, INSTRUMENT.NAME, INSTRUMENT.LAST_PRICE)
                .orderBy(HOLDING_LOT.SYMBOL)
                .fetch(PortfolioRepository::toPosition);
    }

    /** A single aggregated position, or empty if the customer holds no open shares of it. */
    public Optional<Position> position(long customerId, String symbol) {
        return dsl.select(HOLDING_LOT.SYMBOL, INSTRUMENT.NAME, INSTRUMENT.LAST_PRICE, OPEN_QUANTITY, COST_BASIS)
                .from(HOLDING_LOT)
                .join(INSTRUMENT).on(INSTRUMENT.SYMBOL.eq(HOLDING_LOT.SYMBOL))
                .where(HOLDING_LOT.CUSTOMER_ID.eq(customerId))
                .and(HOLDING_LOT.SYMBOL.eq(symbol))
                .and(HOLDING_LOT.REMAINING_QUANTITY.gt(0))
                .groupBy(HOLDING_LOT.SYMBOL, INSTRUMENT.NAME, INSTRUMENT.LAST_PRICE)
                .fetchOptional(PortfolioRepository::toPosition);
    }

    private static Position toPosition(Record r) {
        int quantity = r.get(OPEN_QUANTITY).intValue();
        BigDecimal basis = r.get(COST_BASIS);
        BigDecimal lastPrice = r.get(INSTRUMENT.LAST_PRICE);
        BigDecimal avgPrice = basis.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        BigDecimal marketValue = lastPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal unrealizedPnl = marketValue.subtract(basis);
        return new Position(
                r.get(HOLDING_LOT.SYMBOL),
                r.get(INSTRUMENT.NAME),
                quantity,
                avgPrice,
                lastPrice,
                marketValue,
                unrealizedPnl);
    }
}
