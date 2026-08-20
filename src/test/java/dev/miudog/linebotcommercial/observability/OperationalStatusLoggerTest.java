package dev.miudog.linebotcommercial.observability;

import dev.miudog.linebotcommercial.service.ai.AiExtractionService;
import dev.miudog.linebotcommercial.service.quotation.QuotationOutputDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OperationalStatusLoggerTest {

	@Test
	void logsConfigurationReadinessWithoutExposingConfiguredValues(CapturedOutput output) {
		AiExtractionService aiExtractionService = mock(AiExtractionService.class);
		QuotationOutputDirectoryService quotationDirectoryService = mock(QuotationOutputDirectoryService.class);
		when(aiExtractionService.isConfigured()).thenReturn(true);
		when(quotationDirectoryService.isConfigured()).thenReturn(true);

		OperationalStatusLogger statusLogger =
		new OperationalStatusLogger(aiExtractionService, quotationDirectoryService, "https://private.example.com");

		statusLogger.logApplicationReady();

		assertThat(output)
			.contains("event=application_ready")
			.contains("requestId=startup")
			.contains("aiConfigured=true")
			.contains("quotationRootConfigured=true")
			.contains("publicBaseUrlConfigured=true")
			.doesNotContain("private.example.com");
	}
}
