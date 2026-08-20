package dev.miudog.linebotcommercial.service.quotation;

public record QuotationDeliveryResult(
	long quotationId,
	long attemptId,
	int attemptCount,
	QuotationDeliveryStatus status,
	boolean alreadyDelivered,
	String providerMessageId,
	String errorSummary
) {}
