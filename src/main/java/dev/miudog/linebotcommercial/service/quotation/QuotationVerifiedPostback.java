package dev.miudog.linebotcommercial.service.quotation;

import java.time.Instant;

public record QuotationVerifiedPostback(
	long draftId,
	int revision,
	QuotationPostbackAction action,
	Instant expiresAt,
	String resourceId
) {
	// 方法：保留既有不攜帶資源識別的 postback 建構方式。
	public QuotationVerifiedPostback(
		long draftId,
		int revision,
		QuotationPostbackAction action,
		Instant expiresAt
	) {
		this(draftId, revision, action, expiresAt, null);
	}
}
