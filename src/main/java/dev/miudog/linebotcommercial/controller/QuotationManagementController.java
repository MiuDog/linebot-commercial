package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationManagementService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 提供僅限本機使用的正式報價查詢、下載與重試 API。
 */
@RestController
@RequestMapping("/api/admin")
public class QuotationManagementController {

	private final QuotationManagementService service;

	// 方法：建立正式報價管理控制器。
	public QuotationManagementController(QuotationManagementService service) {
		this.service = service;
	}

	// 方法：依單號、公司、工作、日期及狀態列出正式報價。
	@GetMapping("/quotations")
	public QuotationManagementService.QuotationListResponse list(
		@RequestParam(required = false) String quotationNumber,
		@RequestParam(required = false) String company,
		@RequestParam(required = false) String work,
		@RequestParam(required = false) String dateFrom,
		@RequestParam(required = false) String dateTo,
		@RequestParam(required = false) String status,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(
			new QuotationManagementService.QuotationListQuery(
				quotationNumber,
				company,
				work,
				dateFrom,
				dateTo,
				status,
				page,
				pageSize
			)
		);
	}

	// 方法：取得一筆正式報價的客戶可見快照及可用管理操作。
	@GetMapping("/quotations/{quotationId}")
	public QuotationManagementService.QuotationDetail detail(@PathVariable long quotationId) {
		return service.detail(quotationId);
	}

	// 方法：列出可搜尋並依狀態篩選的報價草稿。
	@GetMapping("/quotation-drafts")
	public QuotationManagementService.DraftListResponse listDrafts(
		@RequestParam(required = false) String search,
		@RequestParam(required = false) String status,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.listDrafts(new QuotationManagementService.DraftListQuery(search, status, page, pageSize));
	}

	// 方法：回傳完整草稿預覽、缺漏及程式計算金額。
	@GetMapping("/quotation-drafts/{draftId}")
	public QuotationManagementService.DraftDetail draftDetail(@PathVariable long draftId) {
		return service.draftDetail(draftId);
	}

	// 方法：下載資料庫綁定且已完成的 XLSX 或 PDF，不接受任意路徑。
	@GetMapping("/quotations/{quotationId}/files/{fileKind}")
	public ResponseEntity<Resource> download(
		@PathVariable long quotationId,
		@PathVariable String fileKind
	) {
		QuotationManagementService.AdminFileResource file = service.resolveFile(quotationId, fileKind);
		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(file.fileName(), StandardCharsets.UTF_8)
			.build();

		// 外部 API：使用 Spring HTTP API 回傳禁止快取的本機管理附件。
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.contentType()))
			.contentLength(file.fileSize())
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.body(new ByteArrayResource(file.content()));
	}

	// 方法：串流本機管理頁所屬草稿的選定暫存圖片。
	@GetMapping("/quotation-drafts/{draftId}/selected-image")
	public ResponseEntity<Resource> draftSelectedImage(@PathVariable long draftId) {
		return imageResponse(service.resolveDraftImage(draftId));
	}

	// 方法：串流本機管理頁所屬正式報價的選定資產圖片。
	@GetMapping("/quotations/{quotationId}/selected-image")
	public ResponseEntity<Resource> quotationSelectedImage(@PathVariable long quotationId) {
		return imageResponse(service.resolveQuotationImage(quotationId));
	}

	// 方法：以原報價識別碼、流水號與路徑重試失敗的 PDF。
	@PostMapping("/quotations/{quotationId}/pdf-retries")
	public QuotationManagementService.PdfRetryResponse retryPdf(@PathVariable long quotationId) {
		return service.retryPdf(quotationId);
	}

	// 方法：以原報價識別碼、流水號及正式快照重新排程失敗的 Excel。
	@PostMapping("/quotations/{quotationId}/xlsx-retries")
	public QuotationManagementService.XlsxRetryResponse retryXlsx(@PathVariable long quotationId) {
		return service.retryXlsx(quotationId);
	}

	// 方法：沿用最新失敗交付紀錄中的既有 LINE 目的地重送正式報價。
	@PostMapping("/quotations/{quotationId}/line-retries")
	public QuotationManagementService.LineRetryResponse retryLine(@PathVariable long quotationId) {
		return service.retryLine(quotationId);
	}

	// 方法：撤銷指定正式報價的全部 PDF 下載連結。
	@PostMapping("/quotations/{quotationId}/download-links/revocation")
	public QuotationManagementService.RevocationResponse revokePdfLinks(@PathVariable long quotationId) {
		return service.revokePdfLinks(quotationId);
	}

	// 方法：重新建立 PDF 限時連結，回應不包含原始權杖或 URL。
	@PostMapping("/quotations/{quotationId}/download-links")
	public QuotationManagementService.LinkRegenerationResponse regeneratePdfLink(
		@PathVariable long quotationId
	) {
		return service.regeneratePdfLink(quotationId);
	}

	// 方法：以禁止快取及 inline disposition 回傳不含本機路徑的圖片資源。
	private ResponseEntity<Resource> imageResponse(QuotationManagementService.AdminImageResource image) {
		// 外部 API：透過 Spring HTTP 資源串流已完成路徑安全驗證的圖片。
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(image.contentType()))
			.contentLength(image.fileSize())
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline")
			.body(new ByteArrayResource(image.content()));
	}
}
