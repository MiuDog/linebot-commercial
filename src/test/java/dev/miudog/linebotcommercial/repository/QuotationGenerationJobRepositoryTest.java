package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationGenerationJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-generation-job-test",
		"app.quotation.generation-worker-enabled=false",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationGenerationJobRepositoryTest {

	@Autowired
	QuotationGenerationJobRepository repository;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void enqueuesOnlyOneDurableJobForRepeatedConfirmation() {
		long quotationId = insertQuotation("CONFIRMED");

		boolean first = repository.enqueueIfAbsent(quotationId, "request-confirm-1");
		boolean duplicate = repository.enqueueIfAbsent(quotationId, "request-confirm-2");

		assertThat(first).isTrue();
		assertThat(duplicate).isFalse();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_generation_job WHERE quotation_id = ?",
			Integer.class,
			quotationId
		)).isEqualTo(1);
		QuotationGenerationJobRepository.Job job = repository.findByQuotationId(quotationId).orElseThrow();
		assertThat(job.status()).isEqualTo(QuotationGenerationJobRepository.Status.PENDING);
		assertThat(job.destinationId()).isEqualTo("U-job-owner");
		assertThat(job.correlationId()).isEqualTo("request-confirm-1");
	}

	@Test
	void leasesDueWorkAndReclaimsAnExpiredWorkerLease() {
		long quotationId = insertQuotation("CONFIRMED");
		repository.enqueueIfAbsent(quotationId, "request-lease");
		Instant startedAt = Instant.now().plusSeconds(5);

		QuotationGenerationJobRepository.Job first = repository.leaseNext(
			"worker-a",
			startedAt,
			Duration.ofMinutes(2)
		).orElseThrow();

		assertThat(first.status()).isEqualTo(QuotationGenerationJobRepository.Status.RUNNING);
		assertThat(first.attemptCount()).isEqualTo(1);
		assertThat(repository.leaseNext(
			"worker-b",
			startedAt.plusSeconds(60),
			Duration.ofMinutes(2)
		)).isEmpty();

		QuotationGenerationJobRepository.Job reclaimed = repository.leaseNext(
			"worker-b",
			startedAt.plusSeconds(121),
			Duration.ofMinutes(2)
		).orElseThrow();
		assertThat(reclaimed.id()).isEqualTo(first.id());
		assertThat(reclaimed.attemptCount()).isEqualTo(2);
		assertThat(reclaimed.leaseOwner()).isEqualTo("worker-b");
	}

	@Test
	void failedWorkWaitsUntilNextAttemptAndOnlyLeaseOwnerCanCompleteIt() {
		long quotationId = insertQuotation("CONFIRMED");
		repository.enqueueIfAbsent(quotationId, "request-retry");
		Instant startedAt = Instant.now().plusSeconds(5);
		QuotationGenerationJobRepository.Job leased = repository.leaseNext(
			"worker-a",
			startedAt,
			Duration.ofMinutes(2)
		).orElseThrow();

		assertThat(repository.markFailed(
			leased.id(),
			"worker-a",
			"WORKBOOK_GENERATION_FAILED",
			startedAt.plusSeconds(30)
		)).isTrue();
		assertThat(repository.leaseNext(
			"worker-b",
			startedAt.plusSeconds(29),
			Duration.ofMinutes(2)
		)).isEmpty();

		QuotationGenerationJobRepository.Job retried = repository.leaseNext(
			"worker-b",
			startedAt.plusSeconds(30),
			Duration.ofMinutes(2)
		).orElseThrow();
		assertThat(repository.markDone(retried.id(), "worker-a")).isFalse();
		assertThat(repository.markDone(retried.id(), "worker-b")).isTrue();
		assertThat(repository.findByQuotationId(quotationId).orElseThrow().status())
			.isEqualTo(QuotationGenerationJobRepository.Status.DONE);
	}

	@Test
	void xlsxRetryKeepsQuotationIdentityAndRequeuesTheExistingJob() {
		long quotationId = insertQuotation("FAILED");
		repository.enqueueIfAbsent(quotationId, "request-original");
		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, content_type, status, error_message
			)
			VALUES (?, 'XLSX',
				'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
				'FAILED', 'WORKBOOK_GENERATION_FAILED')
			""", quotationId);
		QuotationGenerationJobRepository.Job leased = repository.leaseNext(
			"worker-a",
			Instant.now().plusSeconds(5),
			Duration.ofMinutes(2)
		).orElseThrow();
		repository.markFailed(
			leased.id(),
			"worker-a",
			"WORKBOOK_GENERATION_FAILED",
			Instant.parse("2099-01-01T00:00:00Z")
		);

		boolean retried = repository.retryFailedXlsx(quotationId, "admin-retry-1");

		assertThat(retried).isTrue();
		assertThat(jdbc.queryForObject(
			"SELECT quotation_no FROM quotation WHERE id = ?",
			String.class,
			quotationId
		)).isEqualTo("2026081101");
		assertThat(jdbc.queryForObject(
			"SELECT sequence_number FROM quotation WHERE id = ?",
			Integer.class,
			quotationId
		)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation WHERE id = ?",
			String.class,
			quotationId
		)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation_file WHERE quotation_id = ? AND file_kind = 'XLSX'",
			String.class,
			quotationId
		)).isEqualTo("PENDING");
		QuotationGenerationJobRepository.Job job = repository.findByQuotationId(quotationId).orElseThrow();
		assertThat(job.status()).isEqualTo(QuotationGenerationJobRepository.Status.PENDING);
		assertThat(job.attemptCount()).isEqualTo(0);
		assertThat(job.correlationId()).isEqualTo("admin-retry-1");
	}

	private long insertQuotation(String status) {
		String suffix = UUID.randomUUID().toString();
		jdbc.update("""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name,
				work_name, quotation_name, scheme_id, status, revision
			)
			SELECT ?, 'user', 'U-job-owner', 'U-job-owner', '範例公司',
				'測試工程', '範例公司-測試工程', id, 'CONFIRMED', 1
			FROM quotation_scheme
			WHERE code = 'GENERAL'
			""", "draft-job-" + suffix);
		Long draftId = jdbc.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			"draft-job-" + suffix
		);
		jdbc.update("""
			INSERT INTO quotation (
				draft_id, revision, quotation_no, quotation_name, sequence_date,
				sequence_number, company_name, work_name, quotation_date, valid_until,
				scheme_id, template_id, subtotal, tax_amount, total_amount, status
			)
			SELECT ?, 1, '2026081101', '範例公司-測試工程 20260811-01', '2026-08-11',
				1, '範例公司', '測試工程', '2026-08-11', '2026-08-26',
				s.id, t.id, 100, 5, 105, ?
			FROM quotation_scheme s
			JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE s.code = 'GENERAL'
			""", draftId, status);
		return jdbc.queryForObject(
			"SELECT id FROM quotation WHERE draft_id = ?",
			Long.class,
			draftId
		);
	}
}
