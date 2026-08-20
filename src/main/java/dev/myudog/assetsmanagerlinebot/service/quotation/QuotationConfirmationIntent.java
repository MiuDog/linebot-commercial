package dev.myudog.assetsmanagerlinebot.service.quotation;

public record QuotationConfirmationIntent(
	QuotationDraftSnapshot draft,
	String confirmationEventId,
	String quotationSequence
) {}
