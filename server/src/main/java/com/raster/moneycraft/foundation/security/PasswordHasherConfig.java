package com.raster.moneycraft.foundation.security;

import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Configuration
public class PasswordHasherConfig {

    @Bean
    public Argon2PasswordEncoder argon2PasswordEncoder(MoneyCraftProperties properties) {
        MoneyCraftProperties.Argon2 cfg = properties.getSecurity().getArgon2();
        return new Argon2PasswordEncoder(
            cfg.getSaltLength(),
            cfg.getHashLength(),
            cfg.getParallelism(),
            cfg.getMemoryKib(),
            cfg.getIterations()
        );
    }
}
