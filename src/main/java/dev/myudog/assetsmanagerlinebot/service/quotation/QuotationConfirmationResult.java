package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.time.LocalDate;

/**
 * 正式報價完成配號與快照後的穩定識別資料。
 */
public record QuotationConfirmationResult(
	long quotationId,
	String quotationNumber,
	LocalDate quotationDate,
	LocalDate validUntil,
	int sequenceNumber,
	String folderName,
	String fileBaseName
) {}
