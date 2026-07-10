package com.restopanda.realtime.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the gateway's {@link RealtimeProperties} binding. (Security chains live
 * in {@link RealtimeSecurityConfig}/{@code StreamSecurityConfig}.)
 */
@Configuration
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfig {}
