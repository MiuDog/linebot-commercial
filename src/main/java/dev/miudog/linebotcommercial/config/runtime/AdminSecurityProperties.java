package dev.miudog.linebotcommercial.config.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 私有管理介面的驗證設定。
 */
@Validated
@ConfigurationProperties("app.admin")
public record AdminSecurityProperties(
	@NotBlank
	@Size(min = 32)
	String token,
	boolean tokenRequired
) {
}
