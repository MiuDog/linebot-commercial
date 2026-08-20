package dev.miudog.linebotcommercial.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 以固定低基數欄位記錄外部網路依賴的 Rate、Error、Duration 事件。 */
@Component
public class NetworkObservationLogger {

	private static final Logger log = LoggerFactory.getLogger(NetworkObservationLogger.class);

	// 方法：記錄外部請求開始，並回傳單調時間供後續計算延遲。
	public long started(String dependency, String operation) {
		long startedAt = System.nanoTime();
		log.atInfo()
			.addKeyValue("event", "network_request_started")
			.addKeyValue("requestId", currentRequestId())
			.addKeyValue("dependency", safeLabel(dependency))
			.addKeyValue("operation", safeLabel(operation))
			.log(
				"event=network_request_started requestId={} dependency={} operation={}",
				currentRequestId(),
				safeLabel(dependency),
				safeLabel(operation)
			);
		return startedAt;
	}

	// 方法：記錄外部請求完成的狀態類別與延遲，不記錄 URL、header 或 body。
	public void completed(String dependency, String operation, long startedAt, int statusCode) {
		String statusClass = statusCode >= 100 && statusCode <= 599
			? statusCode / 100 + "xx"
			: "unknown";
		long durationMs = elapsedMilliseconds(startedAt);
		log.atInfo()
			.addKeyValue("event", "network_request_completed")
			.addKeyValue("requestId", currentRequestId())
			.addKeyValue("dependency", safeLabel(dependency))
			.addKeyValue("operation", safeLabel(operation))
			.addKeyValue("statusClass", statusClass)
			.addKeyValue("outcome", statusCode / 100 == 2 ? "SUCCESS" : "REJECTED")
			.addKeyValue("durationMs", durationMs)
			.log(
				"event=network_request_completed requestId={} dependency={} operation={} "
					+ "statusClass={} outcome={} durationMs={}",
				currentRequestId(),
				safeLabel(dependency),
				safeLabel(operation),
				statusClass,
				statusCode / 100 == 2 ? "SUCCESS" : "REJECTED",
				durationMs
			);
	}

	// 方法：記錄外部請求例外類型與延遲，不記錄可能包含個資或憑證的例外訊息。
	public void failed(String dependency, String operation, long startedAt, Throwable error) {
		String errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
		long durationMs = elapsedMilliseconds(startedAt);
		log.atWarn()
			.addKeyValue("event", "network_request_failed")
			.addKeyValue("requestId", currentRequestId())
			.addKeyValue("dependency", safeLabel(dependency))
			.addKeyValue("operation", safeLabel(operation))
			.addKeyValue("outcome", "ERROR")
			.addKeyValue("errorType", errorType)
			.addKeyValue("durationMs", durationMs)
			.log(
				"event=network_request_failed requestId={} dependency={} operation={} "
					+ "outcome=ERROR errorType={} durationMs={}",
				currentRequestId(),
				safeLabel(dependency),
				safeLabel(operation),
				errorType,
				durationMs
			);
	}

	// 方法：將標籤限制為程式定義值，避免未受信任文字進入日誌。
	private static String safeLabel(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) return "unknown";

		return value;
	}

	// 方法：取得同一互動流程的 correlation ID。
	private static String currentRequestId() {
		String requestId = MDC.get("requestId");
		return requestId == null ? "background" : requestId;
	}

	// 方法：使用單調時鐘計算毫秒延遲，避免系統時間校正造成負值。
	private static long elapsedMilliseconds(long startedAt) {
		return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
	}
}
