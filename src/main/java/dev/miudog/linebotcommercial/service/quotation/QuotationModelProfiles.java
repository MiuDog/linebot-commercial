package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.ai.AiExtractionService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 解析角色設定並維持端點與憑證邊界；不改寫舊的單模型設定。 */
@Component
public class QuotationModelProfiles {

	private final Environment environment;
	private final AiExtractionService transport;

	// 方法：延後到使用 workflow 時驗證選用設定，避免影響未啟用的既有部署。
	public QuotationModelProfiles(Environment environment, AiExtractionService transport) {
		this.environment = environment;
		this.transport = transport;
	}

	// 方法：相容開關預設關閉，舊部署不會自動改變模型或呼叫次數。
	public boolean enabled() {
		return environment.getProperty("app.ai.workflow.enabled", Boolean.class, false);
	}

	// 方法：取得有上下界的整輪資源配額。
	public int limit(String name, int fallback, int minimum, int maximum) {
		int value = environment.getProperty("app.ai.workflow." + name, Integer.class, fallback);
		if (value < minimum || value > maximum) throw new QuotationAiException("AI_WORKFLOW_CONFIG", "Workflow 配額超出允許範圍：" + name);

		return value;
	}

	// 方法：取得角色專屬端點，只有端點完全相同時才可繼承基底金鑰。
	public Profile resolve(String role) {
		String prefix = "app.ai.workflow." + role.toLowerCase(java.util.Locale.ROOT) + ".";
		String baseUrl = value("app.ai.api-url");
		String url = fallback(value(prefix + "api-url"), baseUrl);
		String key = value(prefix + "api-key");
		if (key.isBlank() && url.equals(baseUrl)) key = value("app.ai.api-key");
		String model = fallback(value(prefix + "model"), value("app.ai.model"));
		int tokens = environment.getProperty(prefix + "max-completion-tokens", Integer.class,
			environment.getProperty("app.ai.max-completion-tokens", Integer.class, 4000));
		int timeout = environment.getProperty(prefix + "timeout-seconds", Integer.class, 60);
		if (url.isBlank() || key.isBlank() || model.isBlank() || tokens < 256 || tokens > 32000 || timeout < 1 || timeout > 300) {
			throw new QuotationAiException("AI_WORKFLOW_CONFIG", "請檢查 " + role + " 模型、端點、獨立金鑰與配額");
		}
		return new Profile(role, url, key, model, tokens, timeout);
	}

	// 方法：建立本次呼叫專用實例，預算縮減不會污染其他請求。
	public AiExtractionService client(Profile profile, int tokens, int seconds) {
		return transport.profile(profile.url(), profile.key(), profile.model(), tokens, seconds);
	}

	// 方法：將未設定與純空白設定統一處理。
	private String value(String name) {
		return environment.getProperty(name, "").trim();
	}

	// 方法：只在角色未指定時採用舊設定。
	private String fallback(String value, String fallback) {
		return value.isBlank() ? fallback : value;
	}

	public record Profile(String role, String url, String key, String model, int tokens, int timeout) {
		// 方法：避免 record 預設字串表示洩漏憑證或內部端點。
		@Override
		public String toString() {
			return "ModelProfile[role=" + role + "]";
		}
	}
}
