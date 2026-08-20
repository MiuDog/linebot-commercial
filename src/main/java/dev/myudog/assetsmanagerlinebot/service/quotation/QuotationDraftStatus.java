package dev.myudog.assetsmanagerlinebot.service.quotation;

public enum QuotationDraftStatus {
	COLLECTING_BASE_INFO,
	COLLECTING_ITEMS,
	AWAITING_IMAGE,
	READY_FOR_PREVIEW,
	AWAITING_CONFIRMATION,
	CONFIRMED,
	CANCELLED,
	EXPIRED
}
