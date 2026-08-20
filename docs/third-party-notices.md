# Third-party notices

本產品包含或於建置時使用第三方軟體。實際每次建置的直接與遞迴元件、版本及雜湊，以安裝目錄內的 `sbom.cdx.json` 為準。

## Runtime components

- Spring Boot 與 Spring Framework：Apache License 2.0。
- SQLite JDBC：Apache License 2.0；其封裝的 SQLite 為 Public Domain。
- JNA／JNA Platform：Apache License 2.0 或 LGPL 2.1+ 雙重授權，本產品採 Apache License 2.0 條款使用。
- Eclipse Temurin／OpenJDK Runtime：GPLv2 with Classpath Exception，實際 runtime 模組列於 SBOM。

## Build and distribution tools

- Apache Maven 與 Maven Wrapper：Apache License 2.0。
- CycloneDX Maven Plugin：Apache License 2.0。
- NSIS：zlib/libpng license；僅用於建立 Windows Setup。
- GitHub 官方 Actions：MIT License；workflow 鎖定完整 commit SHA。

## Source and license locations

- Spring：https://github.com/spring-projects
- SQLite JDBC：https://github.com/xerial/sqlite-jdbc
- JNA：https://github.com/java-native-access/jna
- OpenJDK：https://openjdk.org/
- CycloneDX Maven Plugin：https://github.com/CycloneDX/cyclonedx-maven-plugin
- NSIS：https://nsis.sourceforge.io/

此文件不是法律意見。正式商用發佈前，維護者必須依 SBOM 完成授權掃描及法律審閱。
