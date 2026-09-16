package dev.miudog.linebotcommercial.config.runtime;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3 相容物件儲存設定；端點留空時使用雲端供應商的預設端點。
 */
@Validated
@ConfigurationProperties("app.object-storage")
public record ObjectStorageProperties(
	String endpoint,
	@NotBlank String region,
	@NotBlank String bucket,
	String accessKey,
	String secretKey,
	boolean forcePathStyle
) {
}
