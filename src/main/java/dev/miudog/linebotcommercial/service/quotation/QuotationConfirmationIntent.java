package dev.miudog.linebotcommercial.service.quotation;

public record QuotationConfirmationIntent(
	QuotationDraftSnapshot draft,
	String confirmationEventId,
	String quotationSequence
) {}
