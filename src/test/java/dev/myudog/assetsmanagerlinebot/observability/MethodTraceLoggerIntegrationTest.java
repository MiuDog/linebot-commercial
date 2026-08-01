package dev.myudog.assetsmanagerlinebot.observability;

import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class MethodTraceLoggerIntegrationTest {

	@Autowired
	QuotationService quotationService;

	@Test
	void tracesPublicMethodsOnSpringManagedApplicationClasses(CapturedOutput output) {
		quotationService.isAiConfigured();

		assertThat(output)
			.contains("event=method_entered")
			.contains("event=method_completed")
			.contains("class=QuotationService")
			.contains("method=isAiConfigured")
			.contains("requestId=background-");
	}
}
