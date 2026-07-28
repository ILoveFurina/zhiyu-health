# 构建段：先拉依赖离线缓存，再打包，最大化镜像层复用
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# 运行段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/server-java-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
