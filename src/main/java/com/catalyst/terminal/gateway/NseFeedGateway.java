package com.catalyst.terminal.gateway;

import java.util.Optional;

public interface NseFeedGateway {
    Optional<String> fetchAnnouncementsXml(String feedUrl);
    byte[] downloadAttachmentBytes(String attachmentUrl);
}

