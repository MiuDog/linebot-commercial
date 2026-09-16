package dev.miudog.linebotcommercial.service.quotation;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotationGenerationCoordinatorTest {

	@Mock QuotationConfirmationService confirmations;
	@Mock QuotationAssetArchiveService assets;
	@Mock QuotationWorkbookService workbooks;
	@Mock QuotationGenerationRepository generations;
	@Mock QuotationPdfService pdfs;
	@Mock QuotationDeliveryService deliveries;
	@TempDir Path output;

	@Test
	void confirmsArchivesSelectedImageGeneratesWorkbookAndMarksXlsxReadyInOrder() {
		QuotationConfirmationCommand confirmationCommand = new QuotationConfirmationCommand(null, calculation());
		QuotationConfirmationResult confirmation = confirmation();
		QuotationArchivedAssets archived = new QuotationArchivedAssets(List.of(), output.resolve("selected.jpg"));
		QuotationWorkbookService.Header header = new QuotationWorkbookService.Header(
			"", "", "範例", "02-1234", "a@example.com", "王先生", "台北", "業務", ""
		);
		QuotationWorkbookService.Header formalHeader = new QuotationWorkbookService.Header(
			"2026081101", "2026-08-11", "範例", "02-1234", "a@example.com",
			"王先生", "台北", "業務", "2026-08-26"
		);
		QuotationWorkbookService.GenerationResult generated = new QuotationWorkbookService.GenerationResult(
			output.resolve("quote.xlsx"), "quote.xlsx", "GENERAL",
			new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("105")
		);
		when(confirmations.confirm(confirmationCommand)).thenReturn(confirmation);
		when(assets.archive(confirmation)).thenReturn(archived);
		when(workbooks.generateConfirmed(formalHeader, confirmation, calculation(), archived.selectedImagePath()))
			.thenReturn(generated);
		QuotationPdfService.PdfExportResult pdf = new QuotationPdfService.PdfExportResult(
			1, "2026081101", generated.path(), output.resolve("quote.pdf"), "hash", 100
		);
		QuotationDeliveryResult delivery = new QuotationDeliveryResult(
			1, 1, 1, QuotationDeliveryStatus.SENT, false, "provider", null
		);
		when(pdfs.export(1)).thenReturn(pdf);
		when(deliveries.deliverFinal(1, "U1")).thenReturn(delivery);

		QuotationGenerationResult result = new QuotationGenerationCoordinator(
			confirmations,
			assets,
			workbooks,
			generations,
			pdfs,
			deliveries
		).generate(new QuotationGenerationCommand(confirmationCommand, header, "U1"));

		assertThat(result.confirmation()).isEqualTo(confirmation);
		assertThat(result.workbook()).isEqualTo(generated);
		assertThat(result.pdf()).isEqualTo(pdf);
		assertThat(result.delivery()).isEqualTo(delivery);
		var ordered = inOrder(confirmations, assets, generations, workbooks, pdfs, deliveries);
		ordered.verify(confirmations).confirm(confirmationCommand);
		ordered.verify(assets).archive(confirmation);
		ordered.verify(generations).markGenerating(confirmation.quotationId());
		ordered.verify(workbooks).generateConfirmed(formalHeader, confirmation, calculation(), archived.selectedImagePath());
		ordered.verify(generations).markReady(confirmation.quotationId(), generated.path());
		ordered.verify(pdfs).export(confirmation.quotationId());
		ordered.verify(deliveries).deliverFinal(confirmation.quotationId(), "U1");
	}

	@Test
	void marksGenerationFailedWithoutCallingWorkbookWhenAssetArchiveFails() {
		QuotationConfirmationCommand command = new QuotationConfirmationCommand(null, calculation());
		QuotationConfirmationResult confirmation = confirmation();
		when(confirmations.confirm(command)).thenReturn(confirmation);
		when(assets.archive(confirmation)).thenThrow(new QuotationAssetArchiveException("ARCHIVE_FAILED", "失敗"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QuotationGenerationCoordinator(
			confirmations,
			assets,
			workbooks,
			generations
		).generate(new QuotationGenerationCommand(command, null)))
			.isInstanceOf(QuotationGenerationException.class);
		verify(generations).markFailed(confirmation.quotationId(), "ARCHIVE_FAILED");
		org.mockito.Mockito.verifyNoInteractions(workbooks);
	}

	@Test
	void deliveryFailurePreservesReadyXlsxAndPdfWithoutMarkingGenerationFailed() {
		QuotationConfirmationResult confirmation = confirmation();
		QuotationArchivedAssets archived = new QuotationArchivedAssets(List.of(), null);
		QuotationWorkbookService.GenerationResult workbook = new QuotationWorkbookService.GenerationResult(
			output.resolve("quote.xlsx"), "quote.xlsx", "GENERAL",
			new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("105")
		);
		QuotationPdfService.PdfExportResult pdf = new QuotationPdfService.PdfExportResult(
			1, "2026081101", workbook.path(), output.resolve("quote.pdf"), "hash", 100
		);
		when(assets.archive(confirmation)).thenReturn(archived);
		when(workbooks.generateConfirmed(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(confirmation),
			org.mockito.ArgumentMatchers.eq(calculation()),
			org.mockito.ArgumentMatchers.isNull()
		)).thenReturn(workbook);
		when(pdfs.export(1)).thenReturn(pdf);
		when(deliveries.deliverFinal(1, "U1")).thenThrow(new IllegalStateException("push failed"));
		QuotationGenerationCoordinator coordinator = new QuotationGenerationCoordinator(
			confirmations, assets, workbooks, generations, pdfs, deliveries
		);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.generateConfirmed(
			new QuotationConfirmedGenerationCommand(confirmation, calculation(), null, "U1")
		))
			.isInstanceOf(QuotationGenerationException.class)
			.hasMessageContaining("PDF 已完成");
		verify(generations).markReady(1, workbook.path());
		verify(generations, never()).markFailed(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void resumesAtPdfWithoutRegeneratingAnAlreadyReadyWorkbook() {
		QuotationConfirmationResult confirmation = confirmation();
		when(generations.stage(confirmation.quotationId())).thenReturn(
			new QuotationGenerationRepository.GenerationStage("PDF_FAILED", "READY", "FAILED")
		);
		when(pdfs.export(confirmation.quotationId())).thenReturn(new QuotationPdfService.PdfExportResult(
			confirmation.quotationId(),
			confirmation.quotationNumber(),
			output.resolve("quote.xlsx"),
			output.resolve("quote.pdf"),
			"hash",
			100
		));
		when(deliveries.deliverFinal(confirmation.quotationId(), "U1")).thenReturn(
			new QuotationDeliveryResult(
				confirmation.quotationId(),
				1,
				1,
				QuotationDeliveryStatus.SENT,
				false,
				"provider",
				null
			)
		);
		QuotationGenerationCoordinator coordinator = new QuotationGenerationCoordinator(
			confirmations, assets, workbooks, generations, pdfs, deliveries
		);

		coordinator.resumeConfirmed(
			new QuotationConfirmedGenerationCommand(confirmation, calculation(), null, "U1")
		);

		verify(pdfs).export(confirmation.quotationId());
		verify(deliveries).deliverFinal(confirmation.quotationId(), "U1");
		verifyNoInteractions(assets, workbooks);
		verify(generations, never()).markGenerating(confirmation.quotationId());
	}

	@Test
	void completedSentQuotationIsANoOpAndCannotBeDowngraded() {
		QuotationConfirmationResult confirmation = confirmation();
		when(generations.stage(confirmation.quotationId())).thenReturn(
			new QuotationGenerationRepository.GenerationStage("SENT", "READY", "READY")
		);
		QuotationGenerationCoordinator coordinator = new QuotationGenerationCoordinator(
			confirmations, assets, workbooks, generations, pdfs, deliveries
		);

		coordinator.resumeConfirmed(
			new QuotationConfirmedGenerationCommand(confirmation, calculation(), null, "U1")
		);

		verifyNoInteractions(assets, workbooks, pdfs, deliveries);
		verify(generations, never()).markFailed(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString()
		);
	}

	private QuotationConfirmationResult confirmation() {
		return new QuotationConfirmationResult(
			1,
			"2026081101",
			LocalDate.of(2026, 8, 11),
			LocalDate.of(2026, 8, 26),
			1,
			"20260811-01",
			"範例-工程 20260811-01"
		);
	}

	private QuotationCalculationResult calculation() {
		return new QuotationCalculationResult(
			"GENERAL", List.of(), List.of(), new BigDecimal("100"),
			new BigDecimal("5"), new BigDecimal("105"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}
}
