# Spring Boot 后端镜像 — 多阶段构建。
# Jenkins 只需一条 `docker build`，宿主机无需安装 Maven/JDK。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 换阿里云 Maven 源（国内 4C4G 上拉依赖快很多）。用 echo 写 settings.xml，兼容 legacy builder。
RUN mkdir -p /root/.m2 && \
    echo '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>Aliyun Central</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' \
      > /root/.m2/settings.xml

# 先拷 pom，利用层缓存拉依赖（4C4G 首次慢，增量构建快）。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# 运行层：精简 JRE。
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 换阿里云 Ubuntu apt 源（jammy 的源在 /etc/apt/sources.list，不存在 .d 下 .list）
RUN set -eux; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g; s|http://security.ubuntu.com|https://mirrors.aliyun.com|g; s|http://ports.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list; \
    fi
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

# 4C4G 服务器：限堆 512m，留余量给 agent-service / postgres。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Xmx512m", "-jar", "app.jar"]
