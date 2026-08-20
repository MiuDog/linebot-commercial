package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationAdminException;
import dev.miudog.linebotcommercial.service.quotation.QuotationAiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 將管理 API 的例外統一轉成不洩漏內部細節的錯誤格式。
 */
@RestControllerAdvice(
	assignableTypes = {
		QuotationAdminController.class,
		QuotationManagementController.class
	}
)
public class QuotationAdminExceptionHandler {

	// 方法：將模型設定或解析錯誤轉為穩定且不含敏感資料的管理 API 回應。
	@ExceptionHandler(QuotationAiException.class)
	public ResponseEntity<ApiErrorResponse> handleAiException(QuotationAiException exception) {
		return ResponseEntity.unprocessableEntity()
			.body(ApiErrorResponse.of("AI_PARSE_ERROR", exception.getMessage()));
	}

	// 方法：將已分類的管理功能例外轉成固定錯誤格式。
	@ExceptionHandler(QuotationAdminException.class)
	public ResponseEntity<ApiErrorResponse> handleAdminException(QuotationAdminException exception) {
		HttpStatus status = switch (exception.code()) {
			case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
			case "CONFIRMATION_REQUIRED", "CONFLICT" -> HttpStatus.CONFLICT;
			default -> HttpStatus.UNPROCESSABLE_ENTITY;
		};
		return ResponseEntity.status(status).body(ApiErrorResponse.of(exception.code(), exception.getMessage()));
	}

	// 方法：將重複代碼或唯一值衝突轉成可理解的回應。
	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<ApiErrorResponse> handleDuplicateKey(DuplicateKeyException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiErrorResponse.of("CONFLICT", "品項代碼或關聯資料已存在"));
	}

	// 方法：將無法解析的 JSON 請求轉成資料驗證錯誤。
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
		return ResponseEntity.unprocessableEntity()
			.body(ApiErrorResponse.of("VALIDATION_ERROR", "JSON 格式或欄位型別不正確"));
	}

	public record ApiErrorResponse(ApiError error) {

		// 方法：建立固定結構的 API 錯誤回應。
		private static ApiErrorResponse of(String code, String message) {
			return new ApiErrorResponse(new ApiError(code, message));
		}
	}

	public record ApiError(String code, String message) {}
}
