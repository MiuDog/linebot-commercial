package dev.miudog.linebotcommercial.service.quotation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotationGenerationJobWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");

	@Mock QuotationGenerationJobRepository jobs;
	@Mock QuotationGenerationSnapshotRepository snapshots;
	@Mock QuotationGenerationCoordinator coordinator;
	@Mock dev.miudog.linebotcommercial.service.LineStorageService line;

	@Test
	void leasesAndCompletesPersistedWorkAfterAProcessRestart() {
		QuotationGenerationJobRepository.Job job = job(1, "request-restart", 1);
		QuotationConfirmedGenerationCommand command = command();
		when(jobs.leaseNext("worker-test", NOW, Duration.ofMinutes(2)))
			.thenReturn(Optional.of(job), Optional.empty());
		when(snapshots.load(job.quotationId())).thenReturn(command);
		when(jobs.markDone(job.id(), "worker-test")).thenReturn(true);
		QuotationGenerationJobWorker worker = worker();

		worker.recoverOnStartup();

		verify(snapshots).load(job.quotationId());
		verify(coordinator).resumeConfirmed(command);
		verify(jobs).markDone(job.id(), "worker-test");
		verify(jobs, never()).markFailed(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void failedWorkIsPersistedWithBackoffInsteadOfBeingLostFromMemory() {
		QuotationGenerationJobRepository.Job job = job(2, "request-failure", 3);
		QuotationConfirmedGenerationCommand command = command();
		when(jobs.leaseNext("worker-test", NOW, Duration.ofMinutes(2)))
			.thenReturn(Optional.of(job), Optional.empty());
		when(snapshots.load(job.quotationId())).thenReturn(command);
		org.mockito.Mockito.doThrow(new QuotationGenerationException(
			"WORKBOOK_GENERATION_FAILED",
			"產生失敗",
			null
		)).when(coordinator).resumeConfirmed(command);
		when(jobs.markFailed(
			org.mockito.ArgumentMatchers.eq(job.id()),
			org.mockito.ArgumentMatchers.eq("worker-test"),
			org.mockito.ArgumentMatchers.eq("WORKBOOK_GENERATION_FAILED"),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(true);
		QuotationGenerationJobWorker worker = worker();

		int completed = worker.drainAvailable();

		assertThat(completed).isZero();
		ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
		verify(jobs).markFailed(
			org.mockito.ArgumentMatchers.eq(job.id()),
			org.mockito.ArgumentMatchers.eq("worker-test"),
			org.mockito.ArgumentMatchers.eq("WORKBOOK_GENERATION_FAILED"),
			nextAttempt.capture()
		);
		assertThat(nextAttempt.getValue()).isEqualTo(NOW.plusSeconds(120));
		verify(jobs, never()).markDone(job.id(), "worker-test");
	}

	@Test
	void reportsTheFirstGenerationFailureAndElapsedTimeToLine() {
		QuotationGenerationJobRepository.Job job = job(2, "request-failure-notice", 1);
		QuotationConfirmedGenerationCommand command = command();
		when(jobs.leaseNext("worker-test", NOW, Duration.ofMinutes(2)))
			.thenReturn(Optional.of(job), Optional.empty());
		when(snapshots.load(job.quotationId())).thenReturn(command);
		org.mockito.Mockito.doThrow(new QuotationGenerationException(
			"WORKBOOK_GENERATION_FAILED",
			"產生失敗",
			null
		)).when(coordinator).resumeConfirmed(command);
		when(jobs.markFailed(
			org.mockito.ArgumentMatchers.eq(job.id()),
			org.mockito.ArgumentMatchers.eq("worker-test"),
			org.mockito.ArgumentMatchers.eq("WORKBOOK_GENERATION_FAILED"),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(true);
		QuotationGenerationJobWorker worker = worker(line);

		worker.drainAvailable();

		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(line).push(
			org.mockito.ArgumentMatchers.eq("U1"),
			messages.capture(),
			org.mockito.ArgumentMatchers.any(java.util.UUID.class)
		);
		assertThat(messages.getValue().toString())
			.contains(
				"Excel 報價單建立失敗",
				"系統將自動重試",
				"執行時間：0 秒",
				"WORKBOOK_GENERATION_FAILED"
			);
	}

	@Test
	void reportsThatExcelWasPreservedWhenOnlyPdfExportFails() {
		QuotationGenerationJobRepository.Job job = job(3, "request-pdf-failure", 1);
		QuotationConfirmedGenerationCommand command = command();
		when(jobs.leaseNext("worker-test", NOW, Duration.ofMinutes(2)))
			.thenReturn(Optional.of(job), Optional.empty());
		when(snapshots.load(job.quotationId())).thenReturn(command);
		org.mockito.Mockito.doThrow(new QuotationGenerationException(
			"PDF_EXPORT_FAILED",
			"PDF 產生失敗",
			null
		)).when(coordinator).resumeConfirmed(command);
		when(jobs.markFailed(
			org.mockito.ArgumentMatchers.eq(job.id()),
			org.mockito.ArgumentMatchers.eq("worker-test"),
			org.mockito.ArgumentMatchers.eq("PDF_EXPORT_FAILED"),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(true);
		QuotationGenerationJobWorker worker = worker(line);

		worker.drainAvailable();

		ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
		verify(line).push(
			org.mockito.ArgumentMatchers.eq("U1"),
			messages.capture(),
			org.mockito.ArgumentMatchers.any(java.util.UUID.class)
		);
		assertThat(messages.getValue().toString())
			.contains(
				"Excel 已建立",
				"PDF 匯出失敗",
				"Windows 主機",
				"PDF_EXPORT_FAILED"
			);
	}

	@Test
	void rejectedWakeupDoesNotMutateThePersistedJobAndCanBeRetried() {
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		org.springframework.core.task.TaskExecutor rejected = task -> {
			attempts.incrementAndGet();
			throw new org.springframework.core.task.TaskRejectedException("full");

		};
		QuotationGenerationJobWorker worker = new QuotationGenerationJobWorker(
			jobs,
			snapshots,
			coordinator,
			rejected,
			Clock.fixed(NOW, ZoneOffset.UTC),
			"worker-test",
			Duration.ofMinutes(2),
			10
		);

		worker.wake();
		worker.wake();

		assertThat(attempts).hasValue(2);
		org.mockito.Mockito.verifyNoInteractions(jobs, snapshots, coordinator);
	}

	private QuotationGenerationJobWorker worker() {
		return worker(null);
	}

	private QuotationGenerationJobWorker worker(dev.miudog.linebotcommercial.service.LineStorageService line) {
		return new QuotationGenerationJobWorker(
			jobs,
			snapshots,
			coordinator,
			line,
			Runnable::run,
			Clock.fixed(NOW, ZoneOffset.UTC),
			"worker-test",
			Duration.ofMinutes(2),
			10
		);
	}

	private QuotationGenerationJobRepository.Job job(long id, String correlationId, int attemptCount) {
		return new QuotationGenerationJobRepository.Job(
			id,
			77,
			QuotationGenerationJobRepository.Status.RUNNING,
			"U1",
			correlationId,
			"worker-test",
			NOW.plus(Duration.ofMinutes(2)),
			attemptCount,
			NOW,
			null,
			NOW,
			NOW,
			null
		);
	}

	private QuotationConfirmedGenerationCommand command() {
		QuotationConfirmationResult confirmation = new QuotationConfirmationResult(
			77,
			"2026081101",
			LocalDate.of(2026, 8, 11),
			LocalDate.of(2026, 8, 26),
			1,
			"20260811-01",
			"正定-工程 20260811-01"
		);
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			"GENERAL",
			java.util.List.of(),
			java.util.List.of(),
			java.math.BigDecimal.ZERO,
			java.math.BigDecimal.ZERO,
			java.math.BigDecimal.ZERO,
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
		return new QuotationConfirmedGenerationCommand(confirmation, calculation, null, "U1");
	}
}
