package com.catalyst.terminal.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fetches corporate announcements from BSE India API for overnight & live multi-exchange coverage.
 */
@Component
public class BseFeedGateway {

    private static final Logger log = LoggerFactory.getLogger(BseFeedGateway.class);
    private static final DateTimeFormatter BSE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    public BseFeedGateway(Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    public List<BseAnnouncement> fetchAnnouncements(LocalDate from, LocalDate to) {
        List<BseAnnouncement> results = new ArrayList<>();
        try {
            String url = String.format(
                    "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w?pageno=1&strCat=-1&strPrevDate=%s&strScrip=&strSearch=P&strToDate=%s&strType=C&subcategory=",
                    from.format(BSE_DATE), to.format(BSE_DATE)
            );
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bseindia.com/")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClientSupplier.get()
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .get(10, TimeUnit.SECONDS);

            if (res.statusCode() != 200 || res.body() == null) {
                log.debug("BSE announcements returned HTTP {}", res.statusCode());
                return results;
            }
            JsonNode table = objectMapper.readTree(res.body()).path("Table");
            if (!table.isArray()) return results;

            for (JsonNode row : table) {
                String newsId = row.path("NEWSID").asText("");
                String company = row.path("SLONGNAME").asText("").trim();
                String headline = row.path("HEADLINE").asText("").trim();
                String newsSub = row.path("NEWSSUB").asText("").trim();
                String attach = row.path("ATTACHMENTNAME").asText("").trim();
                String dt = row.path("NEWS_DT").asText("").trim();
                String pdfUrl = attach.isBlank() ? "" : "https://www.bseindia.com/xml-data/corpfiling/AttachLive/" + attach;
                String combinedHeadline = newsSub.isBlank() ? headline : (headline.isBlank() ? newsSub : newsSub + " — " + headline);

                if (!newsId.isBlank() && !company.isBlank()) {
                    results.add(new BseAnnouncement("BSE_" + newsId, company, combinedHeadline, pdfUrl, dt));
                }
            }
        } catch (Exception e) {
            log.debug("BSE announcements fetch skipped: {}", e.getMessage());
        }
        return results;
    }

    public record BseAnnouncement(String uid, String companyName, String headline, String attachmentUrl, String newsDt) {}
}

