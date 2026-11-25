package com.catalyst.terminal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trader.dhan")
public class TraderDhanProperties {

    private boolean enabled = false;
    private String clientId = "";
    private String accessToken = "";
    private String wsUrl = "wss://api-feed.dhan.co";
    private int requestCode = 17;
    private int authType = 2;
    private int version = 2;
    private String pin = "";
    private String totpSecret = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }
    public int getRequestCode() { return requestCode; }
    public void setRequestCode(int requestCode) { this.requestCode = requestCode; }
    public int getAuthType() { return authType; }
    public void setAuthType(int authType) { this.authType = authType; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
}

