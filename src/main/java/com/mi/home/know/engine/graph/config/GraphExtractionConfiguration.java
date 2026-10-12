package com.mi.home.know.engine.graph.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 图谱抽取配置装配
 */
@Configuration
@EnableConfigurationProperties(GraphProperties.class)
public class GraphExtractionConfiguration {
}
