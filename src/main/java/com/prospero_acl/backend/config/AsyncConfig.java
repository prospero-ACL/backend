package com.prospero_acl.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// I could put @EnableAsync on the main @SpringBootApplication class and delete 
// this file, but I think it's cleaner to have a dedicated config class for 
// async stuff.
@Configuration
@EnableAsync
public class AsyncConfig {
}
