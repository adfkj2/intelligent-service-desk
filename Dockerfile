# 三阶段构建：前端产物 → Maven 打包 → JRE 运行
#
# 为什么前端必须在镜像内构建：vite 的产物目录 src/main/resources/static 被 .gitignore 排除，
# 本地有没有构建过完全取决于开发者的机器状态。镜像里自己构建，才能保证"任何一次构建的 jar
# 都自带同一份前端"。构建顺序也是刻意的：先 copy package*.json 再 npm ci，
# 只改业务代码时这一层能命中缓存。

# ---------- 1. 前端 ----------
FROM node:20-alpine AS frontend

WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# vite.config.js 的 outDir 是 ../src/main/resources/static
RUN npm run build

# ---------- 2. 后端 ----------
FROM maven:3.9-eclipse-temurin-21 AS backend

WORKDIR /build
# 可选 Maven 镜像：默认走 Maven Central（CI 用默认即可）；
# 网络受限环境用 --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ARG MAVEN_MIRROR_URL=""
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
        mkdir -p /root/.m2 && \
        printf '%s' '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>'"$MAVEN_MIRROR_URL"'</url></mirror></mirrors></settings>' > /root/.m2/settings.xml; \
    fi

# 这里用镜像自带的 mvn 而不是 ./mvnw：基础镜像已固定 Maven 3.9.x，wrapper 在容器里
# 只会多下载一次发行包，反而让构建更慢、也更依赖外部网络。
# 先把依赖拉下来单独成层：只改源码时不必重新下载 Maven 依赖
COPY pom.xml ./
RUN mvn -B -ntp -q dependency:go-offline

COPY src/ ./src/
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static
# 测试在 CI 里跑，镜像构建跳过测试编译，省一半时间
RUN mvn -B -ntp -Dmaven.test.skip=true package

# ---------- 3. 运行时 ----------
FROM eclipse-temurin:24-jre-alpine AS runtime

ENV TZ=Asia/Shanghai
# 非 root 运行：容器内被攻破时，攻击者拿到的不是 root
RUN addgroup -S isd && adduser -S -G isd isd

WORKDIR /app
COPY --from=backend /build/target/isd-0.0.1-SNAPSHOT.jar /app/app.jar
RUN chown -R isd:isd /app
USER isd

EXPOSE 8081
# 管理端口绑在 127.0.0.1:8082（见 application.yml），容器内探活正好可达；
# 用 busybox 自带的 wget，不额外装 curl
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8082/actuator/health || exit 1

# MaxRAMPercentage 而不是写死 -Xmx：容器内存限制变化时 JVM 自适应
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
