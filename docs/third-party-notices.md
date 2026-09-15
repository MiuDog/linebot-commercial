# Third-party notices

本產品包含或於建置、容器執行時使用第三方軟體。實際元件、版本及雜湊以 CI 產生的 `sbom.cdx.json` 為準。

## Runtime components

- Spring Boot 與 Spring Framework：Apache License 2.0。
- PostgreSQL JDBC、Flyway 與 AWS SDK for Java：各依其專案授權，版本列於 SBOM。
- SQLite JDBC：只供舊資料遷移與測試，不是正式資料庫。
- Eclipse Temurin／OpenJDK Runtime：GPLv2 with Classpath Exception，實際 runtime 模組列於 SBOM。
- LibreOffice：商用映像用於 headless PDF 轉換，依 MPL 2.0／相關元件授權散布。

## Build and distribution tools

- Apache Maven 與 Maven Wrapper：Apache License 2.0。
- CycloneDX Maven Plugin：Apache License 2.0。
- GitHub 官方 Actions：MIT License；workflow 鎖定完整 commit SHA。
- Trivy：Apache License 2.0；CI 用於 SBOM 與漏洞掃描。

## Source and license locations

- Spring：https://github.com/spring-projects
- SQLite JDBC：https://github.com/xerial/sqlite-jdbc
- Flyway：https://github.com/flyway/flyway
- AWS SDK for Java：https://github.com/aws/aws-sdk-java-v2
- OpenJDK：https://openjdk.org/
- LibreOffice：https://www.libreoffice.org/
- CycloneDX Maven Plugin：https://github.com/CycloneDX/cyclonedx-maven-plugin
- Trivy：https://github.com/aquasecurity/trivy

此文件不是法律意見。正式商用發佈前，維護者必須依 SBOM 完成授權掃描及法律審閱。
