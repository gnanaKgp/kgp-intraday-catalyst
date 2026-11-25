package com.catalyst.terminal.gateway;

import java.util.List;

public interface TelegramGateway {
    boolean sendHtmlMessage(String botToken, String chatId, String htmlText);
    List<TelegramUpdate> fetchUpdates(String botToken, long offset);

    record TelegramUpdate(long updateId, String chatId, String text) {}
}

