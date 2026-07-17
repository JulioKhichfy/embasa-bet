package com.footballstats.config;

import com.footballstats.parser.SofaScoreParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Configuration
public class ParserConfig {

    @Bean
    public Properties sofascoreProperties() {
        Properties p = new Properties();
        try (InputStream is = new ClassPathResource("sofascore.properties").getInputStream()) {
            p.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Aviso: nao foi possivel carregar sofascore.properties: " + e.getMessage());
        }
        return p;
    }

    @Bean
    public SofaScoreParser sofaScoreParser(Properties sofascoreProperties) {
        return new SofaScoreParser(sofascoreProperties);
    }
}
