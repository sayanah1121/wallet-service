package com.sayan.wallet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

// enables @Retryable (used to retry transfers that lose a deadlock or serialization conflict)
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}
