package com.zhiyu.health.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 驱动 seam（票 91 起读 + 写，ADR-0006 修订）：读经 rule/ 只读事实适配器（READ session），
 * 写仅经 service/knowledge/GraphAdminService 图谱在线管理（WRITE session，白名单限定）；
 * 读投影仍转调 server-py。不在启动期发起远程连接。
 */
@Configuration
public class Neo4jDriverConfig {

    @Bean(destroyMethod = "close")
    Driver neo4jDriver(
            @Value("${zhiyu.neo4j.uri}") String uri,
            @Value("${zhiyu.neo4j.user}") String user,
            @Value("${zhiyu.neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }
}
