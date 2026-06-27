# 階段一：使用 Maven 容器進行專案編譯與打包
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 階段二：使用輕量級 JRE 運行實體
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 複製打包好的 jar 檔
COPY --from=build /app/target/*.jar app.jar

# 暴露出 LINE Bot 專案在 properties 設定的 8088 Port
EXPOSE 8088

ENTRYPOINT ["java", "-jar", "app.jar"]