package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.LineStorageService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * 以 SQLite 租約恢復並逐筆執行正式報價產生工作。
 */
@Service
public class QuotationGenerationJobWorker {

	private static final Logger log = LoggerFactory.getLogger(QuotationGenerationJobWorker.class);
	private static final Duration MAXIMUM_RETRY_DELAY = Duration.ofMinutes(15);
	private static final Pattern SENSITIVE_VALUE = Pattern.compile(
		"(?i)(authorization|credential|password|secret|token|api[-_ ]?key)(\\s*[=:]\\s*)\\S+"
	);
	private static final Pattern URL_USER_INFO = Pattern.compile("://[^\\s/@:]+:[^\\s/@]+@");

	private final QuotationGenerationJobRepository jobs;
	private final QuotationGenerationSnapshotRepository snapshots;
	private final QuotationGenerationCoordinator coordinator;
	private final LineStorageService line;
	private final TaskExecutor executor;
	private final Clock clock;
	private final String workerId;
	private final Duration leaseDuration;
	private final int maximumBatchSize;
	private final boolean enabled;
	private final AtomicBoolean wakeScheduled = new AtomicBoolean();

	// 方法：建立正式環境的持久化背景工作者。
	@Autowired
	public QuotationGenerationJobWorker(
		QuotationGenerationJobRepository jobs,
		QuotationGenerationSnapshotRepository snapshots,
		QuotationGenerationCoordinator coordinator,
		LineStorageService line,
		@Qualifier("quotationGenerationTaskExecutor") TaskExecutor executor,
		@Value("${app.quotation.generation-lease-seconds:120}") long leaseSeconds,
		@Value("${app.quotation.generation-batch-size:20}") int maximumBatchSize,
		@Value("${app.quotation.generation-worker-enabled:true}") boolean enabled
	) {
		this(
			jobs,
			snapshots,
			coordinator,
			line,
			executor,
			Clock.systemUTC(),
			"quotation-" + UUID.randomUUID(),
			Duration.ofSeconds(leaseSeconds),
			maximumBatchSize,
			enabled
		);
	}

	// 方法：提供聚焦測試可控制時間、租約及工作者識別的建構入口。
	QuotationGenerationJobWorker(
		QuotationGenerationJobRepository jobs,
		QuotationGenerationSnapshotRepository snapshots,
		QuotationGenerationCoordinator coordinator,
		TaskExecutor executor,
		Clock clock,
		String workerId,
		Duration leaseDuration,
		int maximumBatchSize
	) {
		this(
			jobs,
			snapshots,
			coordinator,
			null,
			executor,
			clock,
			workerId,
			leaseDuration,
			maximumBatchSize,
			true
		);
	}

	// 方法：提供聚焦測試注入 LINE 邊界，以驗證首次失敗通知。
	QuotationGenerationJobWorker(
		QuotationGenerationJobRepository jobs,
		QuotationGenerationSnapshotRepository snapshots,
		QuotationGenerationCoordinator coordinator,
		LineStorageService line,
		TaskExecutor executor,
		Clock clock,
		String workerId,
		Duration leaseDuration,
		int maximumBatchSize
	) {
		this(
			jobs,
			snapshots,
			coordinator,
			line,
			executor,
			clock,
			workerId,
			leaseDuration,
			maximumBatchSize,
			true
		);
	}

	// 方法：建立可停用啟動與排程喚醒的完整背景工作者。
	private QuotationGenerationJobWorker(
		QuotationGenerationJobRepository jobs,
		QuotationGenerationSnapshotRepository snapshots,
		QuotationGenerationCoordinator coordinator,
		LineStorageService line,
		TaskExecutor executor,
		Clock clock,
		String workerId,
		Duration leaseDuration,
		int maximumBatchSize,
		boolean enabled
	) {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("背景工作租約必須大於零");
		}
		if (maximumBatchSize < 1 || maximumBatchSize > 100) {
			throw new IllegalArgumentException("單次背景工作數量必須介於 1 到 100");
		}

		this.jobs = jobs;
		this.snapshots = snapshots;
		this.coordinator = coordinator;
		this.line = line;
		this.executor = executor;
		this.clock = clock;
		this.workerId = workerId;
		this.leaseDuration = leaseDuration;
		this.maximumBatchSize = maximumBatchSize;
		this.enabled = enabled;
	}

	// 方法：應用程式啟動完成後立即喚醒，接續前一次程序留下的待辦或過期租約。
	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		wake();
	}

	// 方法：定期喚醒持久化工作；執行器只負責降低延遲，不保存工作真實狀態。
	@Scheduled(
		fixedDelayString = "${app.quotation.generation-poll-interval-ms:5000}",
		initialDelayString = "${app.quotation.generation-poll-initial-delay-ms:5000}"
	)
	public void poll() {
		wake();
	}

	// 方法：以有界執行器觸發資料庫 drain；拒絕時工作仍留在 PENDING 等候下次排程。
	public void wake() {
		if (!enabled) return;

		if (!wakeScheduled.compareAndSet(false, true)) return;

		try {
			// 外部呼叫：執行器只承載喚醒訊號，工作內容已持久化於 SQLite。
			executor.execute(() -> {
				try {
					drainAvailable();
				}
				finally {
					wakeScheduled.set(false);
				}
			});
		}
		catch (TaskRejectedException exception) {
			wakeScheduled.set(false);
			log.atWarn()
				.addKeyValue("event", "quotation_generation_wakeup_rejected")
				.log("event={}", "quotation_generation_wakeup_rejected");
		}
	}

	// 方法：依租約逐筆處理目前到期工作，回傳本輪完成數供測試及監控使用。
	int drainAvailable() {
		int completed = 0;
		for (int index = 0; index < maximumBatchSize; index++) {
			Instant now = clock.instant();
			Optional<QuotationGenerationJobRepository.Job> candidate = jobs.leaseNext(
				workerId,
				now,
				leaseDuration
			);
			if (candidate.isEmpty()) break;

			if (process(candidate.get(), now)) completed++;
		}
		return completed;
	}

	// 方法：以工作保存的 correlation id 還原日誌鏈，成功或失敗都由租約 CAS 收斂。
	private boolean process(QuotationGenerationJobRepository.Job job, Instant now) {
		String previousRequestId = MDC.get("requestId");
		MDC.put("requestId", job.correlationId());
		try {
			QuotationConfirmedGenerationCommand command = snapshots.load(job.quotationId());
			coordinator.resumeConfirmed(command);
			boolean completed = jobs.markDone(job.id(), workerId);
			if (!completed) logLeaseLost(job);

			return completed;
		}
		catch (RuntimeException exception) {
			String errorCode = errorCode(exception);
			Throwable rootCause = rootCause(exception);
			boolean failed = jobs.markFailed(
				job.id(),
				workerId,
				errorCode,
				now.plus(retryDelay(job.attemptCount()))
			);
			if (!failed) logLeaseLost(job);
			if (failed && job.attemptCount() == 1) notifyFirstFailure(job, errorCode);

			log.atError()
				.addKeyValue("event", "quotation_generation_job_failed")
				.addKeyValue("quotationId", job.quotationId())
				.addKeyValue("attemptCount", job.attemptCount())
				.addKeyValue("errorCode", errorCode)
				.addKeyValue("rootErrorType", rootCause.getClass().getSimpleName())
				.addKeyValue("rootErrorMessage", safeRootMessage(rootCause))
				.log(
					"event={} quotationId={} attemptCount={} errorCode={}",
					"quotation_generation_job_failed",
					job.quotationId(),
					job.attemptCount(),
					errorCode
				);
			return false;
		}
		finally {
			if (previousRequestId == null) MDC.remove("requestId");
			else MDC.put("requestId", previousRequestId);
		}
	}

	// 方法：第一次產生失敗時推播安全狀態與耗時，後續自動重試不重複洗版。
	private void notifyFirstFailure(QuotationGenerationJobRepository.Job job, String errorCode) {
		if (line == null) return;

		long seconds = Math.max(0, Duration.between(job.createdAt(), clock.instant()).toSeconds());
		String elapsed = seconds >= 60
			? seconds / 60 + " 分 " + seconds % 60 + " 秒"
			: seconds + " 秒";
		String text = generationFailureMessage(errorCode)
			+ "\n目前執行時間：" + elapsed
			+ "\n錯誤代碼：" + errorCode;
		UUID retryKey = UUID.nameUUIDFromBytes(
			("quotation-generation-failed:" + job.quotationId()).getBytes(StandardCharsets.UTF_8)
		);
		try {
			line.push(job.destinationId(), java.util.List.of(LineStorageService.textMessage(text)), retryKey);
		}
		catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("event", "quotation_generation_failure_notice_failed")
				.addKeyValue("quotationId", job.quotationId())
				.addKeyValue("errorType", exception.getClass().getSimpleName())
				.log("event={} quotationId={} errorType={}",
					"quotation_generation_failure_notice_failed",
					job.quotationId(),
					exception.getClass().getSimpleName()
				);
		}
	}

	// 方法：依正式報價失敗階段說明已保留的產物與使用者應採取的行動。
	private String generationFailureMessage(String errorCode) {
		return switch (errorCode) {
			case "PDF_EXPORT_FAILED" -> "Excel 已建立，但 PDF 匯出失敗。"
				+ "請在已安裝 Microsoft Excel 的 Windows 主機由管理頁重試 PDF。";
			case "DELIVERY_FAILED", "DELIVERY_NOT_COMPLETED" -> "Excel 與 PDF 已建立，"
				+ "但 LINE 傳送失敗；系統將自動重試交付。";
			case "ARCHIVE_FAILED" -> "候選圖片歸檔失敗，Excel 尚未建立；"
				+ "系統將自動重試。";
			case "WORKBOOK_GENERATION_FAILED" -> "Excel 報價單建立失敗，"
				+ "系統將自動重試；若持續失敗請由管理頁使用「重試 Excel」。";
			default -> "報價產生失敗，系統將自動重試。";
		};
	}

	// 方法：依目前嘗試次數建立 30 秒起跳且最高 15 分鐘的退避時間。
	private Duration retryDelay(int attemptCount) {
		int exponent = Math.max(0, Math.min(attemptCount - 1, 5));
		Duration delay = Duration.ofSeconds(30L << exponent);
		return delay.compareTo(MAXIMUM_RETRY_DELAY) > 0 ? MAXIMUM_RETRY_DELAY : delay;
	}

	// 方法：只保存穩定錯誤代碼，不把例外訊息或外部回應寫入工作資料庫。
	private String errorCode(RuntimeException exception) {
		return exception instanceof QuotationGenerationException generationException
			? generationException.code()
			: exception instanceof QuotationAdminException adminException
				? adminException.code()
				: "GENERATION_FAILED";
	}

	// 方法：取得最深層例外，讓正式 JSON 日誌不依賴已關閉的堆疊輸出也能定位根因類型。
	Throwable rootCause(Throwable exception) {
		Throwable current = exception;
		for (int depth = 0; depth < 20; depth++) {
			Throwable cause = current.getCause();
			if (cause == null || cause == current) return current;

			current = cause;
		}
		return current;
	}

	// 方法：限制根因摘要長度並移除控制字元，避免外部例外破壞結構化日誌。
	String safeRootMessage(Throwable rootCause) {
		String message = rootCause.getMessage();
		if (message == null || message.isBlank()) return rootCause.getClass().getSimpleName();

		String safe = message.replaceAll("[\\r\\n\\t]+", " ").trim();
		safe = SENSITIVE_VALUE.matcher(safe).replaceAll("$1$2[redacted]");
		safe = URL_USER_INFO.matcher(safe).replaceAll("://[redacted]@");
		return safe.length() <= 300 ? safe : safe.substring(0, 300);
	}

	// 方法：租約遺失只記錄報價及工作識別，不覆蓋接手工作者的新狀態。
	private void logLeaseLost(QuotationGenerationJobRepository.Job job) {
		log.atWarn()
			.addKeyValue("event", "quotation_generation_lease_lost")
			.addKeyValue("quotationId", job.quotationId())
			.addKeyValue("jobId", job.id())
			.log(
				"event={} quotationId={} jobId={}",
				"quotation_generation_lease_lost",
				job.quotationId(),
				job.id()
			);
	}
}
