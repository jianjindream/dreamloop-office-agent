# ===== Vue 构建 =====
FROM node:22-alpine AS frontend-builder
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ===== Maven 编译：把 Vue 产物覆盖旧版静态入口 =====
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend-builder /frontend/dist/ ./src/main/resources/static/
RUN mvn -B -q -DskipTests package

# 运行时镜像（轻量 JRE 21）
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tzdata curl \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/agi-assistant-*.jar /app/app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
