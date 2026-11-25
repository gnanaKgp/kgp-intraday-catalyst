document.addEventListener('DOMContentLoaded', () => {
    const state = {
        tier: '',
        minScore: null,
        watchlist: new Set()
    };

    const el = {
        ssePill: document.getElementById('ssePill'),
        sseText: document.getElementById('sseText'),
        pollerPill: document.getElementById('pollerPill'),
        pollerText: document.getElementById('pollerText'),
        dhanPill: document.getElementById('dhanPill'),
        dhanText: document.getElementById('dhanText'),
        geminiPill: document.getElementById('geminiPill'),
        clockIst: document.getElementById('clockIst'),
        funnelFeed: document.getElementById('funnelFeed'),
        funnelMetaLabel: document.getElementById('funnelMetaLabel'),
        funnelCapital: document.getElementById('funnelCapital'),
        funnelRiskPct: document.getElementById('funnelRiskPct'),
        funnelShowAll: document.getElementById('funnelShowAll'),
        btnRunFunnelScan: document.getElementById('btnRunFunnelScan'),
        kpiTier1: document.getElementById('kpiTier1'),
        kpiTier2: document.getElementById('kpiTier2'),
        kpiTier3: document.getElementById('kpiTier3'),
        kpiDropRate: document.getElementById('kpiDropRate'),
        kpiCountsSub: document.getElementById('kpiCountsSub'),
        kpiPersisted: document.getElementById('kpiPersisted'),
        kpiAlertsSub: document.getElementById('kpiAlertsSub'),
        catalystFeed: document.getElementById('catalystFeed'),
        feedCountLabel: document.getElementById('feedCountLabel'),
        filterDirection: document.getElementById('filterDirection'),
        filterCategory: document.getElementById('filterCategory'),
        filterSymbol: document.getElementById('filterSymbol'),
        filterFrom: document.getElementById('filterFrom'),
        filterTo: document.getElementById('filterTo'),
        filterWatchlistOnly: document.getElementById('filterWatchlistOnly'),
        watchlistItems: document.getElementById('watchlistItems'),
        watchlistCount: document.getElementById('watchlistCount'),
        eventsFeed: document.getElementById('eventsFeed'),
        analyzeModal: document.getElementById('analyzeModal'),
        logsModal: document.getElementById('logsModal'),
        logsConsole: document.getElementById('logsConsole'),
        logsMeta: document.getElementById('logsMeta'),
        logLevelFilter: document.getElementById('logLevelFilter'),
        logLineCount: document.getElementById('logLineCount'),
        logAutoRefresh: document.getElementById('logAutoRefresh'),
        backtestModal: document.getElementById('backtestModal'),
        btFromDate: document.getElementById('btFromDate'),
        btToDate: document.getElementById('btToDate'),
        btSymbols: document.getElementById('btSymbols'),
        btCapital: document.getElementById('btCapital'),
        btRiskPct: document.getElementById('btRiskPct'),
        btAssumedCat: document.getElementById('btAssumedCat'),
        btShowFiltered: document.getElementById('btShowFiltered'),
        btMeta: document.getElementById('btMeta'),
        btSummaryGrid: document.getElementById('btSummaryGrid'),
        btTableBody: document.getElementById('btTableBody')
    };

    const PRESETS = {
        order: {
            symbol: 'RVNL',
            company: 'Rail Vikas Nigam Limited',
            mcap: '85000',
            headline: 'Receipt of Letter of Acceptance from Southern Railway worth INR 14,500 Cr',
            text: 'Rail Vikas Nigam Limited has received a firm Letter of Acceptance (LOA) from Southern Railway for Railway Electrification & EPC execution valued at INR 14,500.00 Crores to be executed over 24 months.'
        },
        shock: {
            symbol: 'SAMPLE',
            company: 'Sample Midcap Engineering Limited',
            mcap: '3200',
            headline: 'Resignation of Chief Financial Officer and Statutory Auditor with immediate effect',
            text: 'The Company informs that the Chief Financial Officer (CFO) and the Statutory Auditors have tendered their resignation citing inability to finalize quarterly financial statements and governance disagreements.'
        },
        earnings: {
            symbol: 'BEL',
            company: 'Bharat Electronics Limited',
            mcap: '210000',
            headline: 'Q3 Financial Results: Net Profit jumps 48% YoY with 320 bps EBITDA margin expansion',
            text: 'Bharat Electronics Limited reports Q3 revenue growth of 42% YoY and PAT jump of 48% YoY. EBITDA margins expanded by 320 bps on strong defence execution and upward FY guidance revision.'
        },
        noise: {
            symbol: 'TCS',
            company: 'Tata Consultancy Services Limited',
            mcap: '1450000',
            headline: 'Intimation of Trading Window Closure pursuant to SEBI PIT Regulations',
            text: 'Pursuant to SEBI (Prohibition of Insider Trading) Regulations, the trading window for dealing in securities of the Company shall remain closed for designated persons from 1st January until 48 hours after declaration of financial results.'
        }
    };

    function buildQueryParams() {
        const params = new URLSearchParams();
        if (state.tier) params.set('tier', state.tier);
        if (state.minScore) params.set('minScore', state.minScore);
        if (el.filterDirection.value) params.set('direction', el.filterDirection.value);
        if (el.filterCategory.value) params.set('category', el.filterCategory.value);
        if (el.filterSymbol.value.trim()) params.set('symbol', el.filterSymbol.value.trim());
        if (el.filterFrom.value) params.set('from', el.filterFrom.value);
        if (el.filterTo.value) params.set('to', el.filterTo.value);
        if (el.filterWatchlistOnly.checked) params.set('watchlistOnly', 'true');
        params.set('limit', '100');
        return params;
    }

    async function fetchStatus() {
        const res = await fetch('/api/system/status');
        if (!res.ok) return;
        const s = await res.json();
        el.clockIst.textContent = `${s.serverTimeIst} IST`;
        el.pollerPill.className = `pill ${s.pollerEnabled ? 'ok' : 'warn'}`;
        el.pollerText.textContent = s.pollerEnabled ? `POLLER ${s.totalPolls} (${s.lastPollStatus})` : 'POLLER PAUSED';
        el.geminiPill.className = `pill ${s.geminiConfigured ? 'ok' : 'warn'}`;
        el.geminiPill.textContent = s.geminiConfigured ? `AI: ${s.geminiModel}` : 'AI: KEY MISSING';

        el.kpiTier1.textContent = s.tier1Today ?? 0;
        el.kpiTier2.textContent = s.tier2Today ?? 0;
        el.kpiTier3.textContent = s.tier3Today ?? 0;
        const seen = s.totalFilingsSeen || 0;
        const dropped = s.firewallDropped || 0;
        const dropRate = seen > 0 ? ((dropped / seen) * 100).toFixed(0) : 0;
        el.kpiDropRate.textContent = `${dropRate}%`;
        el.kpiCountsSub.textContent = `${seen} seen / ${s.aiEvaluated || 0} AI scored`;
        el.kpiPersisted.textContent = s.totalPersisted ?? 0;
        el.kpiAlertsSub.textContent = `${s.alertsSent || 0} Telegram alerts dispatched`;
    }

    async function fetchFilings() {
        const res = await fetch(`/api/filings?${buildQueryParams().toString()}`);
        if (!res.ok) return;
        const rows = await res.json();
        renderFilings(rows);
    }

    function renderFilings(rows) {
        el.feedCountLabel.textContent = `${rows.length} filing(s)`;
        if (!rows.length) {
            el.catalystFeed.innerHTML = '<div class="empty-state">No filings match the current filters.</div>';
            return;
        }
        el.catalystFeed.innerHTML = rows.map(renderCard).join('');
    }

    function renderCard(r) {
        const pinned = state.watchlist.has((r.symbol || '').toUpperCase());
        const orderVal = r.order_value_cr != null ? `₹${Number(r.order_value_cr).toFixed(2)} Cr` : '—';
        const mcapVal = r.market_cap_cr != null ? `₹${Number(r.market_cap_cr).toFixed(0)} Cr` : '—';
        const ratioVal = r.order_to_mcap_pct != null ? `${Number(r.order_to_mcap_pct).toFixed(2)}%` : '—';
        const ltpVal = r.ltp != null ? `₹${Number(r.ltp).toFixed(2)}` : '—';
        const chgVal = r.day_change_pct != null ? `${Number(r.day_change_pct) >= 0 ? '+' : ''}${Number(r.day_change_pct).toFixed(2)}%` : '—';
        const pdfLink = r.attachment_url || r.attachmentUrl
            ? `<a href="${esc(r.attachment_url || r.attachmentUrl)}" target="_blank" rel="noopener">Open Filing PDF</a>`
            : '';

        return `
        <article class="catalyst-card ${esc(r.tier)}">
            <div class="card-top">
                <div>
                    <div class="card-title-group">
                        <button class="star-btn ${pinned ? 'pinned' : ''}" data-pin="${esc(r.symbol)}" title="Toggle Watchlist">★</button>
                        <span class="ticker">${esc(r.symbol)}</span>
                        <span class="company-name">${esc(r.companyName)}</span>
                        <span class="badge badge-${esc(r.direction)}">${esc(r.direction)}</span>
                        <span class="badge badge-cat">${esc(r.category)}</span>
                        ${r.is_actionable_intraday ? '<span class="badge badge-action">ACTIONABLE</span>' : ''}
                    </div>
                </div>
                <div class="score-box">
                    <span class="num">${r.catalyst_score}</span>
                    <span class="lbl">${esc(r.tier)}</span>
                </div>
            </div>
            <div class="card-headline">${esc(r.headline_summary)}</div>
            <p class="card-rationale">${esc(r.institutional_rationale)}</p>
            <div class="metrics-strip">
                <span>LTP: <strong>${ltpVal}</strong> (${chgVal})</span>
                <span>MCap: <strong>${mcapVal}</strong></span>
                <span>Order: <strong>${orderVal}</strong></span>
                <span>Order/MCap: <strong>${ratioVal}</strong></span>
            </div>
            <div class="card-footer">
                <span class="risk-chip">⚠ Risk: ${esc(r.key_risk)}</span>
                <div class="card-links">
                    <span class="muted">${formatTime(r.evaluatedAt)}</span>
                    ${pdfLink}
                </div>
            </div>
        </article>`;
    }

    async function fetchWatchlist() {
        const res = await fetch('/api/watchlist');
        if (!res.ok) return;
        const items = await res.json();
        state.watchlist = new Set(items.map(i => i.symbol.toUpperCase()));
        el.watchlistCount.textContent = `${items.length} symbols`;
        el.watchlistItems.innerHTML = items.length
            ? items.map(i => `
                <li class="watchlist-item">
                    <button class="sym-link" data-filter-sym="${esc(i.symbol)}">⭐ ${esc(i.symbol)}</button>
                    <button class="btn btn-ghost btn-xs" data-unpin="${esc(i.symbol)}">Remove</button>
                </li>`).join('')
            : '<li class="muted">No symbols pinned yet.</li>';
    }

    async function fetchEvents() {
        const res = await fetch('/api/system/events?limit=40');
        if (!res.ok) return;
        const events = await res.json();
        el.eventsFeed.innerHTML = events.map(ev => `
            <div class="event-row ${esc(ev.level)}">
                <div class="ev-meta">${esc(ev.timestamp)} [${esc(ev.stage)}] ${esc(ev.symbol || '')}</div>
                <div>${esc(ev.message)}</div>
            </div>`).join('');
    }

    function connectSse() {
        const source = new EventSource('/api/stream/catalysts');
        source.addEventListener('connected', () => {
            el.ssePill.className = 'pill ok';
            el.sseText.textContent = 'SSE LIVE';
        });
        source.addEventListener('filing', (evt) => {
            const record = JSON.parse(evt.data);
            showToast(`${record.symbol} scored ${record.catalyst_score}/100 (${record.direction})`);
            fetchFilings();
            fetchStatus();
            fetchEvents();
        });
        source.addEventListener('funnel_update', (evt) => {
            renderFunnelSnapshot(JSON.parse(evt.data));
        });
        source.addEventListener('funnel_cutoff', (evt) => {
            const msg = JSON.parse(evt.data);
            showToast(msg.message || '3:15 PM Auto-Cutoff reached');
            fetchFunnelSnapshot();
        });
        source.onerror = () => {
            el.ssePill.className = 'pill warn';
            el.sseText.textContent = 'SSE RECONNECTING';
        };
    }

    let lastFunnelSnapshot = null;

    async function fetchFunnelSnapshot() {
        const res = await fetch('/api/funnel/snapshot');
        if (!res.ok) return;
        const snap = await res.json();
        renderFunnelSnapshot(snap);
    }

    function renderFunnelSnapshot(snap) {
        if (!snap) return;
        lastFunnelSnapshot = snap;

        if (snap.dhanConfigured) {
            el.dhanPill.className = `pill ${snap.dhanWsConnected ? 'ok' : 'warn'}`;
            el.dhanText.textContent = snap.dhanWsConnected ? 'DHAN WS LIVE' : `DHAN ${snap.dhanAuthStatus || 'READY'}`;
        } else {
            el.dhanPill.className = 'pill warn';
            el.dhanText.textContent = 'DHAN OFF';
        }

        document.querySelectorAll('#funnelStagesBar .stage-chip').forEach(ch => {
            ch.classList.toggle('active', ch.dataset.stage === snap.stage);
        });

        const top = snap.topSetups || [];
        const all = snap.allEvaluated || [];
        el.funnelMetaLabel.textContent =
            `Stage: ${snap.stageDescription} (${snap.stageWindow}) | Nifty 500 Universe: ${snap.nifty500Count} | Candidates: ${snap.candidateCount} | Passed (> ${snap.minCompositeScore}): ${top.length}`
            + (snap.lastScanAt ? ` | Last scan: ${formatTime(snap.lastScanAt)}` : '');

        const list = el.funnelShowAll.checked ? all : top;
        if (!list.length) {
            el.funnelFeed.innerHTML = all.length
                ? `<div class="empty-state">${all.length} candidate(s) evaluated, 0 passed all 4 guardrails &amp; &gt; ${snap.minCompositeScore} threshold. Toggle <strong>Show All / Disqualified</strong> to inspect.</div>`
                : '<div class="empty-state">No ranked setups yet. Click <strong>Run Funnel Scan</strong> to evaluate candidates with DhanHQ technicals.</div>';
            return;
        }
        el.funnelFeed.innerHTML = list.map(renderSetupCard).join('');
    }

    function renderSetupCard(s) {
        const g = s.guardrails || {};
        const p = s.executionPlan;
        const b = s.scoreBreakdown || {};
        const passed = Boolean(g.passed) && s.compositeScore > 75;
        const cls = `${esc(s.side)} ${passed ? '' : 'DISQUALIFIED'}`;
        const reasons = (g.disqualificationReasons || []).length
            ? `<div class="guardrail-reasons">Guardrails: ${g.disqualificationReasons.map(esc).join(' • ')}</div>`
            : '';

        return `
        <article class="setup-card ${cls}">
            <div class="card-top">
                <div class="card-title-group">
                    <span class="ticker">${esc(s.symbol)} — ${esc(s.side)}</span>
                    <span class="badge badge-cat">${esc(s.sector || 'NIFTY')}</span>
                    <span class="badge ${passed ? 'badge-BULLISH' : 'badge-NEUTRAL'}">${passed ? 'PASS >75' : 'FILTERED'}</span>
                </div>
                <div class="score-box">
                    <span class="num">${Number(s.compositeScore || 0).toFixed(1)}</span>
                    <span class="lbl">COMPOSITE</span>
                </div>
            </div>
            <div class="card-headline"><strong>News:</strong> ${esc(s.whyMoving)}</div>
            <div class="card-rationale"><strong>Trigger:</strong> ${esc(s.technicalTrigger)}</div>
            ${p ? `
            <div class="setup-exec-strip">
                <div><span>Entry</span><strong>₹${Number(p.entry).toFixed(2)}</strong></div>
                <div><span>Stop-Loss</span><strong>₹${Number(p.stopLoss).toFixed(2)}</strong></div>
                <div><span>Target (1:${Number(p.rrRatio).toFixed(1)})</span><strong>₹${Number(p.target).toFixed(2)}</strong></div>
                <div><span>Qty (Risk ₹${Number(p.riskInr).toFixed(0)})</span><strong>${p.quantity}</strong></div>
            </div>` : ''}
            <div class="subscore-strip">
                <span class="subscore-pill">Cat(25%): ${b.catalyst ?? 0}</span>
                <span class="subscore-pill">RVOL(25%): ${b.rvol ?? 0}</span>
                <span class="subscore-pill">Struct(25%): ${b.structure ?? 0}</span>
                <span class="subscore-pill">RS(15%): ${b.relativeStrength ?? 0}</span>
                <span class="subscore-pill">Liq(10%): ${b.liquidity ?? 0}</span>
                <span class="subscore-pill">ATR14: ${g.atr14 ?? 0} (${g.atrMultiple ?? 0}x)</span>
                <span class="subscore-pill">20d Turn: ₹${g.avgTurnover20dCr ?? 0} Cr</span>
            </div>
            ${reasons}
        </article>`;
    }

    // Event bindings
    document.querySelectorAll('#tierFilters .filter-pill').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('#tierFilters .filter-pill').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            state.tier = btn.dataset.tier || '';
            state.minScore = btn.dataset.minscore || null;
            fetchFilings();
        });
    });

    [el.filterDirection, el.filterCategory, el.filterFrom, el.filterTo, el.filterWatchlistOnly]
        .forEach(ctrl => ctrl.addEventListener('change', fetchFilings));
    el.filterSymbol.addEventListener('input', fetchFilings);

    document.getElementById('btnResetFilters').addEventListener('click', () => {
        state.tier = '';
        state.minScore = null;
        el.filterDirection.value = '';
        el.filterCategory.value = '';
        el.filterSymbol.value = '';
        el.filterFrom.value = '';
        el.filterTo.value = '';
        el.filterWatchlistOnly.checked = false;
        document.querySelectorAll('#tierFilters .filter-pill').forEach((b, i) => b.classList.toggle('active', i === 0));
        fetchFilings();
    });

    el.catalystFeed.addEventListener('click', async (e) => {
        const pinSym = e.target.getAttribute('data-pin');
        if (!pinSym) return;
        const sym = pinSym.toUpperCase();
        if (state.watchlist.has(sym)) {
            await fetch(`/api/watchlist/${encodeURIComponent(sym)}`, { method: 'DELETE' });
            showToast(`Removed ${sym} from watchlist`);
        } else {
            await fetch('/api/watchlist', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ symbol: sym, notes: 'Pinned from feed' })
            });
            showToast(`Pinned ${sym} to watchlist`);
        }
        await fetchWatchlist();
        fetchFilings();
    });

    document.getElementById('watchlistForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const input = document.getElementById('watchlistSymbolInput');
        const sym = input.value.trim().toUpperCase();
        if (!sym) return;
        await fetch('/api/watchlist', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ symbol: sym })
        });
        input.value = '';
        await fetchWatchlist();
        fetchFilings();
    });

    el.watchlistItems.addEventListener('click', async (e) => {
        const unpin = e.target.getAttribute('data-unpin');
        const filterSym = e.target.getAttribute('data-filter-sym');
        if (unpin) {
            await fetch(`/api/watchlist/${encodeURIComponent(unpin)}`, { method: 'DELETE' });
            await fetchWatchlist();
            fetchFilings();
        } else if (filterSym) {
            el.filterSymbol.value = filterSym;
            fetchFilings();
        }
    });

    document.getElementById('btnExportCsv').addEventListener('click', () => {
        window.location.href = `/api/filings/export?${buildQueryParams().toString()}`;
    });

    document.getElementById('btnTelegramTest').addEventListener('click', async () => {
        const res = await fetch('/api/system/telegram/test', { method: 'POST' });
        const data = await res.json();
        showToast(data.message || 'Telegram check complete');
    });

    document.getElementById('btnRefreshEvents').addEventListener('click', fetchEvents);

    // Modal & On-demand analyzer
    const closeModal = () => el.analyzeModal.classList.remove('active');
    document.getElementById('btnOpenAnalyze').addEventListener('click', () => el.analyzeModal.classList.add('active'));
    document.getElementById('btnCloseModal').addEventListener('click', closeModal);
    document.getElementById('btnCancelModal').addEventListener('click', closeModal);

    // Live Logs modal
    let logsTimer = null;
    async function fetchLiveLogs() {
        const lines = el.logLineCount.value || 300;
        const level = el.logLevelFilter.value || 'ALL';
        const res = await fetch(`/api/system/logs?lines=${encodeURIComponent(lines)}&level=${encodeURIComponent(level)}`);
        if (!res.ok) return;
        const data = await res.json();
        const atBottom = el.logsConsole.scrollHeight - el.logsConsole.scrollTop - el.logsConsole.clientHeight < 40;
        el.logsMeta.textContent = data.exists ? `(${data.file} • ${data.lineCount} lines)` : `(${data.file} not found)`;
        if (!data.lines || !data.lines.length) {
            el.logsConsole.innerHTML = '<div class="muted">No log lines match the current filter.</div>';
            return;
        }
        el.logsConsole.innerHTML = data.lines.map(line => {
            const cls = line.includes(' ERROR ') ? 'ERROR' : (line.includes(' WARN ') ? 'WARN' : '');
            return `<div class="log-line ${cls}">${esc(line)}</div>`;
        }).join('');
        if (atBottom || el.logsConsole.scrollTop === 0) {
            el.logsConsole.scrollTop = el.logsConsole.scrollHeight;
        }
    }

    function openLogsModal() {
        el.logsModal.classList.add('active');
        fetchLiveLogs();
        if (logsTimer) clearInterval(logsTimer);
        logsTimer = setInterval(() => {
            if (el.logsModal.classList.contains('active') && el.logAutoRefresh.checked) {
                fetchLiveLogs();
            }
        }, 2000);
    }

    function closeLogsModal() {
        el.logsModal.classList.remove('active');
        if (logsTimer) {
            clearInterval(logsTimer);
            logsTimer = null;
        }
    }

    document.getElementById('btnOpenLogs').addEventListener('click', openLogsModal);
    document.getElementById('btnCloseLogs').addEventListener('click', closeLogsModal);
    document.getElementById('btnRefreshLogs').addEventListener('click', fetchLiveLogs);
    el.logLevelFilter.addEventListener('change', fetchLiveLogs);
    el.logLineCount.addEventListener('change', fetchLiveLogs);
    el.logsModal.addEventListener('click', (e) => {
        if (e.target === el.logsModal) closeLogsModal();
    });

    // Historical Backtest modal
    let lastBacktestSummary = null;
    const todayStr = new Date().toISOString().slice(0, 10);
    const weekAgoStr = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
    el.btToDate.value = todayStr;
    el.btFromDate.value = weekAgoStr;

    document.getElementById('btnOpenBacktest').addEventListener('click', () => el.backtestModal.classList.add('active'));
    document.getElementById('btnCloseBacktest').addEventListener('click', () => el.backtestModal.classList.remove('active'));
    el.backtestModal.addEventListener('click', (e) => {
        if (e.target === el.backtestModal) el.backtestModal.classList.remove('active');
    });
    el.btShowFiltered.addEventListener('change', () => renderBacktest(lastBacktestSummary));

    document.getElementById('backtestForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const btn = document.getElementById('btnRunBacktest');
        btn.disabled = true;
        btn.textContent = 'Running Replay...';
        try {
            const rawSyms = el.btSymbols.value.trim();
            const symbols = rawSyms ? rawSyms.split(',').map(s => s.trim().toUpperCase()).filter(Boolean) : null;
            const assumedRaw = el.btAssumedCat.value.trim();
            const res = await fetch('/api/backtest/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    fromDate: el.btFromDate.value,
                    toDate: el.btToDate.value,
                    symbols,
                    capitalInr: parseFloat(el.btCapital.value || '100000'),
                    riskPct: parseFloat(el.btRiskPct.value || '1.0'),
                    minCompositeScore: 75.0,
                    assumedCatalystScore: assumedRaw ? parseInt(assumedRaw, 10) : null
                })
            });
            if (res.ok) {
                lastBacktestSummary = await res.json();
                renderBacktest(lastBacktestSummary);
                fetchEvents();
            } else {
                showToast('Backtest request failed');
            }
        } finally {
            btn.disabled = false;
            btn.textContent = 'Run Historical Backtest';
        }
    });

    function renderBacktest(s) {
        if (!s) return;
        el.btMeta.textContent = `${s.tradingDaysScanned} trading day(s) • ${s.symbolsEvaluated} symbol(s) • ${s.totalPassedSignals} passed / ${s.totalFilteredSignals} filtered`;
        const pnlCls = s.netPnlInr >= 0 ? 'bt-win' : 'bt-loss';
        el.btSummaryGrid.innerHTML = `
            <div class="bt-kpi"><span>Passed Trades</span><strong>${s.totalPassedSignals} (${s.wins}W / ${s.losses}L)</strong></div>
            <div class="bt-kpi"><span>Win Rate</span><strong>${s.winRatePct}%</strong></div>
            <div class="bt-kpi"><span>Net P&amp;L</span><strong class="${pnlCls}">₹${Number(s.netPnlInr).toFixed(0)} (${s.returnOnCapitalPct}%)</strong></div>
            <div class="bt-kpi"><span>Profit Factor</span><strong>${s.profitFactor}</strong></div>
            <div class="bt-kpi"><span>Avg R-Multiple</span><strong>${s.avgRMultiple}R</strong></div>
            <div class="bt-kpi"><span>Max Drawdown</span><strong>₹${Number(s.maxDrawdownInr).toFixed(0)}</strong></div>
            <div class="bt-kpi"><span>Guardrails Saved</span><strong>${s.filteredWouldHaveLostPct}% losers</strong></div>
        `;

        const rows = el.btShowFiltered.checked ? (s.filteredSetups || []) : (s.passedTrades || []);
        if (!rows.length) {
            el.btTableBody.innerHTML = `<tr><td colspan="13" class="muted">No ${el.btShowFiltered.checked ? 'filtered setups' : 'passed trades'} in this range. Try setting <strong>Assumed Catalyst = 80</strong> or widening dates.</td></tr>`;
            return;
        }
        el.btTableBody.innerHTML = rows.map(t => {
            const pCls = t.pnlInr > 0 ? 'bt-win' : (t.pnlInr < 0 ? 'bt-loss' : '');
            const notes = (t.guardrailReasons || []).length ? t.guardrailReasons.join('; ') : t.catalystSource;
            return `
            <tr>
                <td>${esc(t.sessionDate)}</td>
                <td><strong>${esc(t.symbol)}</strong></td>
                <td>${esc(t.side)}</td>
                <td>${esc(t.signalTimeIst)}</td>
                <td>${Number(t.compositeScore).toFixed(1)}</td>
                <td>₹${Number(t.entryPrice).toFixed(2)}</td>
                <td>₹${Number(t.stopLoss).toFixed(2)}</td>
                <td>₹${Number(t.targetPrice).toFixed(2)}</td>
                <td>₹${Number(t.exitPrice).toFixed(2)} (${esc(t.exitTimeIst)})</td>
                <td class="${pCls}">${esc(t.outcome)}</td>
                <td class="${pCls}">₹${Number(t.pnlInr).toFixed(0)} (${t.rMultipleRealized}R)</td>
                <td>+${t.mfePct}% / ${t.maePct}%</td>
                <td>${esc(notes)}</td>
            </tr>`;
        }).join('');
    }

    document.querySelectorAll('[data-preset]').forEach(btn => {
        btn.addEventListener('click', () => {
            const p = PRESETS[btn.dataset.preset];
            if (!p) return;
            document.getElementById('inSymbol').value = p.symbol;
            document.getElementById('inCompany').value = p.company;
            document.getElementById('inMcap').value = p.mcap;
            document.getElementById('inHeadline').value = p.headline;
            document.getElementById('inText').value = p.text;
        });
    });

    document.getElementById('analyzeForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const btn = document.getElementById('btnSubmitAnalyze');
        btn.disabled = true;
        btn.textContent = 'Scoring...';
        try {
            const pdfInput = document.getElementById('inPdfFile');
            const mcapRaw = document.getElementById('inMcap').value;
            let res;
            if (pdfInput.files && pdfInput.files.length > 0) {
                const fd = new FormData();
                fd.append('symbol', document.getElementById('inSymbol').value.trim());
                fd.append('companyName', document.getElementById('inCompany').value.trim());
                fd.append('headline', document.getElementById('inHeadline').value.trim());
                if (mcapRaw) fd.append('marketCapCrOverride', mcapRaw);
                fd.append('file', pdfInput.files[0]);
                res = await fetch('/api/analyze/pdf', { method: 'POST', body: fd });
            } else {
                res = await fetch('/api/analyze', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        symbol: document.getElementById('inSymbol').value.trim(),
                        companyName: document.getElementById('inCompany').value.trim(),
                        headline: document.getElementById('inHeadline').value.trim(),
                        filingText: document.getElementById('inText').value.trim(),
                        marketCapCrOverride: mcapRaw ? parseFloat(mcapRaw) : null
                    })
                });
            }
            if (!res.ok) {
                const err = await res.json();
                showToast(err.error || 'Analysis failed');
            } else {
                closeModal();
                await fetchFilings();
                await fetchStatus();
                await fetchEvents();
            }
        } finally {
            btn.disabled = false;
            btn.textContent = 'Score Filing';
        }
    });

    function showToast(msg) {
        const div = document.createElement('div');
        div.className = 'toast';
        div.textContent = msg;
        document.getElementById('toastContainer').appendChild(div);
        setTimeout(() => div.remove(), 4000);
    }

    function formatTime(iso) {
        if (!iso) return '';
        return iso.replace('T', ' ').substring(0, 19);
    }

    function esc(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    el.funnelShowAll.addEventListener('change', () => renderFunnelSnapshot(lastFunnelSnapshot));

    el.btnRunFunnelScan.addEventListener('click', async () => {
        const btn = el.btnRunFunnelScan;
        btn.disabled = true;
        btn.textContent = 'Scanning...';
        try {
            const cap = el.funnelCapital.value || 100000;
            const risk = el.funnelRiskPct.value || 1.0;
            const res = await fetch(`/api/funnel/scan?capitalInr=${encodeURIComponent(cap)}&riskPct=${encodeURIComponent(risk)}`, { method: 'POST' });
            if (res.ok) {
                const snap = await res.json();
                renderFunnelSnapshot(snap);
                fetchEvents();
                showToast(`Funnel scan complete: ${(snap.topSetups || []).length} setup(s) passed`);
            }
        } finally {
            btn.disabled = false;
            btn.textContent = 'Run Funnel Scan';
        }
    });

    // Boot
    fetchWatchlist().then(fetchFilings);
    fetchStatus();
    fetchEvents();
    fetchFunnelSnapshot();
    connectSse();
    setInterval(fetchStatus, 5000);
});

