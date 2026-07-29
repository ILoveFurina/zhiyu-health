package com.zhiyu.health.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** server-java 禁忌规则所需的只读 Neo4j 驱动；不在启动期发起远程连接。 */
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
