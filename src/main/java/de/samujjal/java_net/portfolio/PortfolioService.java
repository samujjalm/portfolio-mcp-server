package de.samujjal.java_net.portfolio;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Trading logic over the portfolio. BUYs append a new FIFO lot; SELLs consume
 * the oldest open lots first, computing realized P&L from their cost basis.
 */
@Service
public class PortfolioService {

    private static final int MAX_HISTORY = 100;

    private final PortfolioRepository repository;

    public PortfolioService(PortfolioRepository repository) {
        this.repository = repository;
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
    public List<Instrument> listInstruments() {
        return repository.listInstruments();
    }

    @Transactional(readOnly = true)
    public Instrument getQuote(String symbol) {
        return requireInstrument(symbol);
    }

    @Transactional(readOnly = true)
    public List<TradeRecord> tradeHistory(long customerId) {
        requireCustomer(customerId);
        return repository.tradeHistory(customerId, MAX_HISTORY);
    }

    @Transactional
    public TradeResult executeTrade(long customerId, String symbol, Side side, int quantity) {
        if (quantity <= 0) {
            throw new TradeException("Quantity must be a positive whole number, got " + quantity);
        }
        Customer customer = requireCustomer(customerId);
        Instrument instrument = requireInstrument(symbol);
        BigDecimal price = instrument.lastPrice();

        return side == Side.BUY
                ? buy(customer, instrument, price, quantity)
                : sell(customer, instrument, price, quantity);
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

    private Customer requireCustomer(long customerId) {
        return repository.findCustomer(customerId)
                .orElseThrow(() -> new TradeException("No customer with id " + customerId));
    }

    private Instrument requireInstrument(String symbol) {
        return repository.findInstrument(symbol)
                .orElseThrow(() -> new TradeException("Unknown instrument symbol '" + symbol + "'"));
    }
}
