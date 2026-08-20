package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.LineStorageService;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 將正式報價快照轉成 LINE Flex 摘要，並以可稽核的資料庫嘗試紀錄完成交付。 */
@Service
public class QuotationDeliveryService {

	private static final int MAX_ERROR_SUMMARY_LENGTH = 160;
	private static final Duration MAXIMUM_LINK_TIME_TO_LIVE = Duration.ofDays(30);
	private static final Logger log = LoggerFactory.getLogger(QuotationDeliveryService.class);

	private final QuotationDeliveryRepository repository;
	private final QuotationDownloadService downloads;
	private final LineStorageService line;
	private final Duration linkTimeToLive;
	private final Clock clock;

	// 方法：注入正式快照、短效下載連結與 LINE 推播邊界。
	@Autowired
	public QuotationDeliveryService(
		QuotationDeliveryRepository repository,
		QuotationDownloadService downloads,
		LineStorageService line,
		@Value("${app.quotation.delivery-link-ttl-hours:168}") long linkTimeToLiveHours
	) {
		this(repository, downloads, line, Duration.ofHours(linkTimeToLiveHours), Clock.systemUTC());
	}

	// 方法：提供測試使用的明確有效期限建構入口。
	QuotationDeliveryService(
		QuotationDeliveryRepository repository,
		QuotationDownloadService downloads,
		LineStorageService line,
		Duration linkTimeToLive
	) {
		this(repository, downloads, line, linkTimeToLive, Clock.systemUTC());
	}

	// 方法：提供測試使用的明確有效期限與時鐘建構入口。
	QuotationDeliveryService(
		QuotationDeliveryRepository repository,
		QuotationDownloadService downloads,
		LineStorageService line,
		Duration linkTimeToLive,
		Clock clock
	) {
		if (linkTimeToLive == null
			|| linkTimeToLive.isZero()
			|| linkTimeToLive.isNegative()
			|| linkTimeToLive.compareTo(MAXIMUM_LINK_TIME_TO_LIVE) > 0) {
			throw new IllegalArgumentException("LINE 報價下載連結有效期限必須大於零");
		}

		this.repository = repository;
		this.downloads = downloads;
		this.line = line;
		this.linkTimeToLive = linkTimeToLive;
		this.clock = clock;
	}

	// 方法：送出最終 Flex 報價；重複成功要求直接回傳既有結果，失敗則保留重試資格。
	public QuotationDeliveryResult deliverFinal(long quotationId, String destinationId) {
		requireDestination(destinationId);
		QuotationDeliverySnapshot snapshot = repository.findSnapshot(quotationId);
		QuotationDeliveryClaim claim = repository.claimFinal(quotationId, destinationId);
		if (claim.state() == QuotationDeliveryClaim.State.ALREADY_SENT) {
			logDelivery("quotation_delivery_idempotent", snapshot, claim, null);
			return sentResult(snapshot, claim, true, claim.providerMessageId());
		}

		if (claim.state() == QuotationDeliveryClaim.State.IN_PROGRESS) {
			logDelivery("quotation_delivery_in_progress", snapshot, claim, null);
			return inProgressResult(snapshot, claim);
		}

		try {
			logDelivery("quotation_delivery_started", snapshot, claim, null);
			QuotationDownloadLink link = downloads.issuePdfLink(quotationId, linkTimeToLive);
			requireHttps(link.url());
			LineStorageService.LinePushReceipt receipt = line.push(
				destinationId,
				List.of(finalFlex(snapshot, link.url())),
				retryKey(quotationId, destinationId)
			);
			repository.markSent(claim.attemptId(), receipt.providerMessageId());
			logDelivery("quotation_delivery_sent", snapshot, claim, null);
			return sentResult(snapshot, claim, false, receipt.providerMessageId());
		}
		catch (RuntimeException exception) {
			String summary = safeErrorSummary(exception);
			repository.markFailed(claim.attemptId(), summary);
			logDelivery("quotation_delivery_failed", snapshot, claim, summary);
			return new QuotationDeliveryResult(
				snapshot.quotationId(),
				claim.attemptId(),
				claim.attemptCount(),
				QuotationDeliveryStatus.FAILED,
				false,
				null,
				summary
			);
		}
	}

	// 方法：由正式報價與既有目的地建立跨程序重試皆相同且不揭露目的地的 UUID。
	private UUID retryKey(long quotationId, String destinationId) {
		String material = "quotation-final:" + quotationId + ":" + destinationId;
		return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
	}

	// 方法：建立只含正式快照摘要與 HTTPS 下載動作的單一 Flex Message。
	private Map<String, Object> finalFlex(QuotationDeliverySnapshot snapshot, String downloadUrl) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "box");
		body.put("layout", "vertical");
		body.put("spacing", "md");
		List<Map<String, Object>> contents = new java.util.ArrayList<>();
		contents.add(text("報價單已完成", "xl", "bold"));
		contents.add(text("公司：" + snapshot.companyName(), "sm", "regular"));
		contents.add(text("工作：" + snapshot.workName(), "sm", "regular"));
		contents.add(text("單號：" + snapshot.quotationNumber(), "sm", "regular"));
		contents.add(text("未稅：" + money(snapshot.subtotal()), "sm", "regular"));
		contents.add(text("稅額：" + money(snapshot.taxAmount()), "sm", "regular"));
		contents.add(text("總價：" + money(snapshot.totalAmount()), "lg", "bold"));
		if (snapshot.createdAt() != null) {
			contents.add(text("執行時間：" + executionTime(snapshot.createdAt()), "sm", "regular"));
		}
		body.put("contents", contents);

		Map<String, Object> action = new LinkedHashMap<>();
		action.put("type", "uri");
		action.put("label", "下載 PDF");
		action.put("uri", downloadUrl);
		Map<String, Object> button = new LinkedHashMap<>();
		button.put("type", "button");
		button.put("style", "primary");
		button.put("action", action);
		Map<String, Object> footer = new LinkedHashMap<>();
		footer.put("type", "box");
		footer.put("layout", "vertical");
		footer.put("contents", List.of(button));

		Map<String, Object> bubble = new LinkedHashMap<>();
		bubble.put("type", "bubble");
		bubble.put("body", body);
		bubble.put("footer", footer);
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "flex");
		message.put("altText", "報價單 " + snapshot.quotationNumber() + " 已完成");
		message.put("contents", bubble);
		return message;
	}

	// 方法：以分鐘與秒顯示從正式確認至交付完成的端到端時間。
	private String executionTime(java.time.Instant startedAt) {
		long seconds = Math.max(0, Duration.between(startedAt, clock.instant()).toSeconds());
		long minutes = seconds / 60;
		long remainingSeconds = seconds % 60;
		return minutes > 0 ? minutes + " 分 " + remainingSeconds + " 秒" : remainingSeconds + " 秒";
	}

	// 方法：建立 LINE Flex 使用的文字元件。
	private Map<String, Object> text(String value, String size, String weight) {
		Map<String, Object> component = new LinkedHashMap<>();
		component.put("type", "text");
		component.put("text", value);
		component.put("size", size);
		component.put("weight", weight);
		component.put("wrap", true);
		return component;
	}

	// 方法：建立成功結果並保留冪等命中資訊。
	private QuotationDeliveryResult sentResult(
		QuotationDeliverySnapshot snapshot,
		QuotationDeliveryClaim claim,
		boolean alreadyDelivered,
		String providerMessageId
	) {
		return new QuotationDeliveryResult(
			snapshot.quotationId(),
			claim.attemptId(),
			claim.attemptCount(),
			QuotationDeliveryStatus.SENT,
			alreadyDelivered,
			providerMessageId,
			null
		);
	}

	// 方法：建立目前已有其他工作者發送中的非重複交付結果。
	private QuotationDeliveryResult inProgressResult(
		QuotationDeliverySnapshot snapshot,
		QuotationDeliveryClaim claim
	) {
		return new QuotationDeliveryResult(
			snapshot.quotationId(),
			claim.attemptId(),
			claim.attemptCount(),
			QuotationDeliveryStatus.IN_PROGRESS,
			false,
			null,
			null
		);
	}

	// 方法：記錄不含 LINE 使用者、下載權杖或訊息本文的交付生命週期事件。
	private void logDelivery(
		String event,
		QuotationDeliverySnapshot snapshot,
		QuotationDeliveryClaim claim,
		String errorSummary
	) {
		log.atInfo()
			.addKeyValue("event", event)
			.addKeyValue("quotationId", snapshot.quotationId())
			.addKeyValue("attemptId", claim.attemptId())
			.addKeyValue("attemptCount", claim.attemptCount())
			.addKeyValue("deliveryKind", "FINAL")
			.addKeyValue("errorSummary", errorSummary)
			.log(
				"event={} quotationId={} attemptId={} attemptCount={} deliveryKind={} errorSummary={}",
				event,
				snapshot.quotationId(),
				claim.attemptId(),
				claim.attemptCount(),
				"FINAL",
				errorSummary
			);
	}

	// 方法：將正式金額快照加入千分位，且保留程式計算的必要小數。
	private String money(BigDecimal value) {
		if (value == null) throw new IllegalStateException("正式報價金額快照不完整");

		String plain = value.stripTrailingZeros().toPlainString();
		boolean negative = plain.startsWith("-");
		String unsigned = negative ? plain.substring(1) : plain;
		String[] parts = unsigned.split("\\.", -1);
		String integer = parts[0];
		StringBuilder grouped = new StringBuilder();
		for (int index = 0; index < integer.length(); index++) {
			if (index > 0 && (integer.length() - index) % 3 == 0) grouped.append(',');

			grouped.append(integer.charAt(index));
		}
		if (parts.length > 1 && !parts[1].isEmpty()) grouped.append('.').append(parts[1]);

		return (negative ? "-" : "") + grouped;
	}

	// 方法：拒絕非 HTTPS、含帳密或缺少主機的下載網址。
	private void requireHttps(String value) {
		URI uri;
		try {
			uri = URI.create(value);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("PDF 下載連結無效");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
			throw new IllegalStateException("PDF 下載連結必須使用公開 HTTPS 網址");
		}
	}

	// 方法：驗證 LINE 目的地識別碼存在且不含控制字元。
	private void requireDestination(String destinationId) {
		if (destinationId == null
			|| destinationId.isBlank()
			|| destinationId.length() > 128
			|| destinationId.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("LINE 使用者目的地不可留空");
		}
	}

	// 方法：只保存例外類型與穩定錯誤碼，避免外部回應、權杖或個資進入資料庫。
	private String safeErrorSummary(RuntimeException exception) {
		String code = exception instanceof LineStorageService.LineMessagingException lineException
			? lineException.code()
			: "DELIVERY_FAILED";
		String summary = exception.getClass().getSimpleName() + ":" + code;
		return summary.length() <= MAX_ERROR_SUMMARY_LENGTH
			? summary
			: summary.substring(0, MAX_ERROR_SUMMARY_LENGTH);
	}
}
