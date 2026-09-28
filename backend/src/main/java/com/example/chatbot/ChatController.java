package com.example.chatbot;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.ui.Model;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
public class ChatController {

    private final DocumentService documentService;
    private final RAGService ragService;
    private final AtomicInteger totalQuestions = new AtomicInteger();

    public ChatController(DocumentService documentService, RAGService ragService) {
        this.documentService = documentService;
        this.ragService = ragService;
    }

    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, Object> status() {
        return Map.of(
                "indexedFiles", documentService.getIndexedFiles(),
                "totalChunks", documentService.getTotalChunks(),
                "totalQuestions", totalQuestions.get()
        );
    }

    @PostMapping("/api/upload")
    @ResponseBody
    public ResponseEntity<?> uploadPdfs(@RequestParam("files") MultipartFile[] files) {
        try {
            int chunks = documentService.processAndStorePdf(files);
            return ResponseEntity.ok(Map.of("message", "Successfully processed " + files.length + " files.", "chunks", chunks));
        } catch (Exception e) {
            e.printStackTrace();
            return failureResponse(e);
        }
    }

    private String rootCauseMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private ResponseEntity<?> failureResponse(Exception exception) {
        String message = rootCauseMessage(exception);
        if (message.contains("RESOURCE_EXHAUSTED") || message.matches("(?s).*\"code\"\\s*:\\s*429.*")) {
            return ResponseEntity.status(429).body(Map.of("error", message));
        }
        return ResponseEntity.internalServerError().body(Map.of("error", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Map.of("error", "File is too large. Maximum upload size is 200 MB."));
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<?> chat(@RequestBody Map<String, String> payload) {
        String query = payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty"));
        }
        if (documentService.getIndexedFiles().isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "No PDFs are indexed. Upload a PDF and wait for indexing to finish before asking a question."));
        }

        try {
            String rawAnswer = ragService.generateAnswer(query);
            totalQuestions.incrementAndGet();
            
            String answer;
            String source = "Unknown.pdf";
            
            if (rawAnswer.contains("|||")) {
                String[] parts = rawAnswer.split("\\|\\|\\|");
                answer = parts[0];
                if (parts.length > 1) {
                    source = parts[1];
                }
            } else {
                answer = rawAnswer;
            }

            Map<String, String> response = new HashMap<>();
            response.put("answer", answer);
            response.put("source", source);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return failureResponse(e);
        }
    }
}
