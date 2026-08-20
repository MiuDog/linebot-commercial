package dev.miudog.linebotcommercial.service.quotation;

/**
 * 正式確認交易所需的已確認草稿與程式計價結果。
 */
public record QuotationConfirmationCommand(
	QuotationConfirmationIntent confirmationIntent,
	QuotationCalculationResult calculation
) {}
