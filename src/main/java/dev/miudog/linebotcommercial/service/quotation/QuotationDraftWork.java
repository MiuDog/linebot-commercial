package dev.miudog.linebotcommercial.service.quotation;

public record QuotationDraftWork(
	QuotationDraftSnapshot draft,
	QuotationCalculationResult calculation
) {}
