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

	// 方法：含中文的腳本必須附 UTF-8 BOM，避免 Windows PowerShell 5.1 以 ANSI 解碼。
	@Test
	void marksUnicodePowerShellScriptsWithUtf8Bom() throws IOException {
		try (var scripts = Files.list(Path.of("scripts"))) {
			for (Path script : scripts.filter(path -> path.toString().endsWith(".ps1")).toList()) {
				byte[] bytes = Files.readAllBytes(script);
				if (new String(bytes, StandardCharsets.UTF_8).chars().anyMatch(character -> character > 127)) {
					assertThat(bytes).as(script.toString()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
				}
			}
		}
	}

	// 方法：用實際 Windows PowerShell 執行首次設定兩次，確認可啟動且不覆寫密碼。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void preparesSettingsWithWindowsPowerShellAndPreservesExistingSecrets(@TempDir Path projectRoot) throws Exception {
		Path scripts = Files.createDirectories(projectRoot.resolve("scripts"));
		Path setup = scripts.resolve("prepare-local-settings.ps1");
		Files.copy(Path.of("scripts/prepare-local-settings.ps1"), setup);
		Files.copy(Path.of(".env.example"), projectRoot.resolve(".env.example"));
		Path secret = projectRoot.resolve("secrets/database-password");
		String password = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", setup.toString())
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
			if (!finished) process.destroyForcibly();
			assertThat(finished).isTrue();
			assertThat(process.exitValue()).isZero();
			String actual = Files.readString(secret);
			assertThat(actual).matches("[0-9a-f]{64}");
			if (password != null) assertThat(actual).isEqualTo(password);
			password = actual;
		}
	}

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

	// 方法：基底與各角色誤填官網時均須阻擋，並先確認其他設定有效。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void rejectsWebsiteUrlsForBaseAndWorkflowRoles(@TempDir Path projectRoot) throws Exception {
		for (String setting : java.util.List.of("AI_API_URL", "AI_TEXT_API_URL", "AI_VISION_API_URL", "AI_ESCALATION_API_URL")) {
			writeValidFixture(projectRoot);
			writeSecret(projectRoot.resolve("secrets"), "ai-api-key", "test-key-not-for-production");
			for (String role : java.util.List.of("text", "vision", "escalation")) {
				writeSecret(projectRoot.resolve("secrets"), "ai-" + role + "-api-key", "test-role-key-not-for-production");
			}
			Files.writeString(projectRoot.resolve(".env"), "\nAI_API_URL=https://api.openai.com/v1\nAI_MODEL=test-model\nAI_WORKFLOW_ENABLED=true\n",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
			assertThat(runValidation(projectRoot)).isZero();
			Files.writeString(projectRoot.resolve(".env"), setting + "=https://openai.com\n",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
			assertThat(runValidation(projectRoot)).isEqualTo(2);
		}
	}

	// 方法：官方 API 端點與不同模型設定可通過本機格式驗證，不代表遠端權限已驗證。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void acceptsExplicitWorkflowModels(@TempDir Path projectRoot) throws Exception {
		writeValidFixture(projectRoot);
		writeSecret(projectRoot.resolve("secrets"), "ai-api-key", "test-key-not-for-production");
		Files.writeString(projectRoot.resolve(".env"), "\nAI_API_URL=https://api.openai.com/v1\nAI_MODEL=legacy\nAI_WORKFLOW_ENABLED=true\nAI_TEXT_MODEL=text\nAI_VISION_MODEL=vision\nAI_ESCALATION_MODEL=escalation\n",
			StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
		assertThat(runValidation(projectRoot)).isZero();
	}

	// 方法：缺少設定時回傳失敗碼。
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

	// 方法：以假輸入驅動實際精靈，驗證確認選項、寫入與保留舊值；不使用真實憑證。
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void wizardReplacesKeyAfterYesAndPreservesItOnInvalidPaste(@TempDir Path projectRoot) throws Exception {
		for (String scenario : java.util.List.of("yes", "padded-yes", "empty-paste", "start", "start-failure")) {
			writeValidFixture(projectRoot);
			Path keyPath = projectRoot.resolve("secrets/ai-api-key");
			Files.writeString(keyPath, "old-test-key-not-real");
			Files.writeString(projectRoot.resolve(".env"), "\nAI_API_URL=https://api.openai.com/v1\nAI_MODEL=test-model\n",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
			String runner = """
				param([string]$Source, [string]$Fixture, [string]$Scenario)
				$ErrorActionPreference = 'Stop'
				$text = [IO.File]::ReadAllText($Source)
				$cut = $text.LastIndexOf('# 外部主流程')
				$library = Join-Path $Fixture 'console-library.ps1'
				[IO.File]::WriteAllText($library, $text.Substring(0, $cut), [Text.UTF8Encoding]::new($true))
				. $library -Action Setup -ProjectRootOverride $Fixture
				if ($Scenario -like 'start*') {
					$script:commands = @()
					function docker {
						$script:commands += ($args -join ' ')
						$global:LASTEXITCODE = 0
						if ($Scenario -eq 'start-failure' -and $args -contains '--force-recreate') { $global:LASTEXITCODE = 1 }
					}
					$started = Invoke-ComposeUp $false
					if ($script:commands.Count -ne 2 -or $script:commands[1] -ne 'compose up -d --no-deps --no-build --force-recreate --wait app') { exit 12 }
					if ($started -ne ($Scenario -eq 'start')) { exit 13 }
					exit 0
				}
				function Read-Host {
					param([string]$Prompt, [switch]$AsSecureString)
					if ($AsSecureString) {
						$paste = 'new-test-key-not-real'
						if ($Scenario -eq 'empty-paste') { $paste = ' ' }
						$secret = [Security.SecureString]::new()
						foreach ($character in $paste.ToCharArray()) { $secret.AppendChar($character) }
						return $secret
					}
					if ($Prompt -like 'AI API Key 已設定*') {
						if ($Scenario -eq 'padded-yes') { return ' y ' }
						return 'y'
					}
					return ''
				}
				Invoke-CustomerSetup
				$expectedExit = 0
				if ($Scenario -eq 'empty-paste') { $expectedExit = 2 }
				if ($script:OperationExitCode -ne $expectedExit) { exit 14 }
				""";
			Path runnerPath = projectRoot.resolve("wizard-test.ps1");
			Files.writeString(runnerPath, "\uFEFF" + runner, StandardCharsets.UTF_8);
			Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
				runnerPath.toString(), "-Source", Path.of("scripts/customer-console.ps1").toAbsolutePath().toString(),
				"-Fixture", projectRoot.toString(), "-Scenario", scenario)
				.redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as(output).isZero();
			assertThat(Files.readString(keyPath)).as(scenario)
				.isEqualTo(scenario.equals("empty-paste") || scenario.startsWith("start") ? "old-test-key-not-real" : "new-test-key-not-real");
			assertThat(Files.readString(projectRoot.resolve("secrets/database-password")).trim()).isEqualTo("0123456789abcdef0123456789abcdef");
		}
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
