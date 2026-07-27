package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.repository.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 【職責】資產生命週期的協調者：收錄 → 歸檔 → 查詢。
 *
 * <p>這是唯一同時碰到「檔案系統」與「資料庫」的地方，兩者的一致性由這裡負責：
 * 檔案搬移與路徑更新在同一個交易內完成，避免資料庫指向一個不存在的檔案。
 * 上層的 {@link CommandService} 因此完全不需要知道檔案放在哪裡。
 */
@Service
public class AssetService {

    private final AssetRepository repository;
    private final FileStorageService fileStorage;

    /**
     * @param repository  資產索引的資料庫存取
     * @param fileStorage 圖片本體的落地與搬移
     */
    public AssetService(AssetRepository repository, FileStorageService fileStorage) {
        this.repository = repository;
        this.fileStorage = fileStorage;
    }

    /**
     * 收錄一張從群組傳來的圖片：先寫檔，再建立索引。圖片依收錄日期落地，
     * 之後不論怎麼打標籤都不會再搬動。
     *
     * <p>LINE 在未收到 200 回應時會重送 webhook，因此以 messageId 做冪等判斷，
     * 重複事件直接略過，不會產生第二份檔案。
     *
     * @param messageId   LINE 訊息 id
     * @param sourceType  來源型態：group／room／user
     * @param sourceId    來源 id，決定這筆資產屬於哪個群組
     * @param uploaderId  上傳者 LINE userId
     * @param content     圖片內容串流，method 結束時一定會被關閉
     * @param contentType 原始 MIME 型態，決定副檔名
     * @return 新收錄的資產；重複事件則為空
     * @throws IOException 寫入磁碟失敗
     */
    @Transactional
    public Optional<Asset> ingest(String messageId, String sourceType, String sourceId,
                                  String uploaderId, InputStream content, String contentType) throws IOException {
        if (repository.findByMessageId(messageId).isPresent()) {
            System.out.println("[收錄] messageId=" + messageId + " 已存在，略過重複事件");
            content.close();
            return Optional.empty();
        }

        FileStorageService.StoredFile stored = fileStorage.save(content, contentType);
        Asset asset = new Asset(null, messageId, UUID.randomUUID().toString().replace("-", ""),
                sourceType, sourceId, uploaderId, stored.relativePath(), stored.contentType(),
                stored.size(), Instant.now(), List.of());
        Long id = repository.insert(asset);
        System.out.println("[收錄] 資產 #" + id + " 已落地：" + stored.relativePath());
        return repository.findByMessageId(messageId);
    }

    /**
     * 對被引用的那則圖片訊息掛上標籤。
     *
     * <p><b>不會搬動檔案。</b> 磁碟只依日期分層，分類完全由標籤承擔，
     * 因此改標籤時檔案路徑永遠不變，備份與外部引用不會失效；
     * 同一張圖也可以同時屬於多個資產編號，不需要在磁碟上複製。
     *
     * @param quotedMessageId 被引用的圖片訊息 id
     * @param tags            要掛上的標籤，第一個視為主要資產編號
     * @return 掛上標籤後的資產；找不到對應圖片或標籤為空時回傳空
     */
    @Transactional
    public Optional<Asset> tag(String quotedMessageId, List<String> tags) {
        Optional<Asset> found = repository.findByMessageId(quotedMessageId);
        if (found.isEmpty() || tags.isEmpty()) {
            return Optional.empty();
        }
        Asset asset = found.get();

        for (String tag : tags) {
            repository.linkTag(asset.id(), repository.upsertTag(tag));
        }
        System.out.println("[標籤] 資產 #" + asset.id() + " 掛上：" + String.join("、", tags));

        return repository.findByMessageId(quotedMessageId);
    }

    /**
     * 依關鍵字查出資產，多個關鍵字為「同時符合」。
     *
     * @param sourceId 查詢範圍，不同群組互不可見
     * @param tags     關鍵字
     * @param limit    最多回傳幾筆
     * @return 符合條件的資產，新到舊排序
     */
    public List<Asset> search(String sourceId, List<String> tags, int limit) {
        return repository.searchByTags(sourceId, tags, limit);
    }

    /**
     * 列出某群組用過的編號／標籤與各自數量，供盤點使用。
     *
     * @param sourceId 統計範圍
     * @return 標籤到數量的對應
     */
    public Map<String, Integer> tagCounts(String sourceId) {
        return repository.tagCounts(sourceId);
    }

    /**
     * 某群組目前收錄的圖片總數。
     *
     * @param sourceId 統計範圍
     * @return 圖片張數
     */
    public int countBySource(String sourceId) {
        return repository.countBySource(sourceId);
    }

    /**
     * 以對外取圖權杖找出資產，供 {@code MediaController} 回傳檔案。
     *
     * @param shareToken 對外權杖
     * @return 對應的資產；查無則為空
     */
    public Optional<Asset> findByShareToken(String shareToken) {
        return repository.findByShareToken(shareToken);
    }

    /**
     * 以 LINE 訊息 id 找出資產。
     *
     * @param messageId LINE 訊息 id
     * @return 對應的資產；查無則為空
     */
    public Optional<Asset> findByMessageId(String messageId) {
        return repository.findByMessageId(messageId);
    }

    /**
     * 讀出資產圖片的完整位元組，供報價流程送給 AI 辨識。
     *
     * <p>刻意一次讀進記憶體而非回傳串流：呼叫端需要把同一份位元組
     * 先送給模型、之後再貼進 PDF，串流只能讀一次。
     *
     * @param asset 目標資產
     * @return 圖片位元組
     * @throws IOException 檔案不存在或讀取失敗
     */
    public byte[] contentOf(Asset asset) throws IOException {
        return Files.readAllBytes(fileStorage.resolve(asset.filePath()));
    }
}
