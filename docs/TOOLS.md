# Portfolio MCP Server — Tool Reference

> Generated from the `@Tool` annotations by `ToolDocGeneratorTest`. Do not edit by hand — run `./gradlew generateToolDocs` to refresh.

- **Server:** `portfolio-mcp` v0.0.1
- **Transport:** HTTP/SSE — connect at `http://localhost:8080/sse`
- **Tools:** 6

| Tool | Summary |
|------|---------|
| [`executeTrade`](#executetrade) | Execute a trade for a customer and update their portfolio. |
| [`getPortfolio`](#getportfolio) | Get a customer's full portfolio: cash balance, every stock position (quantity, average cost, current price, market value, unrealised P&L) and total account value. |
| [`getQuote`](#getquote) | Get the current quote and identifiers (ISIN, WKN) for a single instrument, with an ISO-8601 UTC timestamp of when the price was read. |
| [`getTradeHistory`](#gettradehistory) | Get a customer's trade history (most recent first), cursor-paginated. |
| [`listInstruments`](#listinstruments) | List tradable instruments (symbol, name, ISIN, WKN, current price), cursor-paginated. |
| [`previewTrade`](#previewtrade) | Simulate a trade WITHOUT persisting it: runs the identical FIFO matching and funds/shares checks as executeTrade but rolls back, returning the projected execution price, estimated cost, slippage margin, resulting cash/position, and an isValid flag (with a reason when the trade would fail). |

## `executeTrade`

Execute a trade for a customer and update their portfolio. BUY appends a new FIFO lot and debits cash; SELL consumes the oldest lots first, credits cash and reports realised P&L. MARKET fills at the current price; LIMIT fills only if marketable against limitPrice. Idempotent on idempotencyKey — a repeat within 5 minutes returns the original receipt. Fails on unknown customer/symbol, insufficient funds, insufficient shares, or a non-marketable limit.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `request` | object | yes | — | The trade to execute |
| `request.customerId` | integer | yes | min 1, format int64 | The customer's numeric id |
| `request.idempotencyKey` | string | yes | pattern `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$` | Client-generated idempotency key (UUID). A repeated key within 5 minutes returns the original receipt instead of executing again. |
| `request.limitPrice` | number | no | min 0 | Limit price per share. Required when orderType is LIMIT; ignored for MARKET. |
| `request.orderType` | string (enum: MARKET, LIMIT) | no | — | Order type; defaults to MARKET when omitted |
| `request.quantity` | integer | yes | min 1, format int32 | Number of shares to trade |
| `request.side` | string (enum: BUY, SELL) | yes | — | Trade direction |
| `request.symbol` | string | yes | pattern `^[A-Z]{1,6}$` | Ticker symbol to trade, e.g. AAPL |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "request" : {
      "type" : "object",
      "properties" : {
        "customerId" : {
          "type" : "integer",
          "format" : "int64",
          "description" : "The customer's numeric id",
          "minimum" : 1
        },
        "idempotencyKey" : {
          "type" : "string",
          "description" : "Client-generated idempotency key (UUID). A repeated key within 5 minutes returns the original receipt instead of executing again.",
          "pattern" : "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        },
        "limitPrice" : {
          "type" : "number",
          "description" : "Limit price per share. Required when orderType is LIMIT; ignored for MARKET.",
          "minimum" : 0
        },
        "orderType" : {
          "type" : "string",
          "enum" : [ "MARKET", "LIMIT" ],
          "description" : "Order type; defaults to MARKET when omitted"
        },
        "quantity" : {
          "type" : "integer",
          "format" : "int32",
          "description" : "Number of shares to trade",
          "minimum" : 1
        },
        "side" : {
          "type" : "string",
          "enum" : [ "BUY", "SELL" ],
          "description" : "Trade direction"
        },
        "symbol" : {
          "type" : "string",
          "description" : "Ticker symbol to trade, e.g. AAPL",
          "pattern" : "^[A-Z]{1,6}$"
        }
      },
      "required" : [ "customerId", "idempotencyKey", "quantity", "side", "symbol" ],
      "description" : "The trade to execute",
      "additionalProperties" : false
    }
  },
  "required" : [ "request" ],
  "additionalProperties" : false
}
```

</details>

## `getPortfolio`

Get a customer's full portfolio: cash balance, every stock position (quantity, average cost, current price, market value, unrealised P&L) and total account value.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `customerId` | integer | yes | — | The customer's numeric id |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "customerId" : {
      "type" : "integer",
      "description" : "The customer's numeric id"
    }
  },
  "required" : [ "customerId" ],
  "additionalProperties" : false
}
```

</details>

## `getQuote`

Get the current quote and identifiers (ISIN, WKN) for a single instrument, with an ISO-8601 UTC timestamp of when the price was read.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `symbol` | string | yes | — | Ticker symbol — 1–6 uppercase letters, e.g. AAPL |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "symbol" : {
      "type" : "string",
      "description" : "Ticker symbol — 1–6 uppercase letters, e.g. AAPL"
    }
  },
  "required" : [ "symbol" ],
  "additionalProperties" : false
}
```

</details>

## `getTradeHistory`

Get a customer's trade history (most recent first), cursor-paginated. Returns an object with `items` (including realised P&L on sells) and a `nextCursor`; pass nextCursor back as `cursor`.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `customerId` | integer | yes | — | The customer's numeric id |
| `limit` | integer | no | — | Max items to return (default 50, max 100) |
| `cursor` | string | no | — | Pagination cursor from a previous nextCursor; omit for the first page |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "customerId" : {
      "type" : "integer",
      "description" : "The customer's numeric id"
    },
    "limit" : {
      "type" : "integer",
      "description" : "Max items to return (default 50, max 100)"
    },
    "cursor" : {
      "type" : "string",
      "description" : "Pagination cursor from a previous nextCursor; omit for the first page"
    }
  },
  "required" : [ "customerId" ],
  "additionalProperties" : false
}
```

</details>

## `listInstruments`

List tradable instruments (symbol, name, ISIN, WKN, current price), cursor-paginated. Returns an object with `items` and a `nextCursor`; pass nextCursor back as `cursor` for the next page.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `limit` | integer | no | — | Max items to return (default 50, max 100) |
| `cursor` | string | no | — | Pagination cursor from a previous nextCursor; omit for the first page |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "limit" : {
      "type" : "integer",
      "description" : "Max items to return (default 50, max 100)"
    },
    "cursor" : {
      "type" : "string",
      "description" : "Pagination cursor from a previous nextCursor; omit for the first page"
    }
  },
  "required" : [ ],
  "additionalProperties" : false
}
```

</details>

## `previewTrade`

Simulate a trade WITHOUT persisting it: runs the identical FIFO matching and funds/shares checks as executeTrade but rolls back, returning the projected execution price, estimated cost, slippage margin, resulting cash/position, and an isValid flag (with a reason when the trade would fail). Use this to safely check a trade before executing it.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `request` | object | yes | — | The trade to simulate (same shape as executeTrade) |
| `request.customerId` | integer | yes | min 1, format int64 | The customer's numeric id |
| `request.idempotencyKey` | string | yes | pattern `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$` | Client-generated idempotency key (UUID). A repeated key within 5 minutes returns the original receipt instead of executing again. |
| `request.limitPrice` | number | no | min 0 | Limit price per share. Required when orderType is LIMIT; ignored for MARKET. |
| `request.orderType` | string (enum: MARKET, LIMIT) | no | — | Order type; defaults to MARKET when omitted |
| `request.quantity` | integer | yes | min 1, format int32 | Number of shares to trade |
| `request.side` | string (enum: BUY, SELL) | yes | — | Trade direction |
| `request.symbol` | string | yes | pattern `^[A-Z]{1,6}$` | Ticker symbol to trade, e.g. AAPL |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "request" : {
      "type" : "object",
      "properties" : {
        "customerId" : {
          "type" : "integer",
          "format" : "int64",
          "description" : "The customer's numeric id",
          "minimum" : 1
        },
        "idempotencyKey" : {
          "type" : "string",
          "description" : "Client-generated idempotency key (UUID). A repeated key within 5 minutes returns the original receipt instead of executing again.",
          "pattern" : "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        },
        "limitPrice" : {
          "type" : "number",
          "description" : "Limit price per share. Required when orderType is LIMIT; ignored for MARKET.",
          "minimum" : 0
        },
        "orderType" : {
          "type" : "string",
          "enum" : [ "MARKET", "LIMIT" ],
          "description" : "Order type; defaults to MARKET when omitted"
        },
        "quantity" : {
          "type" : "integer",
          "format" : "int32",
          "description" : "Number of shares to trade",
          "minimum" : 1
        },
        "side" : {
          "type" : "string",
          "enum" : [ "BUY", "SELL" ],
          "description" : "Trade direction"
        },
        "symbol" : {
          "type" : "string",
          "description" : "Ticker symbol to trade, e.g. AAPL",
          "pattern" : "^[A-Z]{1,6}$"
        }
      },
      "required" : [ "customerId", "idempotencyKey", "quantity", "side", "symbol" ],
      "description" : "The trade to simulate (same shape as executeTrade)",
      "additionalProperties" : false
    }
  },
  "required" : [ "request" ],
  "additionalProperties" : false
}
```

</details>

