package dev.miudog.linebotcommercial.service.quotation;

import java.nio.file.Path;

/** 已通過權杖、期限與路徑驗證的 PDF 下載資源。 */
public record QuotationDownloadResource(
	Path path,
	String fileName,
	String contentType,
	long fileSize
) {}
