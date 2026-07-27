package dev.myudog.assetsmanagerlinebot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 【職責】圖片本體在磁碟上的落地與路徑安全。
 *
 * <p>對外只回傳「相對於資產庫根目錄、以 / 分隔」的路徑，資料庫也只存這個，
 * 因此整個資產庫連同 assets.db 可以整包搬到別台機器而不失效。
 *
 * <p><b>實體結構</b>：{@code {根目錄}/{yyyyMMdd}/{yyyyMMdd-HHmmssSSS}.jpg}
 *
 * <pre>
 * F:\資產庫\
 * ├─ assets.db
 * ├─ 20260727\
 * │  ├─ 20260727-224530123.jpg
 * │  └─ 20260727-224612456.jpg
 * └─ 20260728\
 *    └─ 20260728-091502001.jpg
 * </pre>
 *
 * <p><b>磁碟上只有日期，沒有資產編號。</b> 分類完全交給資料庫的標籤，
 * 檔案落地之後就不再搬動——這是「指標法」的核心：磁碟負責保存，
 * 資料庫負責組織，兩者職責不重疊。
 *
 * <p>好處是使用者改標籤時檔案路徑永遠不變，備份與外部引用不會失效；
 * 而且同一張圖可以同時屬於多個編號，不必在磁碟上複製或做連結。
 *
 * <p>根目錄由 {@code ASSETS_ROOT} 環境變數指定，可以是任意路徑
 * （例如 {@code F:/資產庫}），與專案目錄無關。
 */
@Service
public class FileStorageService {

    /** 日期資料夾，例如 20260727。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 檔名時間戳，含毫秒以降低同秒多張照片的碰撞機率。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    /** 時區寫死台北：跟著容器時區跑的話，日期資料夾會在不同機器上跳動。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private final Path root;

    /**
     * @param assetsRoot 資產庫根目錄，由 {@code ASSETS_ROOT} 指定，
     *                   例如 {@code F:/資產庫} 或 {@code /data/assets}
     */
    public FileStorageService(@Value("${app.storage.root}") String assetsRoot) {
        this.root = Paths.get(assetsRoot).toAbsolutePath().normalize();
    }

    /**
     * 落地結果。
     *
     * @param relativePath 相對於資產庫根目錄、以 / 分隔的路徑
     * @param size         實際寫入的位元組數
     * @param contentType  原始 MIME 型態
     */
    public record StoredFile(String relativePath, long size, String contentType) {}

    /**
     * 將 LINE 下載回來的串流寫入當天的日期資料夾，缺少的目錄會自動建立。
     *
     * <p>檔名採純時間戳，直接看資料夾就能依時間排序。毫秒仍碰撞時
     * （同一毫秒兩張圖）由 {@link #uniquePath} 補上流水序號。
     *
     * @param inputStream 圖片內容，method 結束時一定會被關閉
     * @param contentType 原始 MIME 型態，決定副檔名
     * @return 落地結果，含相對路徑
     * @throws IOException 建立目錄或寫檔失敗
     */
    public StoredFile save(InputStream inputStream, String contentType) throws IOException {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String relativeDir = DAY.format(now);

        Path directory = resolve(relativeDir);
        Files.createDirectories(directory);

        Path target = uniquePath(directory, STAMP.format(now), extensionFor(contentType));
        try (inputStream) {
            long size = Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(relativeDir + "/" + target.getFileName(), size, contentType);
        }
    }

    /**
     * 將相對路徑還原成實體路徑。
     *
     * <p>正規化後會檢查結果仍位於資產庫根目錄之內。使用者輸入現在完全不會
     * 進入路徑，這道檢查是針對資料庫內容的防線——即使 assets.db 被竄改，
     * 也讀不到資產庫以外的檔案。
     *
     * @param relativePath 相對路徑
     * @return 對應的絕對路徑
     * @throws IllegalArgumentException 路徑逃出資產庫根目錄時
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路徑逃逸資產庫根目錄: " + relativePath);
        }
        return resolved;
    }

    /**
     * 資產庫根目錄的絕對路徑，供啟動時記錄與疑難排解使用。
     *
     * @return 根目錄
     */
    public Path root() {
        return root;
    }

    /**
     * 在目錄下找出一個尚未被占用的檔名。
     *
     * <p>時間戳已含毫秒，正常情況第一次就會命中；同一毫秒收到兩張圖時
     * 依序嘗試 {@code -1}、{@code -2}，避免後者覆蓋前者。
     *
     * @param directory 目標目錄
     * @param baseName  檔名主體（時間戳）
     * @param extension 含點的副檔名
     * @return 尚未存在的檔案路徑
     */
    private Path uniquePath(Path directory, String baseName, String extension) {
        Path candidate = directory.resolve(baseName + extension);
        int sequence = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + "-" + sequence + extension);
            sequence++;
        }
        return candidate;
    }

    /**
     * 由 MIME 型態決定副檔名，未知型態一律當 JPEG——LINE 傳來的圖片絕大多數是 JPEG。
     *
     * @param contentType MIME 型態，可為 null
     * @return 含點的副檔名
     */
    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.split(";")[0].trim().toLowerCase()) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
