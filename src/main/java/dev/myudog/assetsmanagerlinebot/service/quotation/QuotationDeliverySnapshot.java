package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.math.BigDecimal;
import java.time.Instant;

public record QuotationDeliverySnapshot(
	long quotationId,
	String companyName,
	String workName,
	String quotationNumber,
	String currency,
	BigDecimal subtotal,
	BigDecimal taxAmount,
	BigDecimal totalAmount,
	Instant createdAt
) {

	// 方法：保留舊呼叫端的相容建構式，未提供建立時間時不顯示執行時間。
	public QuotationDeliverySnapshot(
		long quotationId,
		String companyName,
		String workName,
		String quotationNumber,
		String currency,
		BigDecimal subtotal,
		BigDecimal taxAmount,
		BigDecimal totalAmount
	) {
		this(
			quotationId,
			companyName,
			workName,
			quotationNumber,
			currency,
			subtotal,
			taxAmount,
			totalAmount,
			null
		);
	}
}
