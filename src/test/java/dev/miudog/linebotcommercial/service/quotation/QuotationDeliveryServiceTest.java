package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.LineStorageService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class QuotationDeliveryServiceTest {

	@Test
	void deliversFormalSnapshotAsFlexWithOnlyAnHttpsPdfLink(CapturedOutput output) {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		QuotationDeliverySnapshot snapshot = snapshot();
		when(repository.findSnapshot(41)).thenReturn(snapshot);
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.ready(81, 1));
		when(downloads.issuePdfLink(41, Duration.ofHours(24)))
			.thenReturn(new QuotationDownloadLink(
				"https://quotation.example.test/quotation-downloads/safe-token",
				Instant.parse("2026-08-12T00:00:00Z")
			));
		when(line.push(eq("U-line-user"), any(), any(UUID.class)))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message-1"));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24)
		);

		QuotationDeliveryResult result = service.deliverFinal(41, "U-line-user");

		assertThat(result.status()).isEqualTo(QuotationDeliveryStatus.SENT);
		assertThat(result.attemptCount()).isEqualTo(1);
		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(line).push(eq("U-line-user"), messages.capture(), any(UUID.class));
		String payload = messages.getValue().toString();
		assertThat(payload)
			.contains("正定工程", "港區搭架", "2026081101", "105,000", "https://quotation.example.test")
			.doesNotContain("C:\\", "file:", "application/pdf", "pdfPath");
		verify(repository).markSent(81, "provider-message-1");
		assertThat(output.getAll())
			.doesNotContain("U-line-user", "safe-token", "正定工程", "港區搭架");
	}

	@Test
	void preservesNecessaryDecimalPlacesInTheFinalFlexAmounts() {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		QuotationDeliverySnapshot snapshot = new QuotationDeliverySnapshot(
			41,
			"正定工程",
			"港區搭架",
			"2026081101",
			"TWD",
			new BigDecimal("20.01"),
			new BigDecimal("1.00"),
			new BigDecimal("21.01")
		);
		when(repository.findSnapshot(41)).thenReturn(snapshot);
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.ready(81, 1));
		when(downloads.issuePdfLink(41, Duration.ofHours(24)))
			.thenReturn(new QuotationDownloadLink(
				"https://quotation.example.test/quotation-downloads/safe-token",
				Instant.parse("2026-08-12T00:00:00Z")
			));
		when(line.push(eq("U-line-user"), any(), any(UUID.class)))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message-1"));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24)
		);

		service.deliverFinal(41, "U-line-user");

		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(line).push(eq("U-line-user"), messages.capture(), any(UUID.class));
		assertThat(messages.getValue().toString())
			.contains("未稅：20.01", "稅額：1", "總價：21.01");
	}

	@Test
	void includesTotalExecutionTimeInTheFinalFlex() {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		QuotationDeliverySnapshot snapshot = new QuotationDeliverySnapshot(
			41,
			"正定工程",
			"港區搭架",
			"2026081101",
			"TWD",
			new BigDecimal("20"),
			new BigDecimal("1"),
			new BigDecimal("21"),
			Instant.parse("2026-08-11T01:00:00Z")
		);
		when(repository.findSnapshot(41)).thenReturn(snapshot);
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.ready(81, 1));
		when(downloads.issuePdfLink(41, Duration.ofHours(24)))
			.thenReturn(new QuotationDownloadLink(
				"https://quotation.example.test/quotation-downloads/safe-token",
				Instant.parse("2026-08-12T00:00:00Z")
			));
		when(line.push(eq("U-line-user"), any(), any(UUID.class)))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message-1"));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24),
			java.time.Clock.fixed(Instant.parse("2026-08-11T01:02:05Z"), java.time.ZoneOffset.UTC)
		);

		service.deliverFinal(41, "U-line-user");

		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(line).push(eq("U-line-user"), messages.capture(), any(UUID.class));
		assertThat(messages.getValue().toString()).contains("執行時間：2 分 5 秒");
	}

	@Test
	void savesFailureAndAllowsTheNextPersistedAttemptToRetry() {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		when(repository.findSnapshot(41)).thenReturn(snapshot());
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.ready(81, 1))
			.thenReturn(QuotationDeliveryClaim.ready(82, 2));
		when(downloads.issuePdfLink(eq(41L), any()))
			.thenReturn(new QuotationDownloadLink(
				"https://quotation.example.test/quotation-downloads/safe-token",
				Instant.parse("2026-08-12T00:00:00Z")
			));
		when(line.push(eq("U-line-user"), any(), any(UUID.class)))
			.thenThrow(new LineStorageService.LineMessagingException("LINE_HTTP_ERROR", "status=503"))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message-2"));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24)
		);

		QuotationDeliveryResult failed = service.deliverFinal(41, "U-line-user");
		QuotationDeliveryResult retried = service.deliverFinal(41, "U-line-user");

		assertThat(failed.status()).isEqualTo(QuotationDeliveryStatus.FAILED);
		assertThat(failed.errorSummary()).isEqualTo("LineMessagingException:LINE_HTTP_ERROR");
		assertThat(retried.status()).isEqualTo(QuotationDeliveryStatus.SENT);
		assertThat(retried.attemptCount()).isEqualTo(2);
		verify(repository).markFailed(81, "LineMessagingException:LINE_HTTP_ERROR");
		verify(repository).markSent(82, "provider-message-2");
		ArgumentCaptor<UUID> retryKeys = ArgumentCaptor.forClass(UUID.class);
		verify(line, org.mockito.Mockito.times(2)).push(
			eq("U-line-user"),
			any(),
			retryKeys.capture()
		);
		assertThat(retryKeys.getAllValues()).containsOnly(retryKeys.getValue());
	}

	@Test
	void returnsThePersistedSuccessWithoutIssuingAnotherTokenOrCallingLine() {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		when(repository.findSnapshot(41)).thenReturn(snapshot());
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.alreadySent(81, 2, "provider-message-1"));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24)
		);

		QuotationDeliveryResult result = service.deliverFinal(41, "U-line-user");

		assertThat(result.status()).isEqualTo(QuotationDeliveryStatus.SENT);
		assertThat(result.alreadyDelivered()).isTrue();
		verify(downloads, never()).issuePdfLink(any(Long.class), any());
		verify(line, never()).push(any(), any(), any(UUID.class));
	}

	@Test
	void rejectsAnInsecureDownloadLinkBeforeCallingLineAndKeepsTheAttemptRetryable() {
		QuotationDeliveryRepository repository = mock(QuotationDeliveryRepository.class);
		QuotationDownloadService downloads = mock(QuotationDownloadService.class);
		LineStorageService line = mock(LineStorageService.class);
		when(repository.findSnapshot(41)).thenReturn(snapshot());
		when(repository.claimFinal(41, "U-line-user"))
			.thenReturn(QuotationDeliveryClaim.ready(81, 1));
		when(downloads.issuePdfLink(eq(41L), any()))
			.thenReturn(new QuotationDownloadLink(
				"http://localhost/C:/quotation/output.pdf",
				Instant.parse("2026-08-12T00:00:00Z")
			));
		QuotationDeliveryService service = new QuotationDeliveryService(
			repository,
			downloads,
			line,
			Duration.ofHours(24)
		);

		QuotationDeliveryResult result = service.deliverFinal(41, "U-line-user");

		assertThat(result.status()).isEqualTo(QuotationDeliveryStatus.FAILED);
		assertThat(result.errorSummary()).isEqualTo("IllegalStateException:DELIVERY_FAILED");
		verify(repository).markFailed(81, "IllegalStateException:DELIVERY_FAILED");
		verify(line, never()).push(any(), any(), any(UUID.class));
	}

	private QuotationDeliverySnapshot snapshot() {
		return new QuotationDeliverySnapshot(
			41,
			"正定工程",
			"港區搭架",
			"2026081101",
			"TWD",
			new BigDecimal("100000"),
			new BigDecimal("5000"),
			new BigDecimal("105000")
		);
	}
}
