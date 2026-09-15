# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests clean package

FROM eclipse-temurin:25.0.4_7-jre-noble

RUN apt-get update \
	&& apt-get install -y --no-install-recommends \
		curl \
		fonts-noto-cjk \
		libreoffice-calc \
		libreoffice-writer \
	&& rm -rf /var/lib/apt/lists/* \
	&& groupadd --system --gid 10001 linebot \
	&& useradd --system --uid 10001 --gid linebot --home-dir /nonexistent --shell /usr/sbin/nologin linebot

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/app.jar /app/app.jar

ENV LANG=C.UTF-8 \
	LC_ALL=C.UTF-8 \
	HOME=/tmp/linebot-home \
	JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp"

USER 10001:10001
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
	CMD ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8088/readyz"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
