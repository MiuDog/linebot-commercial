package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.time.Instant;

/** LINE Flex Message 使用的 PDF 安全下載連結。 */
public record QuotationDownloadLink(String url, Instant expiresAt) {}
