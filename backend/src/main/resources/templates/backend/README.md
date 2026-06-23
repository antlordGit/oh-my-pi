# Backend Template

Spring Boot 3.3.5 + Spring Data JPA + Spring Data Redis + MySQL 最小可运行骨架。

## 启动

```bash
# 准备 MySQL 与 Redis
mysql -u root -p -e "CREATE DATABASE demo CHARACTER SET utf8mb4;"
redis-server &

# 启动应用
./mvnw spring-boot:run
```

默认监听 `http://localhost:8080`。

## 端点

- `GET /` —— 返回欢迎字符串

## 自定义

1. 修改 `pom.xml` 的 `groupId` / `artifactId`
2. 修改 `src/main/resources/application.yml` 的数据库连接信息
3. 在 `com.example.demo.controller` 下新增 Controller