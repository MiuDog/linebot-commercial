package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationLineWorkflowException;
import dev.miudog.linebotcommercial.service.quotation.QuotationPendingImagePreviewService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/quotation-pending-images")
public class QuotationPendingImageController {

	private final QuotationPendingImagePreviewService previews;

	// 方法：初始化候選圖片公開讀取端點。
	public QuotationPendingImageController(QuotationPendingImagePreviewService previews) {
		this.previews = previews;
	}

	// 方法：以短效簽章 token 回傳候選原圖或縮圖。
	@GetMapping("/{token}")
	public ResponseEntity<byte[]> image(@PathVariable String token) {
		try {
			QuotationPendingImagePreviewService.ImageResource resource = previews.resolve(token);
			return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(resource.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
				.body(resource.bytes());
		}
		catch (QuotationLineWorkflowException exception) {
			return ResponseEntity.notFound().build();
		}
	}
}
