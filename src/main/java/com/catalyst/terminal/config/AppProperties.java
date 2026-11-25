package com.catalyst.terminal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalyst")
public class AppProperties {

    private final Gemini gemini = new Gemini();
    private final Nse nse = new Nse();
    private final Pdf pdf = new Pdf();
    private final Alerts alerts = new Alerts();
    private final Retention retention = new Retention();

    public Gemini getGemini() { return gemini; }
    public Nse getNse() { return nse; }
    public Pdf getPdf() { return pdf; }
    public Alerts getAlerts() { return alerts; }
    public Retention getRetention() { return retention; }

    public static class Gemini {
        private String apiKey = "";
        private String model = "gemini-3.8-flash";
        private String fallbackModel = "gemini-3.5-flash-lite";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
    }

    public static class Nse {
        private String feedUrl = "https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml";
        private boolean pollerEnabled = true;
        private long pollDelayMs = 3000;
        private boolean marketHoursOnly = false;

        public String getFeedUrl() { return feedUrl; }
        public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }
        public boolean isPollerEnabled() { return pollerEnabled; }
        public void setPollerEnabled(boolean pollerEnabled) { this.pollerEnabled = pollerEnabled; }
        public long getPollDelayMs() { return pollDelayMs; }
        public void setPollDelayMs(long pollDelayMs) { this.pollDelayMs = pollDelayMs; }
        public boolean isMarketHoursOnly() { return marketHoursOnly; }
        public void setMarketHoursOnly(boolean marketHoursOnly) { this.marketHoursOnly = marketHoursOnly; }
    }

    public static class Pdf {
        private int maxPages = 6;
        private int maxChars = 10000;

        public int getMaxPages() { return maxPages; }
        public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    }

    public static class Alerts {
        private boolean telegramEnabled = false;
        private String telegramBotToken = "";
        private String telegramChatId = "";
        private int minScore = 70;
        private int watchlistMinScore = 40;

        public boolean isTelegramEnabled() { return telegramEnabled; }
        public void setTelegramEnabled(boolean telegramEnabled) { this.telegramEnabled = telegramEnabled; }
        public String getTelegramBotToken() { return telegramBotToken; }
        public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }
        public String getTelegramChatId() { return telegramChatId; }
        public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
        public int getMinScore() { return minScore; }
        public void setMinScore(int minScore) { this.minScore = minScore; }
        public int getWatchlistMinScore() { return watchlistMinScore; }
        public void setWatchlistMinScore(int watchlistMinScore) { this.watchlistMinScore = watchlistMinScore; }
    }

    public static class Retention {
        private int days = 365;

        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
    }
}

