package dev.miudog.linebotcommercial.service.quotation;

import java.nio.file.Path;

/**
 * 已從草稿暫存區移入正式報價資料夾的單一原圖。
 */
public record QuotationArchivedAsset(
	long assetId,
	String messageId,
	Path path,
	boolean selected
) {}
