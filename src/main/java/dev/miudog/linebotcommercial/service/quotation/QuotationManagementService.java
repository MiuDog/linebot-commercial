package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationManagementRepository;
import dev.miudog.linebotcommercial.service.FileStorageService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 聚合本機管理頁的正式報價查詢、受控下載及可重試操作。
 */
@Service
public class QuotationManagementService {

	private static final String QUOTATION_DIRECTORY = "報價單";
	private static final int MAXIMUM_FILTER_LENGTH = 100;
	private static final int MAXIMUM_PAGE_SIZE = 100;
	private static final Set<String> QUOTATION_STATUSES = Set.of(
		"CONFIRMED",
		"GENERATING_EXCEL",
		"GENERATING_PDF",
		"PDF_FAILED",
		"READY",
		"SENDING",
		"SENT",
		"CANCELLED",
		"FAILED"
	);
	private static final Set<String> FILE_KINDS = Set.of("XLSX", "PDF");
	private static final Set<String> DRAFT_STATUSES = Set.of(
		"COLLECTING_BASE_INFO",
		"COLLECTING_ITEMS",
		"AWAITING_IMAGE",
		"READY_FOR_PREVIEW",
		"AWAITING_CONFIRMATION",
		"CONFIRMED",
		"CANCELLED",
		"EXPIRED"
	);
	private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
		"image/jpeg",
		"image/png",
		"image/gif",
		"image/webp"
	);
	private final QuotationManagementRepository repository;
	private final QuotationPdfService pdfService;
	private final QuotationDeliveryService deliveryService;
	private final QuotationDownloadService downloadService;
	private final QuotationGenerationJobRepository generationJobs;
	private final QuotationGenerationJobWorker generationWorker;
	private final FileStorageService storage;
	private final Path outputRoot;
	private final Duration downloadLinkTimeToLive;
	private final QuotationBusinessRules businessRules;

	// 方法：建立只使用既有正式報價、PDF、LINE 與權杖服務的管理邊界。
	public QuotationManagementService(
		QuotationManagementRepository repository,
		QuotationPdfService pdfService,
		QuotationDeliveryService deliveryService,
		QuotationDownloadService downloadService,
		QuotationGenerationJobRepository generationJobs,
		QuotationGenerationJobWorker generationWorker,
		FileStorageService storage,
		QuotationBusinessRules businessRules,
		@Value("${app.quotation.root-path:}") String outputRoot,
		@Value("${app.quotation.delivery-link-ttl-hours:168}") long downloadLinkTimeToLiveHours
	) {
		this.repository = repository;
		this.pdfService = pdfService;
		this.deliveryService = deliveryService;
		this.downloadService = downloadService;
		this.generationJobs = generationJobs;
		this.generationWorker = generationWorker;
		this.storage = storage;
		this.businessRules = businessRules;
		this.outputRoot = outputRoot == null || outputRoot.isBlank()
			? null
			: Paths.get(outputRoot).toAbsolutePath().normalize();
		if (downloadLinkTimeToLiveHours < 1 || downloadLinkTimeToLiveHours > 720) {
			throw new IllegalArgumentException("PDF 下載連結有效時數必須介於 1 至 720");
		}

		this.downloadLinkTimeToLive = Duration.ofHours(downloadLinkTimeToLiveHours);
	}

	// 方法：驗證草稿查詢條件並回傳一頁不含擁有者及圖片路徑的摘要。
	public DraftListResponse listDrafts(DraftListQuery query) {
		QuotationManagementRepository.DraftFilter filter = validatedDraftFilter(query);
		QuotationManagementRepository.DraftPage page = repository.findDrafts(filter);
		int totalPages = page.totalItems() == 0
			? 0
			: (int) Math.ceil((double) page.totalItems() / filter.pageSize());
		return new DraftListResponse(
			page.data(),
			new Pagination(filter.page(), filter.pageSize(), page.totalItems(), totalPages)
		);
	}

	// 方法：聚合完整草稿抬頭、品項快照、欄位證據、缺漏與程式計算金額。
	public DraftDetail draftDetail(long draftId) {
		QuotationManagementRepository.DraftHeader header = repository.findDraftHeader(draftId)
			.orElseThrow(() -> notFound("找不到報價草稿"));
		List<QuotationManagementRepository.DraftItem> items = repository.findDraftItems(draftId);
		boolean hasSelectedImage = repository.findSelectedDraftImage(draftId).isPresent();
		List<String> missingFields = missingDraftFields(header, items, hasSelectedImage);
		DraftAmounts amounts = missingFields.isEmpty() ? calculateDraftAmounts(items) : DraftAmounts.empty();
		String selectedImageUrl = hasSelectedImage
			? "/api/admin/quotation-drafts/" + draftId + "/selected-image"
			: null;
		return new DraftDetail(
			header.id(),
			header.quotationName(),
			header.companyName(),
			header.workName(),
			header.salesRepresentative(),
			header.contactName(),
			header.customerPhone(),
			header.customerEmail(),
			header.projectLocation(),
			header.additionalHeader(),
			header.schemeCode(),
			header.status(),
			header.revision(),
			header.imageDeclined(),
			header.createdAt(),
			header.updatedAt(),
			items,
			repository.findDraftFieldEvidence(draftId),
			List.copyOf(missingFields),
			amounts.subtotal(),
			amounts.taxRate(),
			amounts.taxAmount(),
			amounts.totalAmount(),
			selectedImageUrl
		);
	}

	// 方法：驗證查詢條件並回傳一頁不含檔案路徑、目的地或權杖的正式報價摘要。
	public QuotationListResponse list(QuotationListQuery query) {
		QuotationManagementRepository.QuotationFilter filter = validatedFilter(query);
		QuotationManagementRepository.QuotationPage page = repository.findQuotations(filter);
		int totalPages = page.totalItems() == 0
			? 0
			: (int) Math.ceil((double) page.totalItems() / filter.pageSize());
		return new QuotationListResponse(
			page.data(),
			new Pagination(filter.page(), filter.pageSize(), page.totalItems(), totalPages)
		);
	}

	// 方法：聚合正式報價抬頭、客戶可見品項、檔案及 LINE 狀態供管理預覽。
	public QuotationDetail detail(long quotationId) {
		QuotationManagementRepository.QuotationHeader header = repository.findHeader(quotationId)
			.orElseThrow(() -> notFound("找不到正式報價"));
		Map<String, QuotationManagementRepository.FileState> states = repository.findFileStates(quotationId).stream()
			.collect(Collectors.toMap(
				QuotationManagementRepository.FileState::fileKind,
				Function.identity()
			));
		QuotationManagementRepository.DeliveryState delivery = repository.findDeliveryState(quotationId);
		return new QuotationDetail(
			header.id(),
			header.quotationNumber(),
			header.quotationName(),
			header.companyName(),
			header.workName(),
			header.salesRepresentative(),
			header.quotationDate(),
			header.validUntil(),
			header.schemeCode(),
			header.currency(),
			header.subtotal(),
			header.taxRate(),
			header.taxAmount(),
			header.totalAmount(),
			header.status(),
			header.createdAt(),
			repository.findCustomerLines(quotationId),
			new FileViews(
				fileView(
					quotationId,
					"XLSX",
					states.get("XLSX"),
					"FAILED".equals(header.status())
				),
				fileView(
					quotationId,
					"PDF",
					states.get("PDF"),
					"PDF_FAILED".equals(header.status())
				)
			),
			new DeliveryView(
				delivery.status(),
				delivery.attemptCount(),
				delivery.errorMessage(),
				"FAILED".equals(delivery.status()) && "READY".equals(header.status())
			),
			repository.findSelectedQuotationImage(quotationId).isPresent()
				? "/api/admin/quotations/" + quotationId + "/selected-image"
				: null,
			repository.findAuditRecords(quotationId)
		);
	}

	// 方法：解析資料庫擁有者綁定且位於 .pending 的草稿選圖。
	public AdminImageResource resolveDraftImage(long draftId) {
		QuotationManagementRepository.AdminImageRecord record = repository.findSelectedDraftImage(draftId)
			.orElseThrow(() -> notFound("找不到草稿選圖"));
		Path path = safeImagePath(record.locator(), storage.root(), true);
		return imageResource(path, record.contentType());
	}

	// 方法：解析與正式報價綁定且位於報價單根目錄的選圖。
	public AdminImageResource resolveQuotationImage(long quotationId) {
		QuotationManagementRepository.AdminImageRecord record = repository.findSelectedQuotationImage(quotationId)
			.orElseThrow(() -> notFound("找不到正式報價選圖"));
		if (outputRoot == null) {
			throw new QuotationAdminException("OUTPUT_NOT_CONFIGURED", "尚未設定報價輸出根目錄");
		}

		Path path = safeImagePath(record.locator(), outputRoot.resolve(QUOTATION_DIRECTORY), false);
		return imageResource(path, record.contentType());
	}

	// 方法：將 Excel 失敗工作以同一正式報價、流水號及資料夾重新排入持久化佇列。
	public XlsxRetryResponse retryXlsx(long quotationId) {
		QuotationManagementRepository.QuotationHeader header = repository.findHeader(quotationId)
			.orElseThrow(() -> notFound("找不到正式報價"));
		if (!generationJobs.retryFailedXlsx(quotationId, MDC.get("requestId"))) {
			throw new QuotationAdminException(
				"XLSX_RETRY_NOT_ALLOWED",
				"只有 Excel 產生失敗且未在執行中的報價可以重試"
			);
		}

		repository.audit(quotationId, "QUOTATION_XLSX_RETRY", "QUEUED");
		generationWorker.wake();
		return new XlsxRetryResponse(quotationId, header.quotationNumber(), "PENDING");
	}

	// 方法：解析資料庫綁定且位於報價根目錄內的 READY XLSX 或 PDF。
	public AdminFileResource resolveFile(long quotationId, String requestedFileKind) {
		String fileKind = normalizeFileKind(requestedFileKind);
		QuotationManagementRepository.AdminFileRecord record = repository.findReadyFile(quotationId, fileKind)
			.orElseThrow(() -> new QuotationAdminException("FILE_NOT_READY", "報價檔案尚未完成"));
		Path path = safeFilePath(record.relativePath(), fileKind);
		repository.audit(quotationId, "QUOTATION_FILE_DOWNLOAD_" + fileKind, "ALLOWED");
		return new AdminFileResource(
			path,
			path.getFileName().toString(),
			record.contentType(),
			fileSize(path)
		);
	}

	// 方法：只允許 PDF_FAILED 正式報價以同一識別碼與流水號重試。
	public PdfRetryResponse retryPdf(long quotationId) {
		if (!repository.isPdfRetryable(quotationId)) {
			throw new QuotationAdminException("PDF_RETRY_NOT_ALLOWED", "只有 PDF_FAILED 報價可以重試 PDF");
		}

		try {
			QuotationPdfService.PdfExportResult result = pdfService.retry(quotationId);
			repository.audit(quotationId, "QUOTATION_PDF_RETRY", "SUCCEEDED");
			return new PdfRetryResponse(result.quotationId(), result.quotationNumber(), "READY");
		}
		catch (RuntimeException exception) {
			repository.audit(quotationId, "QUOTATION_PDF_RETRY", "FAILED");
			throw exception;
		}
	}

	// 方法：只沿用資料庫最新失敗紀錄的 LINE 目的地重送，不接受管理頁自訂目的地。
	public LineRetryResponse retryLine(long quotationId) {
		String destination = repository.findRetryDestination(quotationId)
			.orElseThrow(() -> new QuotationAdminException(
				"LINE_RETRY_NOT_ALLOWED",
				"找不到可重送的 LINE 失敗紀錄"
			));
		try {
			QuotationDeliveryResult result = deliveryService.deliverFinal(quotationId, destination);
			repository.audit(quotationId, "QUOTATION_LINE_RETRY", result.status().name());
			return new LineRetryResponse(
				result.quotationId(),
				result.status().name(),
				result.attemptCount(),
				result.errorSummary()
			);
		}
		catch (RuntimeException exception) {
			repository.audit(quotationId, "QUOTATION_LINE_RETRY", "FAILED");
			throw exception;
		}
	}

	// 方法：撤銷正式報價全部 PDF 權杖而不回傳原始權杖或雜湊。
	public RevocationResponse revokePdfLinks(long quotationId) {
		if (!repository.exists(quotationId)) throw notFound("找不到正式報價");

		downloadService.revokePdfLinks(quotationId);
		repository.audit(quotationId, "QUOTATION_PDF_LINKS_REVOKE", "SUCCEEDED");
		return new RevocationResponse(quotationId, true);
	}

	// 方法：為已完成 PDF 重新建立可複製的短效 HTTPS URL，不另外回傳原始權杖欄位。
	public LinkRegenerationResponse regeneratePdfLink(long quotationId) {
		QuotationDownloadLink link = downloadService.issuePdfLink(quotationId, downloadLinkTimeToLive);
		repository.audit(quotationId, "QUOTATION_PDF_LINK_REGENERATE", "SUCCEEDED");
		return new LinkRegenerationResponse(quotationId, true, link.url(), link.expiresAt());
	}

	// 方法：驗證草稿搜尋文字、狀態與分頁邊界。
	private QuotationManagementRepository.DraftFilter validatedDraftFilter(DraftListQuery query) {
		if (query == null) query = new DraftListQuery(null, null, 1, 20);

		String search = optionalText(query.search(), "草稿搜尋");
		String status = optionalText(query.status(), "草稿狀態");
		if (status != null) {
			status = status.toUpperCase(Locale.ROOT);
			if (!DRAFT_STATUSES.contains(status)) throw validation("草稿狀態不正確");
		}
		if (query.page() < 1 || query.page() > 100000) throw validation("頁碼不正確");

		if (query.pageSize() < 1 || query.pageSize() > MAXIMUM_PAGE_SIZE) {
			throw validation("每頁筆數必須介於 1 至 100");
		}
		return new QuotationManagementRepository.DraftFilter(search, status, query.page(), query.pageSize());
	}

	// 方法：依正式確認規則列出草稿仍缺少的抬頭或品項欄位。
	private List<String> missingDraftFields(
		QuotationManagementRepository.DraftHeader header,
		List<QuotationManagementRepository.DraftItem> items,
		boolean hasSelectedImage
	) {
		List<String> missing = new ArrayList<>();
		addMissing(missing, header.quotationName(), "報價名稱");
		addMissing(missing, header.companyName(), "公司名稱");
		addMissing(missing, header.workName(), "工作名稱");
		addMissing(missing, header.schemeCode(), "報價格式");
		if (("MARINE".equals(header.schemeCode()) || "BLANK".equals(header.schemeCode()))
			&& !hasSelectedImage
			&& !header.imageDeclined()) {
			missing.add("工程圖片或明確拒絕");
		}
		List<QuotationManagementRepository.DraftItem> activeItems = items.stream()
			.filter(item -> !item.removed())
			.toList();
		if (activeItems.isEmpty()) missing.add("品項");

		boolean hasQuantity = false;
		for (int index = 0; index < activeItems.size(); index++) {
			QuotationManagementRepository.DraftItem item = activeItems.get(index);
			String prefix = "品項 " + (index + 1) + " ";
			addMissing(missing, item.itemName(), prefix + "名稱");
			addMissing(missing, item.unit(), prefix + "單位");
			if (item.unitPrice() == null) missing.add(prefix + "單價");
			if (!"STANDARD".equals(item.itemKind()) && item.quantity() == null) {
				missing.add(prefix + "數量");
			}
			if (item.quantity() != null) hasQuantity = true;
		}
		if (!activeItems.isEmpty() && !hasQuantity) missing.add("至少一筆品項數量");
		return missing;
	}

	// 方法：將空白文字欄位加入明確缺漏清單。
	private void addMissing(List<String> missing, String value, String label) {
		if (value == null || value.isBlank()) missing.add(label);
	}

	// 方法：只以草稿數量與單價快照計算未稅、稅額及總價。
	private DraftAmounts calculateDraftAmounts(List<QuotationManagementRepository.DraftItem> items) {
		BigDecimal subtotal = items.stream()
			.filter(item -> !item.removed())
			.filter(item -> item.quantity() != null && item.unitPrice() != null)
			.map(item -> item.quantity().multiply(item.unitPrice()))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		subtotal = money(subtotal);
		BigDecimal taxAmount = money(subtotal.multiply(businessRules.taxRate()));
		return new DraftAmounts(
			subtotal,
			businessRules.taxRate(),
			taxAmount,
			money(subtotal.add(taxAmount))
		);
	}

	// 方法：以報價金額精度四捨五入至小數點後二位。
	private BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	// 方法：安全解析圖片相對路徑並拒絕逃逸、符號連結或非一般檔案。
	private Path safeImagePath(String locator, Path allowedRoot, boolean pendingOnly) {
		if (locator == null || locator.isBlank()) throw invalidImagePath();

		Path relative = Paths.get(locator);
		if (relative.isAbsolute()) throw invalidImagePath();

		String normalizedLocator = locator.replace('\\', '/');
		if (pendingOnly && !normalizedLocator.startsWith(".pending/")) throw invalidImagePath();

		Path base = pendingOnly ? allowedRoot : allowedRoot.getParent();
		Path resolved = base.resolve(relative).normalize();
		if (!resolved.startsWith(allowedRoot.normalize())) throw invalidImagePath();

		try {
			rejectSymbolicLinks(base, resolved);
			Path realRoot = allowedRoot.toRealPath();
			Path realFile = resolved.toRealPath();
			if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile) || !Files.isReadable(realFile)) {
				throw invalidImagePath();
			}
			return realFile;
		}
		catch (QuotationAdminException exception) {
			throw exception;
		}
		catch (IOException exception) {
			throw new QuotationAdminException("IMAGE_NOT_FOUND", "選取圖片不存在");
		}
	}

	// 方法：逐層拒絕根目錄至目標檔案之間的符號連結。
	private void rejectSymbolicLinks(Path base, Path target) {
		Path current = base.toAbsolutePath().normalize();
		Path relative = current.relativize(target.toAbsolutePath().normalize());
		for (Path component : relative) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw invalidImagePath();
		}
	}

	// 方法：驗證圖片 MIME 並以實際檔案大小建立管理資源。
	private AdminImageResource imageResource(Path path, String contentType) {
		String normalizedType = contentType == null
			? ""
			: contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		if (!IMAGE_CONTENT_TYPES.contains(normalizedType)) {
			throw new QuotationAdminException("INVALID_IMAGE_TYPE", "選取圖片格式不支援");
		}
		return new AdminImageResource(path, normalizedType, fileSize(path));
	}

	// 方法：建立不揭露本機圖片路徑的邊界錯誤。
	private QuotationAdminException invalidImagePath() {
		return new QuotationAdminException("INVALID_IMAGE_PATH", "選取圖片路徑不正確");
	}

	// 方法：驗證搜尋文字、日期、狀態與分頁邊界。
	private QuotationManagementRepository.QuotationFilter validatedFilter(QuotationListQuery query) {
		if (query == null) query = new QuotationListQuery(null, null, null, null, null, null, 1, 20);

		String quotationNumber = optionalText(query.quotationNumber(), "報價單號");
		String company = optionalText(query.company(), "公司");
		String work = optionalText(query.work(), "工作");
		String dateFrom = optionalDate(query.dateFrom(), "開始日期");
		String dateTo = optionalDate(query.dateTo(), "結束日期");
		if (dateFrom != null && dateTo != null && dateFrom.compareTo(dateTo) > 0) {
			throw validation("開始日期不可晚於結束日期");
		}

		String status = optionalText(query.status(), "報價狀態");
		if (status != null) {
			status = status.toUpperCase(Locale.ROOT);
			if (!QUOTATION_STATUSES.contains(status)) throw validation("報價狀態不正確");
		}

		if (query.page() < 1 || query.page() > 100000) throw validation("頁碼不正確");

		if (query.pageSize() < 1 || query.pageSize() > MAXIMUM_PAGE_SIZE) {
			throw validation("每頁筆數必須介於 1 至 100");
		}

		return new QuotationManagementRepository.QuotationFilter(
			quotationNumber,
			company,
			work,
			dateFrom,
			dateTo,
			status,
			query.page(),
			query.pageSize()
		);
	}

	// 方法：正規化可空搜尋文字並拒絕過長或控制字元。
	private String optionalText(String value, String fieldName) {
		if (value == null || value.isBlank()) return null;

		String normalized = value.trim();
		if (normalized.length() > MAXIMUM_FILTER_LENGTH) throw validation(fieldName + "搜尋條件過長");

		if (normalized.chars().anyMatch(Character::isISOControl)) {
			throw validation(fieldName + "搜尋條件含有無效字元");
		}

		return normalized;
	}

	// 方法：將可空日期限制為 ISO yyyy-MM-dd。
	private String optionalDate(String value, String fieldName) {
		String normalized = optionalText(value, fieldName);
		if (normalized == null) return null;

		try {
			return LocalDate.parse(normalized).toString();
		}
		catch (DateTimeParseException exception) {
			throw validation(fieldName + "格式必須是 yyyy-MM-dd");
		}
	}

	// 方法：將檔案狀態轉成不含內部路徑的管理動作摘要。
	private FileView fileView(
		long quotationId,
		String fileKind,
		QuotationManagementRepository.FileState state,
		boolean canRetry
	) {
		if (state == null) return new FileView(null, null, null, false, false);

		boolean canDownload = "READY".equals(state.status());
		String downloadUrl = canDownload
			? "/api/admin/quotations/" + quotationId + "/files/" + fileKind
			: null;
		return new FileView(
			state.status(),
			state.errorMessage(),
			state.fileSize(),
			canDownload,
			canRetry && "FAILED".equals(state.status()),
			downloadUrl
		);
	}

	// 方法：只接受固定 XLSX 或 PDF 路徑變數。
	private String normalizeFileKind(String value) {
		String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
		if (!FILE_KINDS.contains(normalized)) throw validation("報價檔案類型不正確");

		return normalized;
	}

	// 方法：以正規化及實體路徑雙重限制檔案必須留在報價單根目錄。
	private Path safeFilePath(String relativePath, String fileKind) {
		if (outputRoot == null) {
			throw new QuotationAdminException("OUTPUT_NOT_CONFIGURED", "尚未設定報價輸出根目錄");
		}

		if (relativePath == null || relativePath.isBlank()) throw invalidFilePath();

		Path relative = Paths.get(relativePath);
		if (relative.isAbsolute()) throw invalidFilePath();

		Path quotationRoot = outputRoot.resolve(QUOTATION_DIRECTORY).normalize();
		Path resolved = outputRoot.resolve(relative).normalize();
		if (!resolved.startsWith(quotationRoot)) throw invalidFilePath();

		String expectedExtension = "XLSX".equals(fileKind) ? ".xlsx" : ".pdf";
		if (!resolved.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
			throw invalidFilePath();
		}

		try {
			Path realRoot = quotationRoot.toRealPath();
			Path realFile = resolved.toRealPath();
			if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile) || !Files.isReadable(realFile)) {
				throw invalidFilePath();
			}

			return realFile;
		}
		catch (IOException exception) {
			throw new QuotationAdminException("FILE_NOT_FOUND", "報價檔案不存在");
		}
	}

	// 方法：從實體檔案讀取大小，不信任資料庫快照值。
	private long fileSize(Path path) {
		try {
			return Files.size(path);
		}
		catch (IOException exception) {
			throw new QuotationAdminException("FILE_NOT_FOUND", "報價檔案無法讀取");
		}
	}

	// 方法：建立不揭露本機路徑的檔案邊界錯誤。
	private QuotationAdminException invalidFilePath() {
		return new QuotationAdminException("INVALID_FILE_PATH", "報價檔案路徑不正確");
	}

	// 方法：建立管理 API 驗證錯誤。
	private QuotationAdminException validation(String message) {
		return new QuotationAdminException("VALIDATION_ERROR", message);
	}

	// 方法：建立管理 API 找不到資源錯誤。
	private QuotationAdminException notFound(String message) {
		return new QuotationAdminException("NOT_FOUND", message);
	}

	public record QuotationListQuery(
		String quotationNumber,
		String company,
		String work,
		String dateFrom,
		String dateTo,
		String status,
		int page,
		int pageSize
	) {}

	public record QuotationListResponse(
		List<QuotationManagementRepository.QuotationSummary> data,
		Pagination pagination
	) {}

	public record DraftListQuery(String search, String status, int page, int pageSize) {}

	public record DraftListResponse(
		List<QuotationManagementRepository.DraftSummary> data,
		Pagination pagination
	) {}

	public record Pagination(int page, int pageSize, long totalItems, int totalPages) {}

	public record QuotationDetail(
		long id,
		String quotationNumber,
		String quotationName,
		String companyName,
		String workName,
		String salesRepresentative,
		String quotationDate,
		String validUntil,
		String schemeCode,
		String currency,
		BigDecimal subtotal,
		BigDecimal taxRate,
		BigDecimal taxAmount,
		BigDecimal totalAmount,
		String status,
		String createdAt,
		List<QuotationManagementRepository.QuotationLine> lines,
		FileViews files,
		DeliveryView delivery,
		String selectedImageUrl,
		List<QuotationManagementRepository.AuditRecord> auditRecords
	) {}

	public record DraftDetail(
		long id,
		String quotationName,
		String companyName,
		String workName,
		String salesRepresentative,
		String contactName,
		String customerPhone,
		String customerEmail,
		String projectLocation,
		String additionalHeader,
		String schemeCode,
		String status,
		int revision,
		boolean imageDeclined,
		String createdAt,
		String updatedAt,
		List<QuotationManagementRepository.DraftItem> items,
		List<QuotationManagementRepository.DraftFieldEvidence> fieldEvidence,
		List<String> missingFields,
		BigDecimal subtotal,
		BigDecimal taxRate,
		BigDecimal taxAmount,
		BigDecimal totalAmount,
		String selectedImageUrl
	) {}

	public record FileViews(FileView xlsx, FileView pdf) {}

	public record FileView(
		String status,
		String errorMessage,
		Long fileSize,
		boolean canDownload,
		boolean canRetry,
		String downloadUrl
	) {

		// 方法：建立尚未簽發下載網址的檔案狀態檢視。
		private FileView(String status, String errorMessage, Long fileSize, boolean canDownload, boolean canRetry) {
			this(status, errorMessage, fileSize, canDownload, canRetry, null);
		}
	}

	public record DeliveryView(String status, int attemptCount, String errorMessage, boolean canRetry) {}

	public record AdminFileResource(Path path, String fileName, String contentType, long fileSize) {}

	public record AdminImageResource(Path path, String contentType, long fileSize) {}

	public record PdfRetryResponse(long quotationId, String quotationNumber, String status) {}

	public record XlsxRetryResponse(long quotationId, String quotationNumber, String status) {}

	public record LineRetryResponse(long quotationId, String status, int attemptCount, String errorSummary) {}

	public record RevocationResponse(long quotationId, boolean revoked) {}

	public record LinkRegenerationResponse(long quotationId, boolean regenerated, String url, Instant expiresAt) {}

	private record DraftAmounts(
		BigDecimal subtotal,
		BigDecimal taxRate,
		BigDecimal taxAmount,
		BigDecimal totalAmount
	) {

		// 方法：建立資料不完整時四個金額均為空的結果。
		private static DraftAmounts empty() {
			return new DraftAmounts(null, null, null, null);
		}
	}
}
