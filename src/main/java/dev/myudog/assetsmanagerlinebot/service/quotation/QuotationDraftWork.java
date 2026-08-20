package dev.myudog.assetsmanagerlinebot.service.quotation;

public record QuotationDraftWork(
	QuotationDraftSnapshot draft,
	QuotationCalculationResult calculation
) {}
