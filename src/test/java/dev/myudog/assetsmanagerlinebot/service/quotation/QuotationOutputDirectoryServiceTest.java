package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
