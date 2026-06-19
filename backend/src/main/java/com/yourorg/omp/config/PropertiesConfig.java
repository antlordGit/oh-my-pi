package com.yourorg.omp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OmpProperties.class)
public class PropertiesConfig {
}