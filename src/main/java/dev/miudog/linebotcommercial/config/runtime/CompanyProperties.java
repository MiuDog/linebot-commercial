package dev.miudog.linebotcommercial.config.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 每一套部署唯一對應的公司身分。
 */
@Validated
@ConfigurationProperties("app.company")
public record CompanyProperties(
	@NotBlank
	@Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}")
	String id
) {
}
