package dev.miudog.linebotcommercial.observability;

import dev.miudog.linebotcommercial.service.ai.AiExtractionService;
import dev.miudog.linebotcommercial.service.quotation.QuotationOutputDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 【啟動事件尾端】在 Spring 完成所有 Bean 建立後，輸出一筆安全且精簡的就緒事件。
 *
 * <p><b>事件呼叫鏈：</b>
 * {@code SpringApplication.run → ApplicationReadyEvent
 * → logApplicationReady → AiExtractionService.isConfigured
 * + QuotationOutputDirectoryService.isConfigured → application_ready 日誌}。
 *
 * <p>只記錄「有沒有設定」的布林值，不記錄 URL、路徑、Token 或 API Key。
 */
@Component
public class OperationalStatusLogger {

	private static final Logger log = LoggerFactory.getLogger(OperationalStatusLogger.class);

	private final AiExtractionService aiExtractionService;
	private final QuotationOutputDirectoryService quotationDirectoryService;
	private final String publicBaseUrl;

	// 方法：初始化 OperationalStatusLogger。
	public OperationalStatusLogger(
		AiExtractionService aiExtractionService,
		QuotationOutputDirectoryService quotationDirectoryService,
		@Value("${app.public-base-url:}") String publicBaseUrl
	) {
		this.aiExtractionService = aiExtractionService;
		this.quotationDirectoryService = quotationDirectoryService;
		this.publicBaseUrl = publicBaseUrl;
	}

	// 方法：執行 logApplicationReady 方法的處理流程。
	@EventListener(ApplicationReadyEvent.class)
	public void logApplicationReady() {
		// 日誌：記錄應用程式啟動完成與設定狀態。
		log.info(
			"event=application_ready requestId=startup aiConfigured={} "
			+ "quotationRootConfigured={} publicBaseUrlConfigured={}",
			aiExtractionService.isConfigured(),
			quotationDirectoryService.isConfigured(),
			publicBaseUrl != null && !publicBaseUrl.isBlank()
		);
	}
}
