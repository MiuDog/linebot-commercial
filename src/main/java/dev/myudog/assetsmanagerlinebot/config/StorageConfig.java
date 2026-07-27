package dev.myudog.assetsmanagerlinebot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 這裡刻意自己建立 DataSource，而不是交給 auto-configuration。
 *
 * <p>原因：SQLite 不會幫你建立資料庫檔案的父目錄，若 storage 目錄還不存在，
 * 連線會直接以「path does not exist」失敗，整個應用程式起不來。
 * 由自己建立 bean 才能保證「先建目錄、再開連線」的順序。
 */
@Configuration
public class StorageConfig {

    @Bean
    public DataSource dataSource(@Value("${app.storage.root}") String assetsRoot,
                                 @Value("${spring.datasource.url}") String jdbcUrl) throws IOException {
        Path root = Paths.get(assetsRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setJdbcUrl(jdbcUrl);
        // SQLite 只允許單一寫入者，連線池開多條只會換來 SQLITE_BUSY
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionInitSql("PRAGMA foreign_keys=ON");
        return dataSource;
    }
}
