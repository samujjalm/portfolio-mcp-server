package de.samujjal.java_net.service;

import de.samujjal.java_net.model.Customer;
import de.samujjal.java_net.model.Instrument;
import de.samujjal.java_net.model.OpenLot;
import de.samujjal.java_net.model.OrderType;
import de.samujjal.java_net.model.Page;
import de.samujjal.java_net.model.PortfolioView;
import de.samujjal.java_net.model.Position;
import de.samujjal.java_net.model.Quote;
import de.samujjal.java_net.model.Side;
import de.samujjal.java_net.model.TradeException;
import de.samujjal.java_net.model.TradePreview;
import de.samujjal.java_net.model.TradeRecord;
import de.samujjal.java_net.model.TradeRequest;
import de.samujjal.java_net.model.TradeResult;
import de.samujjal.java_net.repository.PortfolioRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Trading logic over the portfolio. BUYs append a new FIFO lot; SELLs consume the
 * oldest open lots first, computing realized P&L from their cost basis.
 *
 * <p>Writes run through a {@link TransactionTemplate} (rather than {@code @Transactional}
 * self-invocation) so that {@link #executeTrade} can layer idempotency caching around the
 * transaction and {@link #previewTrade} can run the identical logic in a separate
 * transaction that is always rolled back.
 */
@Service
public class PortfolioService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

    /** Thrown solely to force rollback of a {@link #previewTrade} simulation; never escapes the method. */
    private static final class PreviewRollback extends RuntimeException {
        private PreviewRollback() {
            super(null, null, false, false);
        }
    }

    private static final PreviewRollback PREVIEW_ROLLBACK = new PreviewRollback();

    private final PortfolioRepository repository;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate previewTransaction;
    private final Cache<String, TradeResult> idempotencyCache;

    public PortfolioService(PortfolioRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.previewTransaction = new TransactionTemplate(transactionManager);
        // A dedicated, isolated transaction so the rollback never touches a caller's transaction.
        this.previewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.idempotencyCache = Caffeine.newBuilder()
                .expireAfterWrite(IDEMPOTENCY_TTL)
                .maximumSize(10_000)
                .build();
    }

    @Transactional(readOnly = true)
    public PortfolioView getPortfolio(long customerId) {
        Customer customer = requireCustomer(customerId);
        List<Position> positions = repository.positions(customerId);
        BigDecimal positionsValue = positions.stream()
                .map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PortfolioView(
                customer.id(),
                customer.name(),
                customer.cashBalance(),
                positions,
                positionsValue,
                customer.cashBalance().add(positionsValue));
    }

    @Transactional(readOnly = true)
    public Page<Instrument> listInstruments(Integer limit, String cursor) {
        int pageSize = clampPageSize(limit);
        List<Instrument> rows = repository.listInstruments(cursor, pageSize + 1);
        return toPage(rows, pageSize, Instrument::symbol);
    }

    @Transactional(readOnly = true)
    public Quote getQuote(String symbol) {
        Instrument instrument = requireInstrument(symbol);
        String fetchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return new Quote(
                instrument.symbol(),
                instrument.name(),
                instrument.isin(),
                instrument.wkn(),
                instrument.lastPrice(),
                fetchedAt);
    }

    @Transactional(readOnly = true)
    public Page<TradeRecord> tradeHistory(long customerId, Integer limit, String cursor) {
        requireCustomer(customerId);
        int pageSize = clampPageSize(limit);
        Long beforeId = (cursor == null || cursor.isBlank()) ? null : Long.parseLong(cursor.trim());
        List<TradeRecord> rows = repository.tradeHistory(customerId, beforeId, pageSize + 1);
        return toPage(rows, pageSize, TradeRecord::id);
    }

    /**
     * Executes a trade, persisting the result. Idempotent on {@link TradeRequest#idempotencyKey()}:
     * a repeated key within the TTL returns the cached receipt without executing again.
     */
    public TradeResult executeTrade(TradeRequest request) {
        String key = request.idempotencyKey();
        if (key != null && !key.isBlank()) {
            TradeResult cached = idempotencyCache.getIfPresent(key);
            if (cached != null) {
                return cached;
            }
        }
        TradeResult result = writeTransaction.execute(status -> executeCore(request));
        if (key != null && !key.isBlank()) {
            idempotencyCache.put(key, result);
        }
        return result;
    }

    /**
     * Simulates a trade: runs the identical FIFO/validation logic as {@link #executeTrade}, then
     * rolls back its transaction so nothing is persisted. Returns the projected outcome.
     */
    public TradePreview previewTrade(TradeRequest request) {
        AtomicReference<TradePreview> holder = new AtomicReference<>();
        try {
            previewTransaction.executeWithoutResult(status -> {
                holder.set(computePreview(request));
                throw PREVIEW_ROLLBACK;
            });
        } catch (PreviewRollback expected) {
            // The simulation transaction has been rolled back; the projection is in `holder`.
        }
        return holder.get();
    }

    private TradePreview computePreview(TradeRequest request) {
        try {
            TradeResult projected = executeCore(request);
            BigDecimal estimatedCost = projected.price().multiply(BigDecimal.valueOf(projected.quantity()));
            return new TradePreview(
                    true, null,
                    projected.symbol(), projected.side(), projected.quantity(),
                    projected.price(), estimatedCost, slippageMargin(request, projected.price()),
                    projected.cashBalance(), projected.resultingPosition());
        } catch (TradeException invalid) {
            return new TradePreview(
                    false, invalid.getMessage(),
                    request.symbol(), request.side(), request.quantity(),
                    null, null, null, null, null);
        }
    }

    /** Shared trade logic used by both real execution and simulation. */
    private TradeResult executeCore(TradeRequest request) {
        if (request.quantity() <= 0) {
            throw new TradeException("Quantity must be a positive whole number, got " + request.quantity());
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new TradeException("idempotencyKey is required");
        }
        Customer customer = requireCustomer(request.customerId());
        Instrument instrument = requireInstrument(request.symbol());
        BigDecimal executionPrice = resolveExecutionPrice(request, instrument);

        return request.side() == Side.BUY
                ? buy(customer, instrument, executionPrice, request.quantity())
                : sell(customer, instrument, executionPrice, request.quantity());
    }

    /**
     * Resolves the fill price. MARKET fills at the current price. LIMIT requires a limit price and
     * only fills when marketable (BUY: market &le; limit; SELL: market &ge; limit), filling at the
     * current market price (honouring any price improvement).
     */
    private BigDecimal resolveExecutionPrice(TradeRequest request, Instrument instrument) {
        BigDecimal market = instrument.lastPrice();
        if (request.effectiveOrderType() != OrderType.LIMIT) {
            return market;
        }
        BigDecimal limit = request.limitPrice();
        if (limit == null) {
            throw new TradeException("limitPrice is required for LIMIT orders");
        }
        if (request.side() == Side.BUY && market.compareTo(limit) > 0) {
            throw new TradeException("LIMIT BUY not marketable: market price %s for %s exceeds limit %s"
                    .formatted(market, instrument.symbol(), limit));
        }
        if (request.side() == Side.SELL && market.compareTo(limit) < 0) {
            throw new TradeException("LIMIT SELL not marketable: market price %s for %s is below limit %s"
                    .formatted(market, instrument.symbol(), limit));
        }
        return market;
    }

    private BigDecimal slippageMargin(TradeRequest request, BigDecimal executionPrice) {
        if (request.effectiveOrderType() != OrderType.LIMIT || request.limitPrice() == null) {
            return BigDecimal.ZERO;
        }
        // Favourable margin between the limit and the actual fill.
        return request.side() == Side.BUY
                ? request.limitPrice().subtract(executionPrice)
                : executionPrice.subtract(request.limitPrice());
    }

    private TradeResult buy(Customer customer, Instrument instrument, BigDecimal price, int quantity) {
        BigDecimal cost = price.multiply(BigDecimal.valueOf(quantity));
        if (customer.cashBalance().compareTo(cost) < 0) {
            throw new TradeException("Insufficient funds: buying %d %s costs %s but cash balance is %s"
                    .formatted(quantity, instrument.symbol(), cost, customer.cashBalance()));
        }
        BigDecimal newCash = customer.cashBalance().subtract(cost);
        repository.updateCash(customer.id(), newCash);
        repository.insertLot(customer.id(), instrument.symbol(), price, quantity);
        long tradeId = repository.insertTrade(customer.id(), instrument.symbol(), Side.BUY, quantity, price, null);

        return new TradeResult(tradeId, instrument.symbol(), Side.BUY, quantity, price, null, newCash,
                repository.position(customer.id(), instrument.symbol()).orElse(null));
    }

    private TradeResult sell(Customer customer, Instrument instrument, BigDecimal price, int quantity) {
        List<OpenLot> lots = repository.openLotsFifo(customer.id(), instrument.symbol());
        int available = lots.stream().mapToInt(OpenLot::remainingQuantity).sum();
        if (available < quantity) {
            throw new TradeException("Insufficient shares: tried to sell %d %s but only %d are held"
                    .formatted(quantity, instrument.symbol(), available));
        }

        int remainingToSell = quantity;
        BigDecimal costBasis = BigDecimal.ZERO;
        for (OpenLot lot : lots) {
            if (remainingToSell == 0) {
                break;
            }
            int taken = Math.min(remainingToSell, lot.remainingQuantity());
            costBasis = costBasis.add(lot.buyPrice().multiply(BigDecimal.valueOf(taken)));
            repository.updateLotRemaining(lot.id(), lot.remainingQuantity() - taken);
            remainingToSell -= taken;
        }

        BigDecimal proceeds = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal realizedPnl = proceeds.subtract(costBasis);
        BigDecimal newCash = customer.cashBalance().add(proceeds);
        repository.updateCash(customer.id(), newCash);
        long tradeId = repository.insertTrade(
                customer.id(), instrument.symbol(), Side.SELL, quantity, price, realizedPnl);

        return new TradeResult(tradeId, instrument.symbol(), Side.SELL, quantity, price, realizedPnl, newCash,
                repository.position(customer.id(), instrument.symbol()).orElse(null));
    }

    private int clampPageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    private <T> Page<T> toPage(List<T> rows, int pageSize, Function<T, Object> cursorOf) {
        if (rows.size() > pageSize) {
            List<T> items = List.copyOf(rows.subList(0, pageSize));
            return new Page<>(items, String.valueOf(cursorOf.apply(items.get(pageSize - 1))));
        }
        return new Page<>(List.copyOf(rows), null);
    }

    private Customer requireCustomer(long customerId) {
        return repository.findCustomer(customerId)
                .orElseThrow(() -> new TradeException("No customer with id " + customerId));
    }

    private Instrument requireInstrument(String symbol) {
        return repository.findInstrument(symbol)
                .orElseThrow(() -> new TradeException("Unknown instrument symbol '" + symbol + "'"));
    }
}
