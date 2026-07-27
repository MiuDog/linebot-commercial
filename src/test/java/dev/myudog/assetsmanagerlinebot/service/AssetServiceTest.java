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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收錄 → 打標籤 → 查詢的完整往返。
 *
 * <p>重點在於驗證「磁碟只依日期分層」這個設計：打標籤絕不搬動檔案，
 * 因此同一張圖可以同時屬於多個資產編號，而路徑永遠不變。
 * 中文標籤在 SQLite 兩端都不會走樣也一併驗證。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.root=${java.io.tmpdir}/assets-manager-test",
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/assets-manager-test/test.db"
})
class AssetServiceTest {

    @Autowired
    AssetService assetService;

    @Autowired
    FileStorageService fileStorage;

    @Test
    void imageLandsInDateFolderAndTaggingNeverMovesIt() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String groupId = "C" + UUID.randomUUID();
        byte[] fakeImage = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        Optional<Asset> ingested = assetService.ingest(messageId, "group", groupId, "U123",
                new ByteArrayInputStream(fakeImage), "image/jpeg");

        assertThat(ingested).isPresent();
        // 結構為 {yyyyMMdd}/{yyyyMMdd-HHmmssSSS}.jpg，磁碟上沒有編號那一層
        assertThat(ingested.get().filePath()).matches("\\d{8}/\\d{8}-\\d{9}(-\\d+)?\\.jpg");
        assertThat(ingested.get().dateFolder()).matches("\\d{8}");
        assertThat(fileStorage.resolve(ingested.get().filePath())).exists();

        String pathBeforeTagging = ingested.get().filePath();

        Optional<Asset> tagged = assetService.tag(messageId, List.of("zd12345", "機房設備", "台北"));

        assertThat(tagged).isPresent();
        assertThat(tagged.get().tags()).containsExactlyInAnyOrder("zd12345", "機房設備", "台北");
        // 第一個標籤是主要資產編號
        assertThat(tagged.get().primaryTag()).isEqualTo("zd12345");
        // 關鍵：打標籤不會搬動檔案，路徑必須一模一樣
        assertThat(tagged.get().filePath()).isEqualTo(pathBeforeTagging);

        Path onDisk = fileStorage.resolve(tagged.get().filePath());
        assertThat(onDisk).exists();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(fakeImage);
        // 磁碟路徑不含任何標籤名稱
        assertThat(onDisk.toString()).doesNotContain("zd12345").doesNotContain("機房設備");
    }

    @Test
    void searchesByTagWithAndSemanticsScopedToGroup() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String groupId = "C" + UUID.randomUUID();

        assetService.ingest(messageId, "group", groupId, "U123",
                new ByteArrayInputStream("img".getBytes(StandardCharsets.UTF_8)), "image/jpeg");
        assetService.tag(messageId, List.of("zd12345", "機房設備", "台北"));

        // 多關鍵字是 AND 語意
        assertThat(assetService.search(groupId, List.of("zd12345", "台北"), 10)).hasSize(1);
        assertThat(assetService.search(groupId, List.of("機房設備", "高雄"), 10)).isEmpty();
        // 查詢限定在同一個群組內
        assertThat(assetService.search("C-其他群組", List.of("zd12345"), 10)).isEmpty();

        assertThat(assetService.tagCounts(groupId)).containsEntry("zd12345", 1);
    }

    @Test
    void oneImageCanBelongToMultipleAssetCodes() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String groupId = "C" + UUID.randomUUID();

        assetService.ingest(messageId, "group", groupId, "U1",
                new ByteArrayInputStream("img".getBytes(StandardCharsets.UTF_8)), "image/jpeg");
        assetService.tag(messageId, List.of("zd12345"));
        // 之後補登到第二個編號底下，磁碟不需要複製檔案
        Optional<Asset> tagged = assetService.tag(messageId, List.of("zd67890"));

        assertThat(tagged).isPresent();
        assertThat(tagged.get().tags()).containsExactlyInAnyOrder("zd12345", "zd67890");
        assertThat(assetService.search(groupId, List.of("zd12345"), 10)).hasSize(1);
        assertThat(assetService.search(groupId, List.of("zd67890"), 10)).hasSize(1);
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
    void rejectsPathsThatEscapeTheAssetsRoot() {
        // 使用者輸入不再進入路徑，這道防線是針對資料庫內容被竄改的情況
        assertThatThrownBy(() -> fileStorage.resolve("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路徑逃逸");
    }
}
