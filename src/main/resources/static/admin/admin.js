"use strict";

const state = {
	schemes: [],
	items: [],
	mappings: [],
	aiConfigured: false,
	quotationSource: null,
	quotationDrafts: [],
	selectedQuotationDraft: null,
	quotationDraftPage: 1,
	quotationDraftTotalPages: 0,
	quotations: [],
	selectedQuotation: null,
	quotationPage: 1,
	quotationTotalPages: 0,
};

const elements = {
	schemeList: document.querySelector("#scheme-list"),
	schemeSelect: document.querySelector("#scheme-select"),
	itemRows: document.querySelector("#item-rows"),
	itemEmpty: document.querySelector("#item-empty"),
	itemSearch: document.querySelector("#item-search"),
	itemForm: document.querySelector("#item-form"),
	itemFormTitle: document.querySelector("#item-form-title"),
	itemId: document.querySelector("#item-id"),
	itemCode: document.querySelector("#item-code"),
	itemName: document.querySelector("#item-name"),
	itemAliases: document.querySelector("#item-aliases"),
	itemActive: document.querySelector("#item-active"),
	itemReset: document.querySelector("#item-reset"),
	mappingRows: document.querySelector("#mapping-rows"),
	mappingEmpty: document.querySelector("#mapping-empty"),
	mappingForm: document.querySelector("#mapping-form"),
	mappingItem: document.querySelector("#mapping-item"),
	mappingSpecification: document.querySelector("#mapping-specification"),
	mappingUnit: document.querySelector("#mapping-unit"),
	mappingPrice: document.querySelector("#mapping-price"),
	mappingRemark: document.querySelector("#mapping-remark"),
	mappingOrder: document.querySelector("#mapping-order"),
	mappingMode: document.querySelector("#mapping-mode"),
	mappingVisible: document.querySelector("#mapping-visible"),
	mappingActive: document.querySelector("#mapping-active"),
	validationForm: document.querySelector("#validation-form"),
	aiParseForm: document.querySelector("#ai-parse-form"),
	masterDataImport: document.querySelector("#master-data-import"),
	aiInstruction: document.querySelector("#ai-instruction"),
	aiParseButton: document.querySelector("#ai-parse-button"),
	aiStatus: document.querySelector("#ai-status"),
	validationJson: document.querySelector("#validation-json"),
	validationResult: document.querySelector("#validation-result"),
	validationResultName: document.querySelector("#validation-result-name"),
	validationResultScheme: document.querySelector("#validation-result-scheme"),
	validationResultRows: document.querySelector("#validation-result-rows"),
	validationResultImage: document.querySelector("#validation-result-image"),
	validationResultMissing: document.querySelector("#validation-result-missing"),
	validationResultWarnings: document.querySelector("#validation-result-warnings"),
	quotationDraftFilterForm: document.querySelector("#quotation-draft-filter-form"),
	quotationDraftSearch: document.querySelector("#quotation-draft-search"),
	quotationDraftStatus: document.querySelector("#quotation-draft-status"),
	quotationDraftFilterReset: document.querySelector("#quotation-draft-filter-reset"),
	quotationDraftListSummary: document.querySelector("#quotation-draft-list-summary"),
	quotationDraftRefresh: document.querySelector("#quotation-draft-refresh"),
	quotationDraftRows: document.querySelector("#quotation-draft-rows"),
	quotationDraftEmpty: document.querySelector("#quotation-draft-empty"),
	quotationDraftPrevious: document.querySelector("#quotation-draft-previous"),
	quotationDraftPageStatus: document.querySelector("#quotation-draft-page-status"),
	quotationDraftNext: document.querySelector("#quotation-draft-next"),
	quotationDraftDetail: document.querySelector("#quotation-draft-detail"),
	quotationDraftDetailTitle: document.querySelector("#quotation-draft-detail-title"),
	quotationDraftDetailStatus: document.querySelector("#quotation-draft-detail-status"),
	quotationDraftDetailSummary: document.querySelector("#quotation-draft-detail-summary"),
	quotationDraftMissing: document.querySelector("#quotation-draft-missing"),
	quotationDraftImagePanel: document.querySelector("#quotation-draft-image-panel"),
	quotationDraftSelectedImage: document.querySelector("#quotation-draft-selected-image"),
	quotationDraftDetailItems: document.querySelector("#quotation-draft-detail-items"),
	quotationFilterForm: document.querySelector("#quotation-filter-form"),
	quotationFilterNumber: document.querySelector("#quotation-filter-number"),
	quotationFilterCompany: document.querySelector("#quotation-filter-company"),
	quotationFilterWork: document.querySelector("#quotation-filter-work"),
	quotationFilterDateFrom: document.querySelector("#quotation-filter-date-from"),
	quotationFilterDateTo: document.querySelector("#quotation-filter-date-to"),
	quotationFilterStatus: document.querySelector("#quotation-filter-status"),
	quotationFilterReset: document.querySelector("#quotation-filter-reset"),
	quotationListSummary: document.querySelector("#quotation-list-summary"),
	quotationRefresh: document.querySelector("#quotation-refresh"),
	quotationRows: document.querySelector("#quotation-rows"),
	quotationEmpty: document.querySelector("#quotation-empty"),
	quotationPrevious: document.querySelector("#quotation-previous"),
	quotationPageStatus: document.querySelector("#quotation-page-status"),
	quotationNext: document.querySelector("#quotation-next"),
	quotationDetail: document.querySelector("#quotation-detail"),
	quotationDetailTitle: document.querySelector("#quotation-detail-title"),
	quotationDetailStatus: document.querySelector("#quotation-detail-status"),
	quotationDetailSummary: document.querySelector("#quotation-detail-summary"),
	quotationDetailLines: document.querySelector("#quotation-detail-lines"),
	quotationImagePanel: document.querySelector("#quotation-image-panel"),
	quotationSelectedImage: document.querySelector("#quotation-selected-image"),
	quotationFileActions: document.querySelector("#quotation-file-actions"),
	quotationRetryXlsx: document.querySelector("#quotation-retry-xlsx"),
	quotationRetryPdf: document.querySelector("#quotation-retry-pdf"),
	quotationRetryLine: document.querySelector("#quotation-retry-line"),
	quotationRegenerateLink: document.querySelector("#quotation-regenerate-link"),
	quotationRevokeLinks: document.querySelector("#quotation-revoke-links"),
	quotationCopyLink: document.querySelector("#quotation-copy-link"),
	quotationGeneratedLink: document.querySelector("#quotation-generated-link"),
	quotationCopyLinkButton: document.querySelector("#quotation-copy-link-button"),
	quotationAuditRecords: document.querySelector("#quotation-audit-records"),
	quotationOperationStatus: document.querySelector("#quotation-operation-status"),
	notice: document.querySelector("#notice"),
};

//#region 初始化與資料載入

async function initialize() {
	bindEvents();
	await Promise.all([
		loadSchemes(),
		loadItems(),
		loadAiStatus(),
		loadQuotationDrafts(),
		loadQuotations(),
	]);
	await loadMappings();
}

function bindEvents() {
	elements.itemForm.addEventListener("submit", saveItem);
	elements.itemReset.addEventListener("click", resetItemForm);
	elements.itemSearch.addEventListener("input", renderItems);
	elements.schemeSelect.addEventListener("change", loadMappings);
	elements.masterDataImport.addEventListener("change", importMasterDataCsv);
	elements.mappingForm.addEventListener("submit", saveMapping);
	elements.validationForm.addEventListener("submit", validateQuotationJson);
	elements.aiParseForm.addEventListener("submit", parseQuotationInstruction);
	elements.quotationDraftFilterForm.addEventListener("submit", filterQuotationDrafts);
	elements.quotationDraftFilterReset.addEventListener("click", resetQuotationDraftFilters);
	elements.quotationDraftRefresh.addEventListener("click", () => loadQuotationDrafts());
	elements.quotationDraftPrevious.addEventListener("click", showPreviousQuotationDraftPage);
	elements.quotationDraftNext.addEventListener("click", showNextQuotationDraftPage);
	elements.quotationFilterForm.addEventListener("submit", filterQuotations);
	elements.quotationFilterReset.addEventListener("click", resetQuotationFilters);
	elements.quotationRefresh.addEventListener("click", () => loadQuotations());
	elements.quotationPrevious.addEventListener("click", showPreviousQuotationPage);
	elements.quotationNext.addEventListener("click", showNextQuotationPage);
	elements.quotationRetryXlsx.addEventListener("click", retryQuotationXlsx);
	elements.quotationRetryPdf.addEventListener("click", retryQuotationPdf);
	elements.quotationRetryLine.addEventListener("click", retryQuotationLine);
	elements.quotationRegenerateLink.addEventListener("click", regenerateQuotationLink);
	elements.quotationRevokeLinks.addEventListener("click", revokeQuotationLinks);
	elements.quotationCopyLinkButton.addEventListener("click", copyQuotationLink);
}

async function loadAiStatus() {
	try {
		const status = await requestJson("/api/admin/quotation-ai-status");
		state.aiConfigured = status.configured;
		elements.aiParseButton.disabled = !state.aiConfigured;
		elements.aiStatus.textContent = state.aiConfigured
			? "AI 已設定，可以直接試跑。"
			: "AI 尚未設定；請先填寫 AI_API_URL、AI_API_KEY 與 AI_MODEL，再重新啟動程式。";
	}
	catch (error) {
		state.aiConfigured = false;
		elements.aiParseButton.disabled = true;
		elements.aiStatus.textContent = `無法確認 AI 狀態：${error.message}`;
	}
}

async function loadSchemes() {
	// 外部 API：向本機後端讀取五種報價格式與範本狀態。
	state.schemes = await requestJson("/api/admin/quotation-schemes");
	renderSchemes();
}

async function loadItems() {
	// 外部 API：向本機後端讀取共用品項主檔。
	state.items = await requestJson("/api/admin/quotation-items");
	renderItems();
	renderItemOptions();
}

async function loadMappings() {
	if (!elements.schemeSelect.value) return;

	// 外部 API：向本機後端讀取目前報價格式的固定品項資料。
	state.mappings = await requestJson(
		`/api/admin/quotation-schemes/${encodeURIComponent(elements.schemeSelect.value)}/items`,
	);
	renderMappings();
}

// 外部 API：使用固定搜尋與狀態參數讀取報價草稿。
async function loadQuotationDrafts(page = state.quotationDraftPage) {
	const parameters = new URLSearchParams({
		page: String(page),
		pageSize: "20",
	});
	if (elements.quotationDraftSearch.value.trim()) {
		parameters.set("search", elements.quotationDraftSearch.value.trim());
	}
	if (elements.quotationDraftStatus.value) {
		parameters.set("status", elements.quotationDraftStatus.value);
	}

	const result = await requestJson(`/api/admin/quotation-drafts?${parameters}`);
	state.quotationDrafts = result.data;
	state.quotationDraftPage = result.pagination.page;
	state.quotationDraftTotalPages = result.pagination.totalPages;
	renderQuotationDrafts(result.pagination);
}

// 外部 API：使用固定查詢參數讀取正式報價，不接受任意 SQL 或路徑。
async function loadQuotations(page = state.quotationPage) {
	const parameters = new URLSearchParams({
		page: String(page),
		pageSize: "20",
	});
	const filters = [
		["quotationNumber", elements.quotationFilterNumber.value],
		["company", elements.quotationFilterCompany.value],
		["work", elements.quotationFilterWork.value],
		["dateFrom", elements.quotationFilterDateFrom.value],
		["dateTo", elements.quotationFilterDateTo.value],
		["status", elements.quotationFilterStatus.value],
	];
	for (const [name, value] of filters) {
		if (value.trim()) parameters.set(name, value.trim());
	}

	const result = await requestJson(`/api/admin/quotations?${parameters}`);
	state.quotations = result.data;
	state.quotationPage = result.pagination.page;
	state.quotationTotalPages = result.pagination.totalPages;
	renderQuotations(result.pagination);
}

//#endregion

//#region 畫面呈現

function renderSchemes() {
	elements.schemeList.replaceChildren();
	elements.schemeSelect.replaceChildren();

	for (const scheme of state.schemes) {
		const card = createElement("article", "scheme-card");
		card.append(
			createElement("div", "scheme-code", scheme.code),
			createElement("div", "scheme-name", scheme.name),
			createElement(
				"div",
				`status ${scheme.templateReady ? "ready" : "pending"}`,
				scheme.templateReady ? "範本已設定" : "等待範本",
			),
		);
		elements.schemeList.append(card);

		const option = document.createElement("option");
		option.value = scheme.code;
		option.textContent = `${scheme.name}（${scheme.code}）`;
		elements.schemeSelect.append(option);
	}
}

function renderItems() {
	const query = elements.itemSearch.value.trim().toLocaleLowerCase("zh-Hant");
	const filtered = state.items.filter(item => {
		const searchable = [item.code, item.name, ...item.aliases].join(" ").toLocaleLowerCase("zh-Hant");
		return searchable.includes(query);
	});

	elements.itemRows.replaceChildren();
	elements.itemEmpty.hidden = filtered.length > 0;
	for (const item of filtered) {
		const row = document.createElement("tr");
		row.append(
			createCell(item.code),
			createCell(item.name),
			createCell(item.aliases.join("、") || "—"),
			createCell(item.isActive ? "啟用" : "停用"),
		);

		const actionCell = document.createElement("td");
		const editButton = createElement("button", "row-button", "編輯");
		editButton.type = "button";
		editButton.addEventListener("click", () => editItem(item));
		actionCell.append(editButton);
		row.append(actionCell);
		elements.itemRows.append(row);
	}
}

function renderItemOptions() {
	const selected = elements.mappingItem.value;
	elements.mappingItem.replaceChildren();
	for (const item of state.items.filter(candidate => candidate.isActive)) {
		const option = document.createElement("option");
		option.value = String(item.id);
		option.textContent = `${item.name}（${item.code}）`;
		elements.mappingItem.append(option);
	}
	if (selected) elements.mappingItem.value = selected;
}

function renderMappings() {
	elements.mappingRows.replaceChildren();
	elements.mappingEmpty.hidden = state.mappings.length > 0;
	for (const mapping of state.mappings) {
		const row = document.createElement("tr");
		row.append(
			createCell(String(mapping.displayOrder), "numeric"),
			createCell(mapping.itemName),
			createCell(mapping.specification || "—"),
			createCell(mapping.unit),
			createCell(formatMoney(mapping.unitPrice), "numeric"),
			createCell(mapping.remark || "—"),
		);
		row.tabIndex = 0;
		row.title = "點擊可載入右側表單";
		row.addEventListener("click", () => editMapping(mapping));
		row.addEventListener("keydown", event => {
			if (event.key === "Enter" || event.key === " ") editMapping(mapping);
		});
		elements.mappingRows.append(row);
	}
}

function createCell(text, className = "") {
	return createElement("td", className, text);
}

function createElement(tagName, className = "", text = "") {
	const element = document.createElement(tagName);
	if (className) element.className = className;
	if (text) element.textContent = text;
	return element;
}

//#endregion

//#region 表單操作

async function saveItem(event) {
	event.preventDefault();
	const itemId = elements.itemId.value;
	const payload = {
		code: elements.itemCode.value,
		name: elements.itemName.value,
		aliases: elements.itemAliases.value.split(",").map(value => value.trim()).filter(Boolean),
		isActive: elements.itemActive.checked,
	};

	try {
		// 外部 API：新增品項或以 PATCH 保留未改動欄位。
		await requestJson(
			itemId ? `/api/admin/quotation-items/${itemId}` : "/api/admin/quotation-items",
			{
				method: itemId ? "PATCH" : "POST",
				body: JSON.stringify(payload),
			},
		);
		showNotice(itemId ? "品項已更新" : "品項已建立");
		resetItemForm();
		await loadItems();
	}
	catch (error) {
		showNotice(error.message, true);
	}
}

function editItem(item) {
	elements.itemId.value = String(item.id);
	elements.itemCode.value = item.code;
	elements.itemName.value = item.name;
	elements.itemAliases.value = item.aliases.join(", ");
	elements.itemActive.checked = item.isActive;
	elements.itemFormTitle.textContent = "編輯品項";
	elements.itemCode.focus();
}

function resetItemForm() {
	elements.itemForm.reset();
	elements.itemId.value = "";
	elements.itemActive.checked = true;
	elements.itemFormTitle.textContent = "新增品項";
}

async function saveMapping(event) {
	event.preventDefault();
	const itemId = elements.mappingItem.value;
	if (!itemId) {
		showNotice("請先建立並選擇品項", true);
		return;
	}

	const payload = {
		specification: elements.mappingSpecification.value,
		unit: elements.mappingUnit.value,
		unitPrice: Number(elements.mappingPrice.value),
		remark: elements.mappingRemark.value,
		displayOrder: Number(elements.mappingOrder.value),
		calculationMode: elements.mappingMode.value,
		isCustomerVisible: elements.mappingVisible.checked,
		isActive: elements.mappingActive.checked,
	};

	try {
		// 外部 API：儲存目前格式與品項之間的固定資料關聯。
		await requestJson(
			`/api/admin/quotation-schemes/${encodeURIComponent(elements.schemeSelect.value)}/items/${itemId}`,
			{ method: "PUT", body: JSON.stringify(payload) },
		);
		showNotice("格式品項已儲存");
		elements.mappingForm.reset();
		elements.mappingVisible.checked = true;
		elements.mappingActive.checked = true;
		await loadMappings();
	}
	catch (error) {
		showNotice(error.message, true);
	}
}

function editMapping(mapping) {
	elements.mappingItem.value = String(mapping.itemId);
	elements.mappingSpecification.value = mapping.specification || "";
	elements.mappingUnit.value = mapping.unit;
	elements.mappingPrice.value = String(mapping.unitPrice);
	elements.mappingRemark.value = mapping.remark || "";
	elements.mappingOrder.value = String(mapping.displayOrder);
	elements.mappingMode.value = mapping.calculationMode;
	elements.mappingVisible.checked = mapping.isCustomerVisible;
	elements.mappingActive.checked = mapping.isActive;
	elements.mappingSpecification.focus();
}

async function parseQuotationInstruction(event) {
	event.preventDefault();
	elements.aiParseButton.disabled = true;
	elements.aiParseButton.textContent = "解析中…";

	try {
		// 外部 API：只傳送使用者輸入文字，不傳送資料庫價格或管理頁內容。
		const result = await requestJson(
			"/api/admin/quotation-ai-parse",
			{
				method: "POST",
				body: JSON.stringify({
					instruction: elements.aiInstruction.value,
					schemeCode: elements.schemeSelect.value,
				}),
			},
		);
		renderValidationResult(result);
		elements.validationJson.value = JSON.stringify(state.quotationSource, null, 2);
		showNotice("AI 解析完成，固定欄位已由資料庫補齊");
	}
	catch (error) {
		clearValidatedQuotation();
		showNotice(error.message, true);
	}
	finally {
		elements.aiParseButton.disabled = !state.aiConfigured;
		elements.aiParseButton.textContent = "讓 AI 解析";
	}
}

async function validateQuotationJson(event) {
	event.preventDefault();

	try {
		// 平台 API：先在瀏覽器解析 JSON，避免把明顯格式錯誤送到後端。
		const payload = JSON.parse(elements.validationJson.value);

		// 外部 API：由本機後端執行嚴格契約檢查及資料庫主檔解析。
		const result = await requestJson(
			`/api/admin/quotation-request-validation?schemeCode=${encodeURIComponent(elements.schemeSelect.value)}`,
			{ method: "POST", body: JSON.stringify(payload) },
		);
		renderValidationResult(result);
		showNotice("AI JSON 驗證通過");
	}
	catch (error) {
		clearValidatedQuotation();
		showNotice(error instanceof SyntaxError ? "JSON 格式不正確" : error.message, true);
	}
}

function renderValidationResult(result) {
	state.quotationSource = toQuotationSource(result);
	elements.validationResultName.textContent = result.headerPatch.workName?.value
		|| result.headerPatch.companyName?.value
		|| "未命名報價";
	elements.validationResultScheme.textContent = `報價格式：${result.schemeCode}`;
	elements.validationResultRows.replaceChildren();

	for (const item of result.standardItems) {
		const row = document.createElement("tr");
		row.append(
			createCell(item.itemName),
			createCell(item.specification || "—"),
			createCell(formatDecimal(item.quantity), "numeric"),
			createCell(item.unit),
			createCell(formatMoney(item.unitPrice), "numeric"),
			createCell(item.lineAmount === null ? "待計算規則" : formatMoney(item.lineAmount), "numeric"),
			createCell(item.remark || "—"),
		);
		elements.validationResultRows.append(row);
	}

	elements.validationResultImage.textContent = result.selectedImageMessageId || "未選取";
	elements.validationResultMissing.textContent = formatMissingFields(result) || "無";
	elements.validationResultWarnings.textContent = result.warnings.join("、") || "無";
	elements.validationResult.hidden = false;
}

function toQuotationSource(result) {
	return {
		schemaVersion: result.schemaVersion,
		schemeCode: result.schemeCode,
		schemeConfidence: result.schemeConfidence,
		headerPatch: result.headerPatch,
		standardItemIntents: result.standardItems.map(item => ({
			itemCode: item.itemCode,
			quantity: item.quantity,
			sourceText: item.sourceText,
			confidence: item.confidence,
		})),
		customItems: result.customItems,
		removedItemCodes: [...result.removedItemCodes],
		imageAssessments: result.imageAssessments.map(assessment => ({
			messageId: assessment.messageId,
			qualityScore: assessment.qualityScore,
			viewpointScore: assessment.viewpointScore,
			distinctivenessScore: assessment.distinctivenessScore,
			reason: assessment.reason,
		})),
		selectedImageMessageId: result.selectedImageMessageId,
		imageDeclined: result.imageDeclined,
		missingBaseFields: result.missingBaseFields,
		missingItemFields: result.missingItemFields,
		nextAction: result.nextAction,
		warnings: [...result.warnings],
	};
}

// 方法：把 2.0 基礎欄位與品項分組缺漏整理成單一可閱讀清單。
function formatMissingFields(result) {
	const baseFields = result.missingBaseFields.map(field => field.field);
	const itemFields = result.missingItemFields.flatMap(group =>
		group.fields.map(field => `${group.itemRef}.${field}`),
	);
	return [...baseFields, ...itemFields].join("、");
}

function clearValidatedQuotation() {
	state.quotationSource = null;
	elements.validationResult.hidden = true;
}

//#endregion

//#region 報價草稿管理

// 方法：依目前條件查詢第一頁草稿。
async function filterQuotationDrafts(event) {
	event.preventDefault();
	await loadQuotationDrafts(1);
}

// 方法：清除草稿條件並重新載入第一頁。
async function resetQuotationDraftFilters() {
	elements.quotationDraftFilterForm.reset();
	await loadQuotationDrafts(1);
}

// 方法：讀取上一頁草稿。
async function showPreviousQuotationDraftPage() {
	if (state.quotationDraftPage <= 1) return;

	await loadQuotationDrafts(state.quotationDraftPage - 1);
}

// 方法：讀取下一頁草稿。
async function showNextQuotationDraftPage() {
	if (state.quotationDraftPage >= state.quotationDraftTotalPages) return;

	await loadQuotationDrafts(state.quotationDraftPage + 1);
}

// 方法：呈現不含 LINE 擁有者及圖片路徑的草稿清單。
function renderQuotationDrafts(pagination) {
	elements.quotationDraftRows.replaceChildren();
	elements.quotationDraftEmpty.hidden = state.quotationDrafts.length > 0;
	elements.quotationDraftListSummary.textContent = `共 ${pagination.totalItems} 份草稿`;
	elements.quotationDraftPageStatus.textContent = pagination.totalPages === 0
		? "尚無資料"
		: `第 ${pagination.page}／${pagination.totalPages} 頁`;
	elements.quotationDraftPrevious.disabled = pagination.page <= 1;
	elements.quotationDraftNext.disabled = pagination.totalPages === 0
		|| pagination.page >= pagination.totalPages;

	for (const draft of state.quotationDrafts) {
		const row = document.createElement("tr");
		const nameCell = document.createElement("td");
		nameCell.append(
			createElement("strong", "", draft.quotationName || "報價名稱待補"),
			createElement("small", "cell-secondary", draft.companyName || "公司待補"),
		);
		const workCell = document.createElement("td");
		workCell.append(
			createElement("span", "", draft.workName || "工作待補"),
			createElement("small", "cell-secondary", draft.schemeCode || "格式待補"),
		);
		const statusCell = document.createElement("td");
		statusCell.append(createStatusChip(draft.status));
		const actionCell = document.createElement("td");
		const detailButton = createElement("button", "row-button", "查看");
		detailButton.type = "button";
		detailButton.addEventListener("click", () => loadQuotationDraftDetail(draft.id));
		actionCell.append(detailButton);
		row.append(
			nameCell,
			workCell,
			statusCell,
			createCell(formatDateTime(draft.updatedAt)),
			actionCell,
		);
		elements.quotationDraftRows.append(row);
	}
}

// 外部 API：讀取草稿完整抬頭、品項快照、缺漏與程式計算結果。
async function loadQuotationDraftDetail(draftId) {
	const draft = await requestJson(`/api/admin/quotation-drafts/${encodeURIComponent(draftId)}`);
	state.selectedQuotationDraft = draft;
	renderQuotationDraftDetail(draft);
}

// 方法：呈現草稿完整快照及本機同源選圖。
function renderQuotationDraftDetail(draft) {
	elements.quotationDraftDetailTitle.textContent = draft.quotationName || "報價草稿";
	elements.quotationDraftDetailStatus.className =
		`status-chip status-${String(draft.status).toLocaleLowerCase("en-US")}`;
	elements.quotationDraftDetailStatus.textContent = statusLabel(draft.status);
	elements.quotationDraftDetailSummary.replaceChildren();
	appendDefinition(elements.quotationDraftDetailSummary, "公司", draft.companyName || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "工作", draft.workName || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "聯絡人", draft.contactName || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "電話", draft.customerPhone || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "Email", draft.customerEmail || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "地點", draft.projectLocation || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "其他抬頭", draft.additionalHeader || "—");
	appendDefinition(elements.quotationDraftDetailSummary, "業務承辦", draft.salesRepresentative || "待使用者輸入");
	appendDefinition(elements.quotationDraftDetailSummary, "格式", draft.schemeCode || "—");
	let imageDecision = "尚待圖片決定";
	if (draft.selectedImageUrl !== null) imageDecision = "已選圖片";
	else if (draft.imageDeclined) imageDecision = "已明確拒絕";
	appendDefinition(elements.quotationDraftDetailSummary, "圖片決定", imageDecision);
	appendDefinition(
		elements.quotationDraftDetailSummary,
		"總計",
		draft.totalAmount === null ? "資料尚未完整" : `TWD ${formatMoney(draft.totalAmount)}`,
	);
	elements.quotationDraftMissing.textContent = draft.missingFields.length === 0
		? "欄位完整，可供正式確認。"
		: `尚缺：${draft.missingFields.join("、")}`;

	elements.quotationDraftImagePanel.hidden = draft.selectedImageUrl === null;
	if (draft.selectedImageUrl !== null) {
		elements.quotationDraftSelectedImage.src = draft.selectedImageUrl;
	}
	else {
		elements.quotationDraftSelectedImage.removeAttribute("src");
	}

	elements.quotationDraftDetailItems.replaceChildren();
	for (const item of draft.items) {
		const row = document.createElement("tr");
		if (item.removed) row.classList.add("removed-row");
		const itemCell = document.createElement("td");
		itemCell.append(
			createElement("span", "", item.itemName || item.itemCode || item.itemKind),
			createElement("small", "cell-secondary", item.specification || "—"),
		);
		row.append(
			itemCell,
			createCell(
				item.quantity === null ? `—／${item.unit || "—"}` : `${formatDecimal(item.quantity)}／${item.unit}`,
				"numeric",
			),
			createCell(item.unitPrice === null ? "—" : formatMoney(item.unitPrice), "numeric"),
			createCell(item.removed ? "已刪除" : item.remark || "—"),
		);
		elements.quotationDraftDetailItems.append(row);
	}
	elements.quotationDraftDetail.hidden = false;
}

//#endregion

//#region 正式報價管理

// 方法：依目前查詢條件回到第一頁。
async function filterQuotations(event) {
	event.preventDefault();
	await loadQuotations(1);
}

// 方法：清除正式報價查詢條件並重新載入第一頁。
async function resetQuotationFilters() {
	elements.quotationFilterForm.reset();
	await loadQuotations(1);
}

// 方法：讀取上一頁正式報價。
async function showPreviousQuotationPage() {
	if (state.quotationPage <= 1) return;

	await loadQuotations(state.quotationPage - 1);
}

// 方法：讀取下一頁正式報價。
async function showNextQuotationPage() {
	if (state.quotationPage >= state.quotationTotalPages) return;

	await loadQuotations(state.quotationPage + 1);
}

// 方法：呈現不含路徑、LINE 目的地及權杖的正式報價摘要。
function renderQuotations(pagination) {
	elements.quotationRows.replaceChildren();
	elements.quotationEmpty.hidden = state.quotations.length > 0;
	elements.quotationListSummary.textContent = `共 ${pagination.totalItems} 份正式報價`;
	elements.quotationPageStatus.textContent = pagination.totalPages === 0
		? "尚無資料"
		: `第 ${pagination.page}／${pagination.totalPages} 頁`;
	elements.quotationPrevious.disabled = pagination.page <= 1;
	elements.quotationNext.disabled = pagination.totalPages === 0 || pagination.page >= pagination.totalPages;

	for (const quotation of state.quotations) {
		const row = document.createElement("tr");
		const number = createElement("strong", "", quotation.quotationNumber);
		const date = createElement("small", "cell-secondary", quotation.quotationDate);
		const numberCell = document.createElement("td");
		numberCell.append(number, date);
		const customerCell = document.createElement("td");
		customerCell.append(
			createElement("span", "", quotation.companyName || "—"),
			createElement("small", "cell-secondary", quotation.workName || quotation.quotationName),
		);
		const statusCell = document.createElement("td");
		statusCell.append(createStatusChip(quotation.status));
		const deliveryCell = document.createElement("td");
		deliveryCell.append(
			createElement("span", "", `PDF：${statusLabel(quotation.pdfStatus)}`),
			createElement("small", "cell-secondary", `LINE：${statusLabel(quotation.lineStatus)}`),
		);
		const actionCell = document.createElement("td");
		const detailButton = createElement("button", "row-button", "查看");
		detailButton.type = "button";
		detailButton.addEventListener("click", () => loadQuotationDetail(quotation.id));
		actionCell.append(detailButton);
		row.append(
			numberCell,
			customerCell,
			createCell(quotation.schemeCode),
			createCell(`${quotation.currency} ${formatMoney(quotation.totalAmount)}`, "numeric"),
			statusCell,
			deliveryCell,
			actionCell,
		);
		elements.quotationRows.append(row);
	}
}

// 外部 API：讀取正式報價的客戶可見快照與可執行操作。
async function loadQuotationDetail(quotationId) {
	const quotation = await requestJson(`/api/admin/quotations/${encodeURIComponent(quotationId)}`);
	state.selectedQuotation = quotation;
	renderQuotationDetail(quotation);
}

// 方法：呈現正式報價詳情，不將敏感欄位放入 DOM。
function renderQuotationDetail(quotation) {
	elements.quotationDetailTitle.textContent = quotation.quotationNumber;
	elements.quotationDetailStatus.className =
		`status-chip status-${String(quotation.status).toLocaleLowerCase("en-US")}`;
	elements.quotationDetailStatus.textContent = statusLabel(quotation.status);
	elements.quotationDetailSummary.replaceChildren();
	appendSummary("報價名稱", quotation.quotationName);
	appendSummary("公司", quotation.companyName || "—");
	appendSummary("工作", quotation.workName || "—");
	appendSummary("業務承辦", quotation.salesRepresentative || "—");
	appendSummary("日期", quotation.quotationDate);
	appendSummary("格式", quotation.schemeCode);
	appendSummary("總計", `${quotation.currency} ${formatMoney(quotation.totalAmount)}`);
	elements.quotationImagePanel.hidden = quotation.selectedImageUrl === null;
	if (quotation.selectedImageUrl !== null) {
		elements.quotationSelectedImage.src = quotation.selectedImageUrl;
	}
	else {
		elements.quotationSelectedImage.removeAttribute("src");
	}

	elements.quotationDetailLines.replaceChildren();
	for (const line of quotation.lines) {
		const row = document.createElement("tr");
		row.append(
			createCell(line.itemName || line.itemCode || line.lineKind),
			createCell(line.quantity === null ? "—" : formatDecimal(line.quantity), "numeric"),
			createCell(line.lineAmount === null ? "—" : formatMoney(line.lineAmount), "numeric"),
		);
		elements.quotationDetailLines.append(row);
	}

	renderQuotationFiles(quotation);
	renderQuotationAudits(quotation.auditRecords);
	elements.quotationCopyLink.hidden = true;
	elements.quotationGeneratedLink.removeAttribute("href");
	elements.quotationRetryXlsx.disabled = !quotation.files.xlsx.canRetry;
	elements.quotationRetryPdf.disabled = !quotation.files.pdf.canRetry;
	elements.quotationRetryLine.disabled = !quotation.delivery.canRetry;
	elements.quotationRegenerateLink.disabled = !quotation.files.pdf.canDownload;
	elements.quotationRevokeLinks.disabled = !quotation.files.pdf.canDownload;
	elements.quotationOperationStatus.textContent = quotation.delivery.errorMessage || "";
	elements.quotationDetail.hidden = false;
}

// 方法：加入正式報價摘要欄位。
function appendSummary(label, value) {
	appendDefinition(elements.quotationDetailSummary, label, value);
}

// 方法：將一組標籤與值加入指定摘要清單。
function appendDefinition(container, label, value) {
	container.append(
		createElement("dt", "", label),
		createElement("dd", "", value),
	);
}

// 方法：呈現只含動作、結果與時間的稽核白名單欄位。
function renderQuotationAudits(records) {
	elements.quotationAuditRecords.replaceChildren();
	if (records.length === 0) {
		elements.quotationAuditRecords.append(createElement("li", "", "目前沒有稽核紀錄。"));
		return;
	}

	for (const record of records) {
		const item = document.createElement("li");
		item.append(
			createElement("strong", "", auditActionLabel(record.action)),
			createElement("span", "cell-secondary", `${record.outcome || "—"} · ${formatDateTime(record.createdAt)}`),
		);
		elements.quotationAuditRecords.append(item);
	}
}

// 方法：將固定稽核動作代碼轉成管理者可讀文字。
function auditActionLabel(action) {
	const labels = {
		QUOTATION_CONFIRMED: "正式確認",
		QUOTATION_CREATED: "正式報價建立",
		QUOTATION_FILE_XLSX: "Excel 檔案",
		QUOTATION_FILE_PDF: "PDF 檔案",
		QUOTATION_GENERATION: "報價生成",
		QUOTATION_DELIVERY_PREVIEW: "預覽傳送",
		QUOTATION_DELIVERY_FINAL: "正式傳送",
		QUOTATION_XLSX_RETRY: "重試 Excel",
		QUOTATION_PDF_RETRY: "重試 PDF",
		QUOTATION_LINE_RETRY: "重送 LINE",
		QUOTATION_FILE_DOWNLOAD_XLSX: "下載 Excel",
		QUOTATION_FILE_DOWNLOAD_PDF: "下載 PDF",
		QUOTATION_PDF_LINK_REGENERATE: "產生 PDF 連結",
		QUOTATION_PDF_LINKS_REVOKE: "撤銷 PDF 連結",
	};
	return labels[action] || action;
}

// 方法：只依後端允許的固定檔案種類建立同源下載連結。
function renderQuotationFiles(quotation) {
	elements.quotationFileActions.replaceChildren();
	for (const fileKind of ["xlsx", "pdf"]) {
		const file = quotation.files[fileKind];
		if (!file.canDownload) {
			elements.quotationFileActions.append(
				createElement("span", "file-state", `${fileKind.toUpperCase()}：${statusLabel(file.status)}`),
			);
			continue;
		}

		const link = createElement("a", "export-link", `下載 ${fileKind.toUpperCase()}`);
		link.href = `/api/admin/quotations/${encodeURIComponent(quotation.id)}/files/${fileKind}`;
		link.download = "";
		elements.quotationFileActions.append(link);
	}
}

// 方法：以同一份正式快照、流水號及資料夾重新排程 Excel。
async function retryQuotationXlsx() {
	const quotation = selectedQuotation();
	if (!window.confirm(`確定以原單號 ${quotation.quotationNumber} 重試 Excel？`)) return;

	await performQuotationAction(
		`/api/admin/quotations/${quotation.id}/xlsx-retries`,
		"Excel 已重新排入背景工作。",
	);
}

// 方法：以同一份正式報價及單號重試 PDF。
async function retryQuotationPdf() {
	const quotation = selectedQuotation();
	if (!window.confirm(`確定以原單號 ${quotation.quotationNumber} 重試 PDF？`)) return;

	await performQuotationAction(
		`/api/admin/quotations/${quotation.id}/pdf-retries`,
		"PDF 已重新產生。",
	);
}

// 方法：沿用資料庫既有目的地重新傳送 LINE，不讓畫面輸入目的地。
async function retryQuotationLine() {
	const quotation = selectedQuotation();
	if (!window.confirm(`確定重新傳送 ${quotation.quotationNumber} 到原 LINE 對話？`)) return;

	await performQuotationAction(
		`/api/admin/quotations/${quotation.id}/line-retries`,
		"LINE 已重新傳送。",
	);
}

// 方法：為已完成 PDF 建立新的限時 HTTPS 連結並提供開啟及複製操作。
async function regenerateQuotationLink() {
	const quotation = selectedQuotation();
	if (!window.confirm(`確定為 ${quotation.quotationNumber} 重新產生 PDF 限時連結？`)) return;

	const result = await performQuotationAction(
		`/api/admin/quotations/${quotation.id}/download-links`,
		"PDF 限時連結已重新產生。",
	);
	if (result === null) return;

	elements.quotationGeneratedLink.href = result.url;
	elements.quotationCopyLink.hidden = false;
	elements.quotationOperationStatus.textContent = `新連結有效至 ${formatDateTime(result.expiresAt)}`;
}

// 方法：使用瀏覽器剪貼簿 API 複製目前畫面上的短效 PDF URL。
async function copyQuotationLink() {
	const url = elements.quotationGeneratedLink.href;
	if (!url) return;

	try {
		// 外部 API：只將後端剛簽發的 HTTPS URL 寫入使用者剪貼簿。
		await navigator.clipboard.writeText(url);
		showNotice("PDF 連結已複製。");
	}
	catch (error) {
		elements.quotationOperationStatus.textContent = "瀏覽器無法自動複製，請使用『開啟新 PDF 連結』後複製網址。";
	}
}

// 方法：撤銷正式報價現有的全部 PDF 限時連結。
async function revokeQuotationLinks() {
	const quotation = selectedQuotation();
	if (!window.confirm(`撤銷 ${quotation.quotationNumber} 的全部 PDF 連結？既有連結將立即失效。`)) return;

	await performQuotationAction(
		`/api/admin/quotations/${quotation.id}/download-links/revocation`,
		"PDF 連結已全部撤銷。",
	);
}

// 外部 API：執行受狀態限制的正式報價操作並同步清單與詳情。
async function performQuotationAction(url, successMessage) {
	setQuotationActionsDisabled(true);
	try {
		const result = await requestJson(url, { method: "POST" });
		showNotice(successMessage);
		await Promise.all([
			loadQuotations(),
			loadQuotationDetail(state.selectedQuotation.id),
		]);
		return result;
	}
	catch (error) {
		if (state.selectedQuotation !== null) renderQuotationDetail(state.selectedQuotation);

		elements.quotationOperationStatus.textContent = error.message;
		showNotice(error.message, true);
		return null;
	}
}

// 方法：操作期間停用按鈕，避免瀏覽器送出重複請求。
function setQuotationActionsDisabled(disabled) {
	for (const button of [
		elements.quotationRetryXlsx,
		elements.quotationRetryPdf,
		elements.quotationRetryLine,
		elements.quotationRegenerateLink,
		elements.quotationRevokeLinks,
	]) {
		button.disabled = disabled;
	}
}

// 方法：取得目前選取的正式報價。
function selectedQuotation() {
	if (state.selectedQuotation === null) throw new Error("請先選擇正式報價");

	return state.selectedQuotation;
}

// 方法：建立含可讀文字及固定狀態類別的狀態標記。
function createStatusChip(status) {
	return createElement(
		"span",
		`status-chip status-${String(status || "unknown").toLocaleLowerCase("en-US")}`,
		statusLabel(status),
	);
}

// 方法：將固定狀態代碼轉為管理者可讀文字。
function statusLabel(status) {
	const labels = {
		COLLECTING_BASE_INFO: "補充頂部資料",
		COLLECTING_ITEMS: "補充品項",
		AWAITING_IMAGE: "等待圖片",
		READY_FOR_PREVIEW: "可預覽",
		AWAITING_CONFIRMATION: "等待確認",
		EXPIRED: "已逾期",
		CONFIRMED: "已確認",
		GENERATING_EXCEL: "產生 Excel 中",
		GENERATING_PDF: "產生 PDF 中",
		PDF_FAILED: "PDF 失敗",
		READY: "可交付",
		SENDING: "傳送中",
		SENT: "已傳送",
		CANCELLED: "已取消",
		FAILED: "失敗",
	};
	return labels[status] || status || "尚無紀錄";
}

// 方法：以台灣地區格式呈現 ISO 時間。
function formatDateTime(value) {
	return new Intl.DateTimeFormat("zh-TW", {
		dateStyle: "medium",
		timeStyle: "short",
	}).format(new Date(value));
}

//#endregion

//#region 共用工具

// 方法：把使用者在 Excel 編輯後的主檔 CSV 整批上傳覆蓋。
async function importMasterDataCsv(event) {
	const file = event.target.files?.[0];
	if (!file) return;

	try {
		// 外部 API：以原始 CSV 內容呼叫本機管理 API，由後端在單一交易內覆蓋主檔。
		const response = await fetch("/api/admin/quotation-master-data.csv", {
			method: "POST",
			headers: { "Content-Type": "text/csv", "X-Local-Admin-Request": "1" },
			body: await file.arrayBuffer(),
		});
		const payload = await response.json();
		if (!response.ok) throw new Error(payload.error?.message || "主檔匯入失敗");

		await loadItems();
		await loadMappings();
		showNotice(
			`主檔已覆蓋：新增 ${payload.createdItems} 項、更新 ${payload.updatedItems} 項、格式品項 ${payload.schemeItems} 筆`,
		);
	}
	catch (error) {
		showNotice(error.message, true);
	}
	finally {
		event.target.value = "";
	}
}

async function requestJson(url, options = {}) {
	// 外部 API：只向同源本機管理 API 發送 JSON，避免跨站資料流動。
	const method = String(options.method || "GET").toUpperCase();
	const headers = { "Content-Type": "application/json", ...options.headers };
	if (!["GET", "HEAD", "OPTIONS"].includes(method)) headers["X-Local-Admin-Request"] = "1";

	const response = await fetch(url, {
		...options,
		headers,
	});
	const payload = await response.json();
	if (!response.ok) throw new Error(payload.error?.message || "操作失敗");

	return payload;
}

function formatMoney(value) {
	return new Intl.NumberFormat("zh-TW", { maximumFractionDigits: 2 }).format(value);
}

function formatDecimal(value) {
	return new Intl.NumberFormat("zh-TW", { maximumFractionDigits: 6 }).format(value);
}

function showNotice(message, isError = false) {
	elements.notice.textContent = message;
	elements.notice.classList.toggle("error", isError);
	elements.notice.hidden = false;
	window.clearTimeout(showNotice.timeoutId);
	showNotice.timeoutId = window.setTimeout(() => {
		elements.notice.hidden = true;
	}, 3500);
}

//#endregion

initialize().catch(error => showNotice(error.message, true));
