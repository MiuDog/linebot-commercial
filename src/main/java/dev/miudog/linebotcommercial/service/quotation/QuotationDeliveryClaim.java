package dev.miudog.linebotcommercial.service.quotation;

public record QuotationDeliveryClaim(
	long attemptId,
	int attemptCount,
	State state,
	String providerMessageId
) {

	public enum State {
		READY,
		IN_PROGRESS,
		ALREADY_SENT
	}

	// 方法：建立可立即送出的新交付嘗試宣告。
	public static QuotationDeliveryClaim ready(long attemptId, int attemptCount) {
		return new QuotationDeliveryClaim(attemptId, attemptCount, State.READY, null);
	}

	// 方法：建立另一個工作者已取得發送權的宣告。
	public static QuotationDeliveryClaim inProgress(long attemptId, int attemptCount) {
		return new QuotationDeliveryClaim(attemptId, attemptCount, State.IN_PROGRESS, null);
	}

	// 方法：建立先前已成功交付的冪等結果宣告。
	public static QuotationDeliveryClaim alreadySent(
		long attemptId,
		int attemptCount,
		String providerMessageId
	) {
		return new QuotationDeliveryClaim(
			attemptId,
			attemptCount,
			State.ALREADY_SENT,
			providerMessageId
		);
	}
}
