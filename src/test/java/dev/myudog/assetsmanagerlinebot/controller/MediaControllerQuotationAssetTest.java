package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.AssetPathResolver;
import dev.myudog.assetsmanagerlinebot.service.AssetService;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationOutputDirectoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaControllerQuotationAssetTest {

	@TempDir Path root;
	Connection connection;
	MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		Path assetsRoot = root.resolve("assets");
		Path quotationRoot = root.resolve("quotations");
		Path image = quotationRoot.resolve("報價單/20260811-01/image-01.jpg");
		Files.createDirectories(image.getParent());
		Files.writeString(image, "formal-image");

		connection = DriverManager.getConnection("jdbc:sqlite::memory:");
		JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		jdbc.execute("CREATE TABLE quotation_asset (asset_id INTEGER NOT NULL)");
		jdbc.update("INSERT INTO quotation_asset (asset_id) VALUES (1)");
		AssetPathResolver paths = new AssetPathResolver(
			jdbc,
			new FileStorageService(assetsRoot.toString()),
			new QuotationOutputDirectoryService(quotationRoot.toString())
		);
		AssetService assets = Mockito.mock(AssetService.class);
		Asset asset = new Asset(
			1L, "M1", "public-token", "user", "U1", "U1",
			"報價單/20260811-01/image-01.jpg", "image/jpeg", 12L, Instant.now(), List.of()
		);
		Mockito.when(assets.findByShareToken("public-token")).thenReturn(Optional.of(asset));
		mvc = MockMvcBuilders.standaloneSetup(new MediaController(assets, paths)).build();
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	@Test
	void servesFormalQuotationAssetFromQuotationRootWhenAssetRootIsDifferent() throws Exception {
		mvc.perform(get("/media/public-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(content().bytes("formal-image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
