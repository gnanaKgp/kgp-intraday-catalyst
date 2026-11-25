package com.catalyst.terminal.service.dhan;

import com.catalyst.terminal.config.TraderDhanProperties;
import com.catalyst.terminal.model.funnel.Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DhanWebSocketFeedServiceTest {

    @Test
    void aggregatesLiveTicksInto5MinuteBarsWithDeltaVolume() {
        TraderDhanProperties props = new TraderDhanProperties();
        DhanAuthService auth = new DhanAuthService(props, HttpClient::newHttpClient, new ObjectMapper());
        DhanWebSocketFeedService ws = new DhanWebSocketFeedService(props, auth, HttpClient::newHttpClient, new ObjectMapper());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        long t1 = ZonedDateTime.of(today, LocalTime.of(9, 16), ZoneId.of("Asia/Kolkata")).toEpochSecond();
        long t2 = ZonedDateTime.of(today, LocalTime.of(9, 18), ZoneId.of("Asia/Kolkata")).toEpochSecond();
        long t3 = ZonedDateTime.of(today, LocalTime.of(9, 21), ZoneId.of("Asia/Kolkata")).toEpochSecond();

        ws.recordBarTick("20224", 200.0, 1000L, t1);
        ws.recordBarTick("20224", 205.0, 2500L, t2);
        ws.recordBarTick("20224", 203.0, 4000L, t3);

        List<Candle> bars = ws.getToday5mCandles("20224");
        assertEquals(2, bars.size());
        assertEquals(200.0, bars.get(0).open(), 0.01);
        assertEquals(205.0, bars.get(0).high(), 0.01);
        assertEquals(1500L, bars.get(0).volume());
        assertEquals(203.0, bars.get(1).close(), 0.01);
        assertEquals(1500L, bars.get(1).volume());
    }
}

