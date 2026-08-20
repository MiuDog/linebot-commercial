# ============================================================
# 階段一：Maven 編譯與打包
# ============================================================
FROM maven:3.9.16-eclipse-temurin-25-alpine AS build
WORKDIR /app

# 【快取優化 1】先複製 pom.xml，讓 Maven 在獨立的 layer 下載所有依賴。
# 只要 pom.xml 沒變，改 Java 原始碼時這一層都會直接命中快取。
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 【快取優化 2】依賴下載完畢後才複製原始碼
COPY src ./src
COPY outputs/excel-templates ./outputs/excel-templates
RUN mvn clean package -DskipTests

# ============================================================
# 階段二：JRE 執行環境
# ============================================================
# 這裡刻意「不用」alpine：musl 版本沒有 UTF-8 locale，JVM 的 sun.jnu.encoding
# 會退化成 ASCII，建立中文資料夾／檔名時會全部變成問號，資產分類會直接壞掉。
# 編譯階段可以用 alpine（不碰中文檔名），執行階段不行。
FROM eclipse-temurin:25-jre
WORKDIR /app

# 健康檢查需要 HTTP 用戶端；基礎 JRE 映像本身未附 wget/curl。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 中文檔名的第一道保險：作業系統層的 locale
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 產出檔名由 pom.xml 的 <finalName>app</finalName> 固定，
# 升版本號時不需要回來改這一行
COPY --from=build /app/target/app.jar app.jar

# 系統共同掛載點：圖片、SQLite、報價單與日誌都在其子路徑內
# 主機端的實際位置由 docker-compose 的 SYSTEM_ROOT_PATH 決定
VOLUME /data/system-root

EXPOSE 8088

# 容器健康檢查，讓 docker compose ps 能直接看出服務是否就緒
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:8088/actuator/health || exit 1

# 中文檔名的第二道保險：把 JVM 的檔案／路徑編碼釘死成 UTF-8
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-jar", "app.jar"]
