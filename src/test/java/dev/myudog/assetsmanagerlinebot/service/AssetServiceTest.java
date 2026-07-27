package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收錄 → 中文標籤 → 實體搬移 → 查詢的完整往返。
 * 特別驗證中文資料夾名稱在磁碟與 SQLite 兩端都不會走樣。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.path=${java.io.tmpdir}/assets-manager-test",
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/assets-manager-test/test.db"
})
class AssetServiceTest {

    @Autowired
    AssetService assetService;

    @Autowired
    FileStorageService fileStorage;

    @Test
    void chineseTagBecomesPhysicalFolderAndIsSearchable() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String groupId = "C" + UUID.randomUUID();
        byte[] fakeImage = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        Optional<Asset> ingested = assetService.ingest(messageId, "group", groupId, "U123",
                new ByteArrayInputStream(fakeImage), "image/jpeg");

        assertThat(ingested).isPresent();
        assertThat(ingested.get().category()).isEqualTo("未分類");
        assertThat(fileStorage.resolve(ingested.get().filePath())).exists();

        Optional<Asset> tagged = assetService.tag(messageId, List.of("機房設備", "台北", "2026"));

        assertThat(tagged).isPresent();
        assertThat(tagged.get().tags()).containsExactlyInAnyOrder("機房設備", "台北", "2026");
        // 第一個標籤成為實體分類資料夾
        assertThat(tagged.get().category()).isEqualTo("機房設備");

        Path onDisk = fileStorage.resolve(tagged.get().filePath());
        assertThat(onDisk).exists();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(fakeImage);
        assertThat(onDisk.toString()).contains("機房設備");
        // 舊位置應該已經清空
        assertThat(fileStorage.resolve(ingested.get().filePath())).doesNotExist();

        // 多標籤查詢是 AND 語意
        assertThat(assetService.search(groupId, List.of("機房設備", "台北"), 10)).hasSize(1);
        assertThat(assetService.search(groupId, List.of("機房設備", "高雄"), 10)).isEmpty();
        // 查詢限定在同一個群組內
        assertThat(assetService.search("C-其他群組", List.of("機房設備"), 10)).isEmpty();

        assertThat(assetService.tagCounts(groupId)).containsEntry("機房設備", 1);
    }

    @Test
    void duplicateWebhookEventIsNotIngestedTwice() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String groupId = "C" + UUID.randomUUID();

        assetService.ingest(messageId, "group", groupId, "U1",
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), "image/jpeg");
        Optional<Asset> second = assetService.ingest(messageId, "group", groupId, "U1",
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), "image/jpeg");

        assertThat(second).isEmpty();
        assertThat(assetService.countBySource(groupId)).isEqualTo(1);
    }

    @Test
    void pathTraversalCharactersInTagsAreStripped() {
        assertThat(FileStorageService.sanitize("../../etc/passwd")).isEqualTo("etcpasswd");
        assertThat(FileStorageService.sanitize("機房/設備")).isEqualTo("機房設備");
        assertThat(FileStorageService.sanitize("  ")).isEqualTo("未分類");
    }
}
