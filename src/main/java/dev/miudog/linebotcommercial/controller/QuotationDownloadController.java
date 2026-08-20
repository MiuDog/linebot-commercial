package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationDownloadResource;
import dev.miudog.linebotcommercial.service.quotation.QuotationDownloadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 將限時權杖解析成 LINE 使用者可下載的 PDF 附件。 */
@RestController
public class QuotationDownloadController {

	private final QuotationDownloadService service;

	// 方法：建立報價 PDF 安全下載控制器。
	public QuotationDownloadController(QuotationDownloadService service) {
		this.service = service;
	}

	// 方法：下載有效權杖對應的 PDF，無效狀態統一回傳 404。
	@GetMapping("/quotation-downloads/{token}")
	public ResponseEntity<Resource> download(@PathVariable String token) {
		QuotationDownloadResource resource = service.resolvePdf(token);
		// 外部呼叫：使用 Spring HTTP API 將所有無效權杖狀態統一表達為 404。
		if (resource == null) return ResponseEntity.notFound().build();

		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(resource.fileName(), StandardCharsets.UTF_8)
			.build();
		// 外部呼叫：使用 Spring HTTP API 回傳禁止快取的 PDF 附件。
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(resource.contentType()))
			.contentLength(resource.fileSize())
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.body(new FileSystemResource(resource.path()));
	}
}
