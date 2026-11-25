package com.catalyst.terminal.gateway;

import com.catalyst.terminal.config.AppProperties;
import com.catalyst.terminal.model.Direction;
import com.catalyst.terminal.model.FilingCategory;
import com.catalyst.terminal.model.GeminiCatalystResult;
import com.catalyst.terminal.model.MarketSnapshot;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class GeminiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmGateway.class);

    public static final String INSTITUTIONAL_SYSTEM_PROMPT = """
            You are an institutional event-driven equity analyst specializing in Indian stock markets (NSE/BSE). Your sole job is to evaluate corporate announcements, regulatory filings, and breaking news to determine their immediate intraday trading impact.

            Analyze the provided filing strictly from an institutional order-flow perspective. Ignore corporate PR fluff and focus only on quantifiable financial, strategic, or regulatory impact.

            ### SCORING RUBRIC (catalyst_score: 0 to 100)
            - 90-100 (Tier 1 - High-Conviction / Circuit Potential):
              * Firm Order Win / LOI where Order Value is >= 15% of Market Cap.
              * Strategic Joint Venture or tech transfer with a marquee global partner into a high-margin vertical (e.g., Railways, Defence, EV, Semiconductors).
              * Blockbuster quarterly earnings beat (YoY revenue/PAT jump > 40% with margin expansion) or major upward guidance revision.
              * Major USFDA/regulatory approval for a key product, or a major negative shock (SEBI ban, USFDA warning letter, Auditor/CFO abrupt resignation, plant shutdown).
            - 70-89 (Tier 2 - Strong Intraday Mover):
              * Firm Order Win where Order Value is 5%-15% of Market Cap.
              * Solid earnings beat, major capex/capacity expansion, or marquee FII/DII bulk/block deal.
              * Significant negative earnings miss or large promoter stake sale at a steep discount.
            - 40-69 (Tier 3 - Moderate / Needs Technical Confirmation):
              * Order win < 5% of Market Cap.
              * Non-binding MoUs without firm financial values, credit rating upgrades, or minor product launches.
            - 0-39 (Noise - Discard):
              * Routine compliance filings: Board meeting intimations, trading window closures, AGM/EGM notices, postal ballots, loss of share certificates, routine investor presentation uploads, or appointment/resignation of Company Secretary/independent directors.

            ### MANDATORY RULES
            1. Order-to-Market-Cap Check: If a contract, LOI, or order value is mentioned in INR Crores (or USD converted at ~84 INR/USD), calculate order_to_mcap_pct = (order_value_cr / market_cap_cr) * 100 using the provided Market Cap. If Market Cap is unavailable, set order_to_mcap_pct to null.
            2. Anti-Backlog Rule: Do NOT treat cumulative/historical order book backlog figures inside quarterly earnings releases or investor presentations as a new ORDER_WIN.
            3. MoU Penalty: If the announcement is a non-binding "MoU" or "Letter of Intent without commercial value", cap catalyst_score at 65 unless it involves a marquee global/government partner.
            4. Direction: Classify as BULLISH, BEARISH, or NEUTRAL.
            5. Actionable Flag: Set is_actionable_intraday to true ONLY if catalyst_score >= 70 and direction is BULLISH or BEARISH.
            6. No Hallucination: Extract numbers strictly from the provided text and context. If a value is missing, return null.
            7. Output Format: Return ONLY a valid JSON object matching the required schema.
            """;

    private final AppProperties properties;
    private final Supplier<HttpClient> httpClientSupplier;
    private final ObjectMapper objectMapper;

    public GeminiLlmGateway(AppProperties properties, Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClientSupplier = httpClientSupplier;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeminiCatalystResult evaluateFiling(String symbol, String companyName, String filingText, MarketSnapshot market) {
        String apiKey = properties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY is not configured; returning fallback NEUTRAL result for {}", symbol);
            return new GeminiCatalystResult(
                    symbol, Direction.NEUTRAL, 0, FilingCategory.NOISE,
                    null, null, false,
                    "Gemini API key missing",
                    "Configure GEMINI_API_KEY to enable automated institutional catalyst evaluation.",
                    "Missing API credentials"
            );
        }

        int maxChars = Math.max(1000, properties.getPdf().getMaxChars());
        String trimmed = filingText.length() > maxChars ? filingText.substring(0, maxChars) : filingText;
        String mcapCtx = (market != null && market.marketCapCr() != null)
                ? String.format("₹%.2f Crores", market.marketCapCr())
                : "Unknown";
        String ltpCtx = (market != null && market.ltp() != null)
                ? String.format("₹%.2f", market.ltp())
                : "Unknown";
        String chgCtx = (market != null && market.dayChangePct() != null)
                ? String.format("%.2f%%", market.dayChangePct())
                : "Unknown";

        String userPrompt = String.format("""
                %s

                ### MARKET CONTEXT
                Symbol: %s
                Company Name: %s
                Market Cap (INR Cr): %s
                Current LTP: %s
                Intraday Change: %s

                ### FILING TEXT
                %s
                """, INSTITUTIONAL_SYSTEM_PROMPT, symbol, companyName, mcapCtx, ltpCtx, chgCtx, trimmed);

        List<String> modelsToTry = new java.util.ArrayList<>();
        if (properties.getGemini().getModel() != null && !properties.getGemini().getModel().isBlank()) {
            modelsToTry.add(properties.getGemini().getModel().trim());
        }
        String fallbackModel = properties.getGemini().getFallbackModel();
        if (fallbackModel != null && !fallbackModel.isBlank() && !modelsToTry.contains(fallbackModel.trim())) {
            modelsToTry.add(fallbackModel.trim());
        }

        Map<String, Object> payload = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "responseMimeType", "application/json",
                        "responseSchema", buildResponseSchema()
                )
        );

        for (String model : modelsToTry) {
            GeminiCatalystResult result = callGeminiModel(model, apiKey.trim(), symbol, payload);
            if (result != null) {
                return result;
            }
        }

        return new GeminiCatalystResult(
                symbol, Direction.NEUTRAL, 0, FilingCategory.NOISE,
                null, null, false,
                "Evaluation unavailable",
                "LLM gateway failed to score filing after retry.",
                "Upstream API error"
        );
    }

    private GeminiCatalystResult callGeminiModel(String model, String apiKey, String symbol, Map<String, Object> payload) {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();

                HttpResponse<String> res = httpClientSupplier.get()
                        .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                        .get(30, TimeUnit.SECONDS);

                if (res.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(res.body());
                    String jsonText = root.at("/candidates/0/content/parts/0/text").asText();
                    log.info("Gemini model {} scored {}", model, symbol);
                    return objectMapper.readValue(jsonText, GeminiCatalystResult.class);
                }

                if (attempt < 2 && (res.statusCode() == 429 || res.statusCode() == 503)) {
                    log.warn("Gemini HTTP {} on {} for {}; retrying...", res.statusCode(), model, symbol);
                    Thread.sleep(1000);
                } else {
                    log.error("Gemini error HTTP {} on {} for {}: {}", res.statusCode(), model, symbol, res.body());
                    break;
                }
            } catch (Exception e) {
                if (attempt < 2) {
                    log.warn("Gemini call exception on {} attempt 1 for {}: {}", model, symbol, e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("Gemini evaluation failed on {} for {}: {}", model, symbol, e.getMessage());
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", Map.of("type", "STRING"));
        props.put("direction", Map.of("type", "STRING", "enum", List.of("BULLISH", "BEARISH", "NEUTRAL")));
        props.put("catalyst_score", Map.of("type", "INTEGER"));
        props.put("category", Map.of("type", "STRING", "enum", List.of(
                "ORDER_WIN", "JOINT_VENTURE", "EARNINGS", "REGULATORY", "CORPORATE_ACTION", "BLOCK_DEAL", "NOISE"
        )));
        props.put("order_value_cr", Map.of("type", "NUMBER", "nullable", true));
        props.put("order_to_mcap_pct", Map.of("type", "NUMBER", "nullable", true));
        props.put("is_actionable_intraday", Map.of("type", "BOOLEAN"));
        props.put("headline_summary", Map.of("type", "STRING"));
        props.put("institutional_rationale", Map.of("type", "STRING"));
        props.put("key_risk", Map.of("type", "STRING"));

        return Map.of(
                "type", "OBJECT",
                "properties", props,
                "required", List.of(
                        "symbol", "direction", "catalyst_score", "category",
                        "order_value_cr", "order_to_mcap_pct", "is_actionable_intraday",
                        "headline_summary", "institutional_rationale", "key_risk"
                )
        );
    }
}

