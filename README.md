# NSE Catalyst Terminal

An event-driven **Institutional Catalyst Screener & Alert Terminal** for Indian equities (NSE), built with **Java 17** and **Spring Boot 3.2.5**.

## Features
- **3-Second NSE RSS Feed Poller:** Cold-start deduplication + bounded LRU cache.
- **0ms Multi-Category Firewall:** Drops routine compliance noise while passing Order Wins, Joint Ventures, Earnings Beats, USFDA/SEBI Regulatory updates, CFO/Auditor shocks, and Block Deals.
- **Live Market Cap Enrichment:** Fetches LTP, intraday `%` change, and Market Cap (`₹ Cr`) before AI scoring so `order_to_mcap_pct` is grounded in real market data and verified deterministically.
- **Institutional AI Scoring (`0–100`):** Strict Gemini JSON `responseSchema` outputting `direction`, `catalyst_score`, `category`, `order_value_cr`, `order_to_mcap_pct`, `is_actionable_intraday`, `headline_summary`, `institutional_rationale`, and `key_risk`.
- **Real-Time Terminal UI + SSE:** Live push stream at `/api/stream/catalysts`, tiered filters (`Tier 1: 90+`, `Tier 2: 70–89`, `Tier 3: 40–69`), Watchlist pinning, CSV export, and On-Demand Text/PDF Filing Sandbox.
- **Telegram Alerts & Commands:** Alerts on `score >= 70` (or `>= 40` for Watchlist symbols) + `/status`, `/tier1`, `/top`, `/watchlist`, `/add`, `/remove`.
- **Persistent H2 Archive:** Disk-backed `./data/catalystdb.mv.db` with 365-day retention pruning.

## Quick Start
1. Copy `.env.example` to `.env` and set `GEMINI_API_KEY` (and optional Telegram credentials).
2. Run locally:
   ```bash
   mvn spring-boot:run
   ```
3. Open [http://localhost:8085/](http://localhost:8085/).

