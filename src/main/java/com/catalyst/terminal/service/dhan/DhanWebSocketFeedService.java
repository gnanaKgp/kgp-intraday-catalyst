package com.catalyst.terminal.service.dhan;

import com.catalyst.terminal.config.TraderDhanProperties;
import com.catalyst.terminal.model.funnel.Candle;
import com.catalyst.terminal.model.funnel.DhanQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * DhanHQ v2 Binary Live Market Feed WebSocket client (wss://api-feed.dhan.co?version=2).
 * Decodes Little-Endian Ticker (code 2), Quote (code 4), and Full (code 8) packets.
 */
@Service
public class DhanWebSocketFeedService implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(DhanWebSocketFeedService.class);

    private final TraderDhanProperties properties;
    private final DhanAuthService authService;
    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Map<String, DhanQuote> liveQuotes = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentSkipListMap<Long, MutableBar>> ws5mBars = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenCumVolume = new ConcurrentHashMap<>();
    private final Set<String> subscribedNseEq = ConcurrentHashMap.newKeySet();
    private final Set<String> subscribedIdx = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final List<ByteBuffer> partialFragments = Collections.synchronizedList(new ArrayList<>());

    private volatile WebSocket webSocket;

    public DhanWebSocketFeedService(
            TraderDhanProperties properties,
            DhanAuthService authService,
            Supplier<HttpClient> httpClientSupplier,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.authService = authService;
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public Optional<DhanQuote> getLiveQuote(String securityId) {
        return Optional.ofNullable(liveQuotes.get(securityId));
    }

    public Map<String, DhanQuote> getAllLiveQuotes() {
        return Collections.unmodifiableMap(liveQuotes);
    }

    public List<Candle> getToday5mCandles(String securityId) {
        ConcurrentSkipListMap<Long, MutableBar> map = ws5mBars.get(securityId);
        if (map == null || map.isEmpty()) return List.of();
        LocalDate today = LocalDate.now(IST);
        List<Candle> out = new ArrayList<>();
        for (MutableBar b : map.values()) {
            LocalDate barDate = Instant.ofEpochSecond(b.bucketEpoch).atZone(IST).toLocalDate();
            if (barDate.equals(today)) {
                out.add(b.toCandle());
            }
        }
        return out;
    }

    public synchronized void connectAndSubscribe(Collection<String> nseSecurityIds, Collection<String> idxSecurityIds) {
        if (!authService.isConfigured()) {
            return;
        }
        Optional<String> tokenOpt = authService.getValidAccessToken();
        if (tokenOpt.isEmpty()) {
            return;
        }

        if (nseSecurityIds != null) subscribedNseEq.addAll(nseSecurityIds);
        if (idxSecurityIds != null) subscribedIdx.addAll(idxSecurityIds);

        if (!connected.get() || webSocket == null) {
            try {
                String wsUri = properties.getWsUrl()
                        + "?version=" + properties.getVersion()
                        + "&token=" + tokenOpt.get()
                        + "&clientId=" + authService.getClientId()
                        + "&authType=" + properties.getAuthType();

                webSocket = httpClientSupplier.get()
                        .newWebSocketBuilder()
                        .buildAsync(URI.create(wsUri), this)
                        .join();
                connected.set(true);
                log.info("Connected to DhanHQ v2 Live Market Feed WebSocket.");
            } catch (Exception e) {
                connected.set(false);
                log.warn("DhanHQ WebSocket connection failed: {}", e.getMessage());
                return;
            }
        }

        sendSubscriptions("NSE_EQ", subscribedNseEq);
        sendSubscriptions("IDX_I", subscribedIdx);
    }

    private void sendSubscriptions(String segment, Set<String> secIds) {
        if (webSocket == null || secIds.isEmpty()) return;
        List<String> list = new ArrayList<>(secIds);
        for (int i = 0; i < list.size(); i += 100) {
            List<String> batch = list.subList(i, Math.min(i + 100, list.size()));
            List<Map<String, String>> instList = new ArrayList<>();
            for (String id : batch) {
                instList.add(Map.of("ExchangeSegment", segment, "SecurityId", id));
            }
            Map<String, Object> req = Map.of(
                    "RequestCode", properties.getRequestCode(),
                    "InstrumentCount", instList.size(),
                    "InstrumentList", instList
            );
            try {
                webSocket.sendText(objectMapper.writeValueAsString(req), true);
            } catch (Exception e) {
                log.warn("Failed sending Dhan WS subscription batch: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void disconnect() {
        WebSocket ws = webSocket;
        connected.set(false);
        if (ws != null) {
            try {
                ws.sendText("{\"RequestCode\":12}", true);
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Shutdown");
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        partialFragments.add(data);
        if (last) {
            ByteBuffer combined = combineFragments();
            parseBinaryPacket(combined);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected.set(false);
        log.warn("DhanHQ WebSocket error: {}", error.getMessage());
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected.set(false);
        log.info("DhanHQ WebSocket closed ({}): {}", statusCode, reason);
        return null;
    }

    private ByteBuffer combineFragments() {
        synchronized (partialFragments) {
            if (partialFragments.size() == 1) {
                ByteBuffer single = partialFragments.remove(0);
                return single;
            }
            int total = 0;
            for (ByteBuffer b : partialFragments) total += b.remaining();
            ByteBuffer merged = ByteBuffer.allocate(total);
            for (ByteBuffer b : partialFragments) merged.put(b);
            partialFragments.clear();
            merged.flip();
            return merged;
        }
    }

    void parseBinaryPacket(ByteBuffer buf) {
        if (buf == null || buf.remaining() < 8) return;
        buf.order(ByteOrder.LITTLE_ENDIAN);

        byte responseCode = buf.get();
        short msgLen = buf.getShort();
        byte segByte = buf.get();
        int secIdInt = buf.getInt();
        String secId = String.valueOf(secIdInt);
        String segment = segByte == 0 ? "IDX_I" : "NSE_EQ";

        if (responseCode == 50) {
            if (buf.remaining() >= 2) {
                short disconnectCode = buf.getShort();
                log.warn("DhanHQ WS disconnect packet received: code={}", disconnectCode);
            }
            connected.set(false);
            return;
        }

        if (responseCode == 2 && buf.remaining() >= 8) {
            // Ticker packet: float32 LTP + int32 LTT
            double ltp = round2(buf.getFloat());
            long ltt = Integer.toUnsignedLong(buf.getInt());
            recordBarTick(secId, ltp, 0L, ltt);
            DhanQuote prev = liveQuotes.get(secId);
            if (prev != null) {
                liveQuotes.put(secId, new DhanQuote(
                        secId, segment, ltp, prev.atp(), prev.volume(),
                        prev.open(), Math.max(prev.high(), ltp), Math.min(prev.low(), ltp),
                        prev.prevClose(), prev.totalBuyQty(), prev.totalSellQty(),
                        prev.bestBid(), prev.bestAsk(), prev.spreadPct(), Instant.now()
                ));
            } else {
                liveQuotes.put(secId, new DhanQuote(
                        secId, segment, ltp, ltp, 0, ltp, ltp, ltp, ltp, 0, 0, 0, 0, 0, Instant.now()
                ));
            }
        } else if ((responseCode == 4 || responseCode == 8) && buf.remaining() >= 42) {
            // Quote / Full packet layout after 8-byte header
            double ltp = round2(buf.getFloat());
            buf.getShort(); // LTQ
            long ltt = Integer.toUnsignedLong(buf.getInt());   // LTT
            double atp = round2(buf.getFloat());
            long vol = Integer.toUnsignedLong(buf.getInt());
            recordBarTick(secId, ltp, vol, ltt);
            long sellQty = Integer.toUnsignedLong(buf.getInt());
            long buyQty = Integer.toUnsignedLong(buf.getInt());
            double open = round2(buf.getFloat());
            double close = round2(buf.getFloat());
            double high = round2(buf.getFloat());
            double low = round2(buf.getFloat());

            double bestBid = 0.0;
            double bestAsk = 0.0;
            double spreadPct = 0.0;
            if (responseCode == 8 && buf.remaining() >= 20) {
                // Full packet first market depth level (20 bytes: buyQty(4), sellQty(4), buyOrders(2), sellOrders(2), bidPrice(4), askPrice(4))
                buf.getInt();
                buf.getInt();
                buf.getShort();
                buf.getShort();
                bestBid = round2(buf.getFloat());
                bestAsk = round2(buf.getFloat());
                if (bestBid > 0 && bestAsk >= bestBid && ltp > 0) {
                    spreadPct = ((bestAsk - bestBid) / ltp) * 100.0;
                }
            }

            DhanQuote prev = liveQuotes.get(secId);
            if (bestBid == 0.0 && prev != null) {
                bestBid = prev.bestBid();
                bestAsk = prev.bestAsk();
                spreadPct = prev.spreadPct();
            }

            liveQuotes.put(secId, new DhanQuote(
                    secId, segment, ltp, atp, vol, open, high, low, close,
                    buyQty, sellQty, bestBid, bestAsk, spreadPct, Instant.now()
            ));
        }
    }

    void recordBarTick(String secId, double ltp, long cumVolume, long tickEpoch) {
        if (ltp <= 0) return;
        long ts = tickEpoch > 0 ? tickEpoch : Instant.now().getEpochSecond();
        long bucket = (ts / 300L) * 300L;
        long deltaVol = 0L;
        if (cumVolume > 0) {
            Long prevVol = lastSeenCumVolume.put(secId, cumVolume);
            if (prevVol != null && cumVolume >= prevVol) {
                deltaVol = cumVolume - prevVol;
            }
        }
        ConcurrentSkipListMap<Long, MutableBar> bars = ws5mBars.computeIfAbsent(secId, k -> new ConcurrentSkipListMap<>());
        long finalDelta = deltaVol;
        bars.compute(bucket, (b, existing) -> {
            if (existing == null) {
                return new MutableBar(bucket, ltp, ltp, ltp, ltp, finalDelta);
            }
            existing.high = Math.max(existing.high, ltp);
            existing.low = Math.min(existing.low, ltp);
            existing.close = ltp;
            existing.volume += finalDelta;
            return existing;
        });
        while (bars.size() > 100) {
            bars.pollFirstEntry();
        }
    }

    private static final class MutableBar {
        final long bucketEpoch;
        final double open;
        double high;
        double low;
        double close;
        long volume;

        MutableBar(long bucketEpoch, double open, double high, double low, double close, long volume) {
            this.bucketEpoch = bucketEpoch;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        Candle toCandle() {
            return new Candle(bucketEpoch, open, high, low, close, volume);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

