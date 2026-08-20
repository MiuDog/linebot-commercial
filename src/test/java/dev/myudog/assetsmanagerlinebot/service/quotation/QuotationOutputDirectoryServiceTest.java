package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationOutputDirectoryServiceTest {

	@TempDir
	Path root;

	@Test
	void createsDirectoryUnderQuotationFolderUsingTheQuotationName() throws Exception {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		Path directory = service.createDirectory("台中港電器設備");

		assertThat(directory).isEqualTo(root.resolve("報價單").resolve("台中港電器設備").toAbsolutePath().normalize());
		assertThat(Files.isDirectory(directory)).isTrue();
	}

	@Test
	void sanitizesUnsafeCharactersAndNeverEscapesTheConfiguredRoot() throws Exception {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		Path directory = service.createDirectory("../客戶:A");
		Path quotationRoot = root.resolve("報價單").toAbsolutePath().normalize();

		assertThat(directory).startsWith(quotationRoot);
		assertThat(directory.getParent()).isEqualTo(quotationRoot);
		assertThat(directory.getFileName().toString()).doesNotContain("..", "/", "\\", ":");
	}

	@Test
	void rejectsBlankQuotationNames() {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		assertThatThrownBy(() -> service.createDirectory("  "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("名稱");
	}

	@Test
	void prefixesWindowsReservedNamesEvenWhenTheyHaveAnExtension() throws Exception {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		Path directory = service.createDirectory("CON.txt");

		assertThat(directory.getFileName().toString()).isEqualTo("_CON.txt");
	}

	// 驗證正式報價只接受系統配置的日期流水號資料夾，且檔名保留既定格式。
	@Test
	void resolvesFormalQuotationFileInsideTheAllocatedSequenceFolder() throws Exception {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		Path file = service.resolveFormalFile(
			"20260811-03",
			"正定工程-台中港案 20260811-03",
			".xlsx"
		);

		assertThat(file).isEqualTo(
			root.resolve("報價單/20260811-03/正定工程-台中港案 20260811-03.xlsx")
				.toAbsolutePath()
				.normalize()
		);
		assertThat(file.getParent()).isDirectory();
	}

	@Test
	void acceptsNaturallyExpandedThreeDigitFormalSequenceFolders() throws Exception {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		Path file = service.resolveFormalFile(
			"20260811-100",
			"正定工程-台中港案 20260811-100",
			".xlsx"
		);

		assertThat(file).isEqualTo(
			root.resolve("報價單/20260811-100/正定工程-台中港案 20260811-100.xlsx")
				.toAbsolutePath()
				.normalize()
		);
	}

	// 驗證使用者文字不可控制正式報價資料夾或副檔名。
	@Test
	void rejectsInvalidFormalFolderAndExtension() {
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		assertThatThrownBy(() -> service.resolveFormalFile("../outside", "案件", ".xlsx"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.resolveFormalFile("20260811-01", "案件", ".exe"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAnExistingQuotationFolderSymlinkThatEscapesItsExpectedLocation() throws Exception {
		Path outside = Files.createDirectories(root.resolve("outside"));
		createSymlinkOrSkip(root.resolve("報價單"), outside);
		QuotationOutputDirectoryService service = new QuotationOutputDirectoryService(root.toString());

		assertThatThrownBy(() -> service.createDirectory("安全名稱"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("路徑");
	}

	// 方法：建立測試用 symlink，執行環境不支援時只略過該平台案例。
	private void createSymlinkOrSkip(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		}
		catch (Exception exception) {
			Assumptions.abort("目前執行環境不允許建立 symbolic link");
		}
	}
}
