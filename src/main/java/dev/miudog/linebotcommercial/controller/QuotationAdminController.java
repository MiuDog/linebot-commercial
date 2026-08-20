package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import dev.miudog.linebotcommercial.service.quotation.QuotationAdminService;
import dev.miudog.linebotcommercial.service.quotation.QuotationAiParsingService;
import dev.miudog.linebotcommercial.service.quotation.QuotationAdminException;
import dev.miudog.linebotcommercial.service.quotation.QuotationMasterDataCsvService;
import dev.miudog.linebotcommercial.service.quotation.QuotationMasterDataXlsxService;
import dev.miudog.linebotcommercial.service.quotation.QuotationRequestValidationService;
import dev.miudog.linebotcommercial.service.quotation.QuotationWorkbookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

/**
 * 提供本機管理頁使用的報價類型與品項主檔 API。
 */
@RestController
@RequestMapping("/api/admin")
public class QuotationAdminController {

	private final QuotationAdminService service;
	private final QuotationAiParsingService aiParsingService;
	private final QuotationRequestValidationService requestValidationService;
	private final QuotationWorkbookService workbookService;
	private final QuotationMasterDataCsvService csvService;
	private final QuotationMasterDataXlsxService xlsxService;

	// 方法：建立本機報價管理 API。
	public QuotationAdminController(
		QuotationAdminService service,
		QuotationAiParsingService aiParsingService,
		QuotationRequestValidationService requestValidationService,
		QuotationWorkbookService workbookService,
		QuotationMasterDataCsvService csvService,
		QuotationMasterDataXlsxService xlsxService
	) {
		this.service = service;
		this.aiParsingService = aiParsingService;
		this.requestValidationService = requestValidationService;
		this.workbookService = workbookService;
		this.csvService = csvService;
		this.xlsxService = xlsxService;
	}

	// 方法：回傳 AI 是否已設定，且不揭露網址、模型名稱或金鑰內容。
	@GetMapping("/quotation-ai-status")
	public AiStatusResponse quotationAiStatus() {
		return new AiStatusResponse(aiParsingService.isConfigured());
	}

	// 方法：回報是否已設定報價單輸出根目錄，供本機管理頁停用不可能成功的操作。
	@GetMapping("/quotation-workbook-status")
	public WorkbookStatusResponse quotationWorkbookStatus() {
		return new WorkbookStatusResponse(workbookService.isConfigured());
	}

	// 方法：讓本機管理頁以純文字試跑 AI 報價解析，圖片將於 LINE 流程接入。
	@PostMapping("/quotation-ai-parse")
	public QuotationRequestValidationService.ValidatedQuotationRequest parseQuotationInstruction(
		@RequestBody AiParseRequest request
	) {
		return aiParsingService.parse(request.instruction(), List.of(), request.schemeCode()).request();
	}

	// 方法：列出五種報價類型與範本就緒狀態。
	@GetMapping("/quotation-schemes")
	public List<QuotationAdminRepository.SchemeSummary> listSchemes() {
		return service.listSchemes();
	}

	// 方法：列出全部品項主檔。
	@GetMapping("/quotation-items")
	public List<QuotationAdminRepository.Item> listItems() {
		return service.listItems();
	}

	// 方法：建立可供多種報價單共用的品項。
	@PostMapping("/quotation-items")
	public ResponseEntity<QuotationAdminRepository.Item> createItem(@RequestBody CreateItemRequest request) {
		QuotationAdminRepository.Item item = service.createItem(
			new QuotationAdminService.CreateItemCommand(
				request.code(),
				request.name(),
				request.aliases(),
				request.isActive()
			)
		);
		return ResponseEntity.created(URI.create("/api/admin/quotation-items/" + item.id())).body(item);
	}

	// 方法：只更新使用者有送出的品項欄位。
	@PatchMapping("/quotation-items/{itemId}")
	public QuotationAdminRepository.Item updateItem(
		@PathVariable long itemId,
		@RequestBody UpdateItemRequest request
	) {
		return service.updateItem(
			itemId,
			new QuotationAdminService.UpdateItemCommand(
				request.code(),
				request.name(),
				request.aliases(),
				request.isActive()
			)
		);
	}

	// 方法：列出指定報價類型會使用的固定品項資料。
	@GetMapping("/quotation-schemes/{schemeCode}/items")
	public List<QuotationAdminRepository.SchemeItem> listSchemeItems(@PathVariable String schemeCode) {
		return service.listSchemeItems(schemeCode);
	}

	// 方法：新增或取代指定報價類型中的品項固定欄位。
	@PutMapping("/quotation-schemes/{schemeCode}/items/{itemId}")
	public QuotationAdminRepository.SchemeItem upsertSchemeItem(
		@PathVariable String schemeCode,
		@PathVariable long itemId,
		@RequestBody UpsertSchemeItemRequest request
	) {
		return service.upsertSchemeItem(
			schemeCode,
			itemId,
			new QuotationAdminService.UpsertSchemeItemCommand(
				request.specification(),
				request.unit(),
				request.unitPrice(),
				request.remark(),
				request.displayOrder(),
				request.calculationMode(),
				request.isCustomerVisible(),
				request.isActive()
			)
		);
	}

	// 方法：驗證 AI 固定 JSON 並以資料庫主檔解析固定欄位，但不在驗證階段計價。
	@PostMapping("/quotation-request-validation")
	public QuotationRequestValidationService.ValidatedQuotationRequest validateQuotationRequest(
		@RequestBody JsonNode request,
		@RequestParam(name = "schemeCode", required = false) String schemeCode
	) {
		return requestValidationService.validate(request, schemeCode);
	}

	// 方法：拒絕舊版未確認的 Excel 建立入口，正式產檔只能由確認交易排程。
	@PostMapping("/quotation-workbooks")
	public void rejectUnconfirmedWorkbookCreation() {
		throw new QuotationAdminException(
			"CONFIRMATION_REQUIRED",
			"Excel 報價單必須先完成完整預覽與正式確認"
		);
	}

	// 方法：下載可由 Excel 開啟且明確不是 XLSX 的 UTF-8 主檔 CSV。
	@GetMapping(value = "/quotation-master-data.csv", produces = "text/csv;charset=UTF-8")
	public ResponseEntity<byte[]> exportMasterDataCsv() {
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-master-data.csv\"")
			.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
			.body(csvService.export());
	}

	// 方法：以匯出的同一份 CSV 覆蓋主檔，讓日常維護可在 Excel 完成後整批上傳。
	@PostMapping(value = "/quotation-master-data.csv", consumes = "text/csv")
	public QuotationMasterDataCsvService.ImportResult importMasterDataCsv(@RequestBody byte[] content) {
		return csvService.importCsv(content);
	}

	// 方法：下載保留資料型別與表格格式的真正 XLSX 報價主檔。
	@GetMapping(
		value = "/quotation-master-data.xlsx",
		produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	)
	public ResponseEntity<byte[]> exportMasterDataXlsx() {
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-master-data.xlsx\"")
			.header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
			.body(xlsxService.export());
	}

	public record CreateItemRequest(String code, String name, List<String> aliases, boolean isActive) {}

	public record AiStatusResponse(boolean configured) {}

	public record WorkbookStatusResponse(boolean configured) {}

	public record AiParseRequest(String instruction, String schemeCode) {}

	public record UpdateItemRequest(String code, String name, List<String> aliases, Boolean isActive) {}

	public record UpsertSchemeItemRequest(
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		Integer displayOrder,
		String calculationMode,
		boolean isCustomerVisible,
		boolean isActive
	) {}
}
