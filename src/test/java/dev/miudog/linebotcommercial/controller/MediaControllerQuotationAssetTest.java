package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.domain.Asset;
import dev.miudog.linebotcommercial.service.AssetService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaControllerQuotationAssetTest {

	MockMvc mvc;

	// 方法：建立以物件儲存內容為來源的媒體控制器測試環境。
	@BeforeEach
	void setUp() throws Exception {
		AssetService assets = Mockito.mock(AssetService.class);
		Asset asset = new Asset(
			1L, "M1", "public-token", "user", "U1", "U1",
			"報價單/20260811-01/image-01.jpg", "image/jpeg", 12L, Instant.now(), List.of()
		);
		Mockito.when(assets.findByShareToken("public-token")).thenReturn(Optional.of(asset));
		Mockito.when(assets.contentOf(asset)).thenReturn(
			"formal-image".getBytes(java.nio.charset.StandardCharsets.UTF_8)
		);
		mvc = MockMvcBuilders.standaloneSetup(new MediaController(assets)).build();
	}

	// 方法：正式媒體端點回傳物件儲存中的不可變內容。
	@Test
	void servesFormalQuotationAssetFromQuotationRootWhenAssetRootIsDifferent() throws Exception {
		mvc.perform(get("/media/public-token"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(content().bytes("formal-image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
