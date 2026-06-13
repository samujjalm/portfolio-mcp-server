# Portfolio MCP Server (`java-net`)

A stock-trading / portfolio backend exposed as a **Model Context Protocol (MCP)** server, so
AI agents (Claude Code, Claude Desktop, the MCP Inspector) can explore a customer's portfolio
and execute trades over a tool API.

Built on **Spring Boot 4.1 / Java 26**, with **Spring AI** for the MCP server, **jOOQ** for
type-safe data access, **Flyway** for schema migrations, and **PostgreSQL** for storage.

---

## What it does

A customer holds cash and a portfolio of stocks. Holdings are tracked as a **FIFO chain of buy
lots** — a sell consumes the oldest lots first and reports realised P&L. The server exposes
six MCP tools:

| Tool | Purpose |
|------|---------|
| `getPortfolio` | Cash, every position (qty, avg cost, market value, unrealised P&L), total value |
| `listInstruments` | Tradable instruments (symbol, name, ISIN, WKN, price) — cursor-paginated |
| `getQuote` | Current price + identifiers for one instrument, with an ISO-8601 timestamp |
| `executeTrade` | Buy/sell (MARKET or LIMIT); idempotent; updates the portfolio |
| `previewTrade` | Simulate a trade (same logic, rolled back) — projected price/cost/validity |
| `getTradeHistory` | Trade ledger, newest first — cursor-paginated |

The full, always-current tool reference (descriptions, JSON Schemas, constraints) lives in
[`docs/TOOLS.md`](docs/TOOLS.md), generated from the code (see [below](#regenerating-artifacts)).

Safety features for autonomous agents: **idempotency keys** on `executeTrade` (a repeat within
5 minutes returns the original receipt), **LIMIT orders** (only fill when marketable), and
**`previewTrade`** for a risk-free dry run.

---

## Architecture

A standard three-tier layout under `src/main/java/de/samujjal/java_net/`:

```
controller/   PortfolioTools        — the MCP tool surface (@Tool methods)  ─┐
service/      PortfolioService      — FIFO logic, idempotency, limit orders  │ depends downward
repository/   PortfolioRepository   — type-safe jOOQ queries                ─┘
model/        records, enums, exception (shared across tiers)
config/       McpConfig, SecurityConfig
jooq/         generated jOOQ metamodel (build output, not committed)
```

Database: `customer`, `instrument` (with ISIN + nullable WKN), `holding_lot` (FIFO source of
truth), `trade` (ledger). Schema lives in `src/main/resources/db/migration/` (Flyway).

---

## Prerequisites

- **JDK 26** (the Gradle toolchain will fetch/validate it).
- **Docker** running — used two ways:
  - `bootRun` auto-starts PostgreSQL from `compose.yaml` (Spring Boot Docker Compose support).
  - Tests spin up PostgreSQL via Testcontainers.

No local Postgres install or manual DB setup is required.

---

## Build & run

```bash
# Build everything (compiles, generates jOOQ + docs, runs all tests)
./gradlew build

# Run the server (auto-starts the Postgres container, applies Flyway migrations + seed data)
./gradlew bootRun
```

The server listens on **http://localhost:8080**, with the MCP SSE endpoint at **`/sse`**.
On startup the log prints `Registered tools: 6` and `Started JavaNetApplication`.

Seed data (from `V2__seed_data.sql`): one **Demo Customer (id = 1)** with €100,000 cash, and
instruments **AAPL, MSFT, NVDA, SAP, TSLA**.

> The dev database runs in a Docker volume and persists between runs. To reset it:
> `docker compose down -v` (removes the `java-net-pgdata` volume), then `./gradlew bootRun`.

---

## Running the tests

```bash
./gradlew test          # run all tests (requires Docker for Testcontainers)
./gradlew clean test    # from scratch
```

33 tests across the tiers, all against a real PostgreSQL (Testcontainers):

- `repository/PortfolioRepositoryTest` — jOOQ queries: FIFO ordering, weighted-average
  aggregation, keyset pagination, ISIN/nullable-WKN mapping.
- `service/PortfolioServiceTest` — FIFO accounting & realised P&L, idempotency replay, limit
  orders, `previewTrade` non-persistence, pagination, quote freshness.
- `controller/PortfolioToolsIntegrationTest` — drives the registered MCP `ToolCallback`s
  (JSON in / JSON out) end-to-end through the full stack.
- `docs/ToolDocGeneratorTest` — regenerates `docs/TOOLS.md` and guards the tool set.
- `JavaNetApplicationTests` — context loads.

Reports: `build/reports/tests/test/index.html`.

---

## Testing the running service

Start it first with `./gradlew bootRun`, then use any of the following.

### Option A — MCP Inspector (easiest visual check)

```bash
npx @modelcontextprotocol/inspector
```

In the UI, choose transport **SSE** and connect to `http://localhost:8080/sse`. You'll see all
six tools with their schemas, and can invoke them with a form.

### Option B — Claude Code (use it as an agent)

Register the server, then talk to it in natural language:

```bash
claude mcp add --transport sse portfolio http://localhost:8080/sse
claude mcp list                  # verify it's connected
```

Then, in a Claude Code session, try prompts like:

- "Using the portfolio server, show me customer 1's portfolio."
- "Get a quote for NVDA, then preview buying 50 shares for customer 1 — is it valid and what's the cost?"
- "Buy 10 AAPL for customer 1 as a MARKET order, then show the updated portfolio and trade history."
- "Place a LIMIT buy of 5 MSFT for customer 1 at a limit price of 400 and tell me what happened."

The agent discovers the tools and their JSON Schemas automatically, picks the right ones, and
chains them. Generate an idempotency key per `executeTrade` (any UUID) — the agent will do this
when asked to trade.

To remove it later: `claude mcp remove portfolio`.

### Option C — Raw curl (no extra tooling)

MCP over SSE is two-channel: open the event stream with `GET /sse` (responses arrive here),
and POST requests to the session endpoint it hands back.

```bash
# 1. Open the SSE stream in the background; capture the session message endpoint.
curl -sN http://localhost:8080/sse > /tmp/sse.out &
sleep 1
EP=$(grep -m1 'data:' /tmp/sse.out | sed 's/^data: *//' | tr -d '\r')   # e.g. /mcp/message?sessionId=...

post() { curl -s -o /dev/null -X POST "http://localhost:8080$EP" \
           -H 'Content-Type: application/json' -d "$1"; }

# 2. Initialise the session.
post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
post '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. List tools.
post '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 4. Call a tool — get the demo customer's portfolio.
post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getPortfolio","arguments":{"customerId":1}}}'

# 5. Execute a trade (note the nested "request" object and the required idempotencyKey UUID).
post '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"executeTrade","arguments":{"request":{"customerId":1,"symbol":"AAPL","side":"BUY","quantity":10,"idempotencyKey":"11111111-1111-1111-1111-111111111111","orderType":"MARKET"}}}}'

# Responses (JSON-RPC results) appear in the SSE stream:
cat /tmp/sse.out
```

> Tool results come back on the `GET /sse` stream as `event:message` / `data:` lines, not as the
> POST response body. The POST returns `200` to acknowledge receipt.

---

## Regenerating artifacts

```bash
./gradlew generateJooq      # regenerate the jOOQ metamodel from the Flyway migrations
./gradlew generateToolDocs  # regenerate docs/TOOLS.md from the @Tool annotations (no server/DB needed)
```

Both also run automatically as part of `./gradlew build`.

---

## Notes & caveats

- **Local/dev security:** Spring Security is configured to permit all requests so the MCP
  endpoints are reachable. Add real authentication before exposing this beyond localhost
  (see `config/SecurityConfig.java`).
- **Limit orders:** there's no resting order book — a marketable LIMIT order fills at the
  current market price (honouring price improvement); a non-marketable one is rejected, not queued.
- **`previewTrade` isolation:** runs in its own transaction that is always rolled back, so it
  reads committed state and never persists or affects a concurrent operation.
- **IDE setup:** the jOOQ metamodel is generated under `build/generated-src/jooq/main`. After
  cloning or pulling, run `./gradlew generateJooq` (or refresh Gradle in your IDE) so imports
  like `de.samujjal.java_net.jooq.Tables` resolve.
