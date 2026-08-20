package dev.myudog.assetsmanagerlinebot.service.quotation;

public enum QuotationNextAction {
	REQUEST_BASE_FIELDS,
	REQUEST_ITEM_FIELDS,
	REQUEST_IMAGE,
	REQUEST_IMAGE_SELECTION,
	SHOW_PREVIEW,
	REQUEST_CONFIRMATION,
	CONFIRMED,
	CANCELLED,
	EXPIRED
}
