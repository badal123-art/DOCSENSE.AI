package com.example.chatbot;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        // Load .env variables into System properties
        try {
            Path envFile = Path.of(".env");
            if (!Files.exists(envFile)) {
                envFile = Path.of("..", ".env");
            }
            Dotenv dotenv = Dotenv.configure()
                    .directory(envFile.toAbsolutePath().getParent().toString())
                    .filename(envFile.getFileName().toString())
                    .ignoreIfMissing()
                    .load();
            dotenv.entries().forEach(entry -> {
                if (System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
        } catch (Exception e) {
            System.out.println("No .env file found or error loading it.");
        }
        
        SpringApplication.run(ChatApplication.class, args);
    }

}
