package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandServiceArchiveTest {

	@Mock
	AssetService assetService;

	@Mock
	LineStorageService lineService;

	@Mock
	QuotationService quotationService;

	@Mock
	ImageArchiveService archiveService;

	CommandService commandService;

	@BeforeEach
	void setUp() {
		commandService = new CommandService(assetService, lineService, quotationService, archiveService);
	}

	@Test
	void archivesTheQuotedImageSetForEveryAllowedFolderFormat() throws Exception {
		when(archiveService.archive("M1", "C1", "ZD12345"))
			.thenReturn(new ImageArchiveService.ArchiveResult(ImageArchiveService.ArchiveStatus.ARCHIVED, "ZD12345", 3, 3, 0, "01", "03"));
		when(archiveService.archive("M2", "C1", "ZD12345A"))
			.thenReturn(new ImageArchiveService.ArchiveResult(ImageArchiveService.ArchiveStatus.ARCHIVED, "ZD12345A", 2, 2, 0, "08", "09"));
		when(archiveService.archive("M3", "C1", "ZD-JY12345"))
			.thenReturn(new ImageArchiveService.ArchiveResult(ImageArchiveService.ArchiveStatus.ARCHIVED, "ZD-JY12345", 1, 1, 0, "100", "100"));
		when(archiveService.archive("M4", "C1", "YJ123456"))
			.thenReturn(new ImageArchiveService.ArchiveResult(ImageArchiveService.ArchiveStatus.ARCHIVED, "YJ123456", 4, 4, 0, "21", "24"));

		commandService.handleText("ZD12345", "M1", "C1", "U1", "R1");
		commandService.handleText("ZD12345A", "M2", "C1", "U1", "R2");
		commandService.handleText("ZD-JY12345", "M3", "C1", "U1", "R3");
		commandService.handleText("YJ123456", "M4", "C1", "U1", "R4");

		verify(lineService).replyText("R1", "已將3張圖片存入「ZD12345」，流水號01至03");
		verify(lineService).replyText("R2", "已將2張圖片存入「ZD12345A」，流水號08至09");
		verify(lineService).replyText("R3", "已將1張圖片存入「ZD-JY12345」，流水號100至100");
		verify(lineService).replyText("R4", "已將4張圖片存入「YJ123456」，流水號21至24");
	}

	@Test
	void ignoresLowercaseArchiveCodes() {
		commandService.handleText("zd12345", "M1", "C1", "U1", "R1");
		commandService.handleText("Zd12345A", "M2", "C1", "U1", "R2");

		verifyNoInteractions(archiveService, lineService);
	}

	@Test
	void reportsSyntaxErrorsForRecognizedPrefixesWithInvalidNumbers() {
		commandService.handleText("ZD1234", "M1", "C1", "U1", "R1");
		commandService.handleText("ZD-JY123456", "M2", "C1", "U1", "R2");
		commandService.handleText("YJ12345", "M3", "C1", "U1", "R3");
		commandService.handleText("ZD20260730", "M4", "C1", "U1", "R4");

		verify(lineService).replyText("R1", "檢測到語法錯誤，請修正後重新執行指令");
		verify(lineService).replyText("R2", "檢測到語法錯誤，請修正後重新執行指令");
		verify(lineService).replyText("R3", "檢測到語法錯誤，請修正後重新執行指令");
		verify(lineService).replyText("R4", "檢測到語法錯誤，請修正後重新執行指令");
		verifyNoInteractions(archiveService);
	}

	@Test
	void reportsDuplicateAndFetchedCountsWhenTheImageSetIsIncomplete() throws Exception {
		when(archiveService.archive("M1", "C1", "ZD12345"))
			.thenReturn(new ImageArchiveService.ArchiveResult(ImageArchiveService.ArchiveStatus.INCOMPLETE_SET, "ZD12345", 1, 3, 1, "", ""));

		commandService.handleText("ZD12345", "M1", "C1", "U1", "reply-token");

		verify(lineService).replyText(
			"reply-token",
			"圖片抓取未完成：重複1張，最終抓取1張（預期3張）"
		);
	}

	@Test
	void repliesWhenAValidCommandDoesNotQuoteAnImage() {
		commandService.handleText(
			"ZD12345",
			null,
			"C1",
			"U1",
			"reply-token"
		);

		verify(lineService).replyText(
			"reply-token",
			"尚未回覆圖片，請回覆要歸檔的圖片組後重新執行指令"
		);
		verifyNoInteractions(archiveService);
	}

	@Test
	void repliesForEveryNonSuccessfulArchiveResult() throws Exception {
		when(archiveService.archive("missing", "C1", "ZD12345"))
			.thenReturn(
				new ImageArchiveService.ArchiveResult(
					ImageArchiveService.ArchiveStatus.NOT_FOUND,
					"ZD12345",
					0,
					0,
					0,
					"",
					""
				)
			);
		when(archiveService.archive("wrong-source", "C1", "ZD12345"))
			.thenReturn(
				new ImageArchiveService.ArchiveResult(
					ImageArchiveService.ArchiveStatus.WRONG_SOURCE,
					"ZD12345",
					0,
					0,
					0,
					"",
					""
				)
			);

		commandService.handleText(
			"ZD12345",
			"missing",
			"C1",
			"U1",
			"R1"
		);
		commandService.handleText(
			"ZD12345",
			"wrong-source",
			"C1",
			"U1",
			"R3"
		);

		verify(lineService).replyText(
			"R1",
			"找不到被回覆圖片的待處理紀錄，請重新上傳後再試"
		);
		verify(lineService).replyText(
			"R3",
			"被回覆圖片不屬於目前群組，無法歸檔"
		);
	}

	@Test
	void repliesWhenArchiveWritingFails() throws Exception {
		doThrow(new IOException("disk failure"))
			.when(archiveService)
			.archive("M1", "C1", "ZD12345");

		commandService.handleText(
			"ZD12345",
			"M1",
			"C1",
			"U1",
			"reply-token"
		);

		verify(lineService).replyText(
			"reply-token",
			"圖片歸檔失敗，請稍後重新執行指令"
		);
	}
}
