package dev.miudog.linebotcommercial.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerOperationsContractTest {

	// 方法：客戶可從單一入口完成設定與日常操作，不必直接輸入 Docker 指令。
	@Test
	void providesOneCustomerFacingControlEntryPoint() throws IOException {
		String launcher = read("linebot.cmd");
		String console = read("scripts/customer-console.ps1");
		String environment = read(".env.example");
		String readme = read("README.md");
		String compose = read("docker-compose.yml");

		assertThat(launcher)
			.contains("customer-console.ps1")
			.contains("-ExecutionPolicy Bypass");
		assertThat(console)
			.contains("Setup")
			.contains("Start")
			.contains("Status")
			.contains("Diagnose")
			.contains("Logs")
			.contains("Stop");
		assertThat(environment)
			.contains("TUNNEL_ENABLED=false")
			.contains("CLOUDFLARED_PROTOCOL=http2");
		assertThat(compose).contains("--protocol", "${CLOUDFLARED_PROTOCOL:-http2}");
		assertThat(readme)
			.contains("雙擊 `linebot.cmd`")
			.contains("連線與環境診斷")
			.contains("不會刪除資料卷");
	}

	// 方法：啟動前必須驗證 Docker、非機密設定與 Secret，避免建立半套容器。
	@Test
	void blocksStartupBeforeDockerMutationWhenConfigurationIsInvalid() throws IOException {
		String console = read("scripts/customer-console.ps1");

		assertThat(console)
			.contains("Assert-DockerReady")
			.contains("Test-CustomerConfiguration")
			.contains("Get-RequiredSecretDefinitions")
			.contains("Invoke-ComposeUp")
			.contains("$script:OperationExitCode = 2");
		assertThat(console.indexOf("$validation = Test-CustomerConfiguration"))
			.isLessThan(console.indexOf("if (-not (Assert-DockerReady))"));
		assertThat(console)
			.contains("Test-CustomerLocalPortAvailable")
			.contains("本機連接埠 $port 已被另一個程式占用")
			.contains("$script:OperationExitCode = 6")
			.contains("[意外錯誤] 操作未完成")
			.contains("不要提供 Secret");
	}

	// 方法：診斷結果必須列出階段、原因與客戶可執行的解法。
	@Test
	void explainsEveryDiagnosticFailureWithAnActionableResolution() throws IOException {
		String console = read("scripts/customer-console.ps1");

		assertThat(console)
			.contains("Docker 引擎")
			.contains("本機服務")
			.contains("Cloudflare Tunnel 容器")
			.contains("Cloudflare 邊緣連線")
			.contains("region1.v2.argotunnel.com")
			.contains("7844")
			.contains("公開網域 DNS")
			.contains("公開網域 HTTPS")
			.contains("請先修正上一階段的 DNS")
			.contains("$public.StatusCode -eq 404")
			.contains("$public.StatusCode -eq 502")
			.contains("$public.StatusCode -eq 403")
			.contains("LINE API")
			.contains("$line.StatusCode -eq 429")
			.contains("$null -ne $_.Exception.Response")
			.contains("$script:OperationExitCode = 3")
			.contains("原因：")
			.contains("解法：")
			.doesNotContain("Write-Host $secret");
	}

	// 方法：客戶控制台只使用 Windows 內建 PowerShell 5.1 可用的密碼學 API。
	@Test
	void supportsBuiltInWindowsPowerShellCryptography() throws IOException {
		String console = read("scripts/customer-console.ps1");

		assertThat(console)
			.contains("RandomNumberGenerator]::Create()")
			.contains("GetBytes($bytes)")
			.doesNotContain("RandomNumberGenerator]::Fill")
			.doesNotContain("Convert]::ToHexString");
	}

	// 方法：Windows 控制台接受編輯器慣用的單一尾端換行，並以成功碼回報有效設定。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void acceptsValidCustomerConfigurationWithTrailingSecretNewlines(@TempDir Path projectRoot) throws Exception {
		writeValidFixture(projectRoot);

		assertThat(runValidation(projectRoot)).isZero();
	}

	// 方法：Windows 控制台在設定缺漏時回傳非零，供捷徑與維運工具可靠判斷。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void returnsFailureExitCodeWhenCustomerConfigurationIsMissing(@TempDir Path projectRoot) throws Exception {
		assertThat(runAction(projectRoot, "Validate")).isEqualTo(2);
	}

	// 方法：啟動遇到設定缺漏時先回報設定錯誤，不依賴 Docker 是否已啟動。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void blocksStartWithConfigurationExitCodeBeforeCheckingDocker(@TempDir Path projectRoot) throws Exception {
		assertThat(runAction(projectRoot, "Start")).isEqualTo(2);
	}

	// 方法：設定精靈遇到不完整安裝資料夾時提供可執行解法，不顯示底層例外。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void explainsMissingEnvironmentTemplateAsIncompletePackage(@TempDir Path projectRoot) throws Exception {
		assertThat(runAction(projectRoot, "Setup")).isEqualTo(7);
	}

	// 方法：建立不含真實憑證的有效商用機客戶設定樣本。
	private void writeValidFixture(Path projectRoot) throws IOException {
		String environment = """
			COMPOSE_PROJECT_NAME=linebot-commercial-customer-test
			APP_PORT=18088
			COMPANY_ID=customer-commercial
			PUBLIC_BASE_URL=https://commercial.customer.test
			OBJECT_STORAGE_BUCKET=customer-commercial-assets
			AI_API_URL=
			AI_MODEL=
			TUNNEL_ENABLED=false
			""";
		Path secretDirectory = projectRoot.resolve("secrets");

		// 檔案 API：建立客戶設定與具一般編輯器尾端換行的測試 Secret。
		Files.createDirectories(secretDirectory);
		Files.writeString(projectRoot.resolve(".env"), environment, StandardCharsets.UTF_8);
		writeSecret(secretDirectory, "database-password", "0123456789abcdef0123456789abcdef");
		writeSecret(secretDirectory, "object-storage-access-key", "customeraccess");
		writeSecret(secretDirectory, "object-storage-secret-key", "0123456789abcdef0123456789abcdef");
		writeSecret(secretDirectory, "line-channel-token", "test-line-token-not-for-production");
		writeSecret(secretDirectory, "line-channel-secret", "test-line-secret-not-for-production");
		writeSecret(secretDirectory, "admin-token", "0123456789abcdef0123456789abcdef");
		writeSecret(secretDirectory, "ai-api-key", "");
		writeSecret(secretDirectory, "quotation-postback-secret", "0123456789abcdef0123456789abcdef");
		writeSecret(secretDirectory, "quotation-image-link-secret", "0123456789abcdef0123456789abcdef");
	}

	// 方法：保存具單一尾端換行的測試 Secret。
	private void writeSecret(Path directory, String name, String value) throws IOException {
		Files.writeString(directory.resolve(name), value + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	// 方法：以客戶實際使用的 Windows PowerShell 執行驗證並回傳程序狀態碼。
	private int runValidation(Path projectRoot) throws Exception {
		return runAction(projectRoot, "Validate");
	}

	// 方法：以指定操作執行客戶控制台並回傳程序狀態碼。
	private int runAction(Path projectRoot, String action) throws Exception {
		Path script = Path.of("scripts/customer-console.ps1").toAbsolutePath();
		ProcessBuilder builder = new ProcessBuilder(
			"powershell.exe",
			"-NoProfile",
			"-ExecutionPolicy",
			"Bypass",
			"-File",
			script.toString(),
			"-Action",
			action,
			"-ProjectRootOverride",
			projectRoot.toString()
		);
		builder.redirectErrorStream(true);

		// 外部程序 API：執行客戶控制台並排空輸出，避免子程序因緩衝區阻塞。
		Process process = builder.start();
		process.getInputStream().transferTo(OutputStream.nullOutputStream());
		return process.waitFor();
	}

	// 方法：以 UTF-8 讀取客戶操作檔案。
	private String read(String path) throws IOException {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
