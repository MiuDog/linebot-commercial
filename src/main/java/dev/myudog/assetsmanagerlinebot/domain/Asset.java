package dev.myudog.assetsmanagerlinebot.domain;

import java.time.Instant;
import java.util.List;

/**
 * 【職責】一筆資產索引的不可變快照，是資料庫列與各層之間的共同語言。
 *
 * <p>這個專案採「指標法」：圖片本體永遠留在磁碟，資料庫只保存指向它的
 * {@code filePath}。因此本紀錄本身不含任何影像位元組。
 *
 * <p>{@code filePath} 是相對於 {@code app.storage.path} 的路徑，且一律以 "/" 分隔，
 * 讓同一份 assets.db 在 Windows 與 Linux 容器之間搬移時不會失效。
 *
 * @param id          資料庫流水號；尚未寫入時為 null
 * @param messageId   LINE 訊息 id，用來對應引用回覆並防止重複收錄
 * @param shareToken  對外取圖用的不可預測權杖，見 {@code MediaController}
 * @param sourceType  來源型態：group／room／user
 * @param sourceId    來源 id，資料以此切開，不同群組互不可見
 * @param uploaderId  上傳者的 LINE userId
 * @param filePath    指向磁碟檔案的相對路徑
 * @param contentType 原始 MIME 型態，決定副檔名與回傳標頭
 * @param fileSize    檔案位元組數
 * @param createdAt   收錄時間
 * @param tags        關聯的資產編號與標籤；未載入時為空集合
 */
public record Asset(
        Long id,
        String messageId,
        String shareToken,
        String sourceType,
        String sourceId,
        String uploaderId,
        String filePath,
        String contentType,
        Long fileSize,
        Instant createdAt,
        List<String> tags) {

    /**
     * 目前歸屬的分類資料夾，也就是 {@code filePath} 的第一段。
     *
     * <p>對已歸檔的資產而言就是資產編號（例如 {@code zd12345}），
     * 尚未歸檔時則是 {@code 未分類}。
     *
     * @return 分類資料夾名稱
     */
    public String category() {
        int slash = filePath.indexOf('/');
        return slash > 0 ? filePath.substring(0, slash) : filePath;
    }
}
