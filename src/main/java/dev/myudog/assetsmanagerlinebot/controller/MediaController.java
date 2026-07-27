package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.service.AssetService;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * LINE 伺服器抓圖用的對外端點。
 *
 * <p>這個端點必然對公網開放（LINE 才抓得到圖），所以路徑用的是每筆資產獨立、
 * 不可預測的 shareToken，而不是流水號或檔名——否則等於把整個資產庫公開。
 */
@RestController
public class MediaController {

    private final AssetService assetService;
    private final FileStorageService fileStorage;

    public MediaController(AssetService assetService, FileStorageService fileStorage) {
        this.assetService = assetService;
        this.fileStorage = fileStorage;
    }

    @GetMapping("/media/{shareToken}")
    public ResponseEntity<Resource> serve(@PathVariable String shareToken) {
        Optional<Asset> found = assetService.findByShareToken(shareToken);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Asset asset = found.get();
        Path file = fileStorage.resolve(asset.filePath());
        if (!Files.isReadable(file)) {
            System.err.println("[媒體] 資料庫指向的檔案不存在：" + asset.filePath());
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = asset.contentType() == null
                ? MediaType.IMAGE_JPEG
                : MediaType.parseMediaType(asset.contentType().split(";")[0].trim());

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }
}
