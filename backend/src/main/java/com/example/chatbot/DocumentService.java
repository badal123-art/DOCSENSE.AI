package com.example.chatbot;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DocumentService {

    private final RAGService ragService;
    private final List<String> indexedFiles = new ArrayList<>();
    private final AtomicInteger totalChunks = new AtomicInteger(0);

    public DocumentService(RAGService ragService) {
        this.ragService = ragService;
    }

    public int processAndStorePdf(MultipartFile[] files) throws Exception {
        int chunksAdded = 0;
        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
        
        for (MultipartFile file : files) {
            try (InputStream is = file.getInputStream()) {
                Document document = parser.parse(is);
                document.metadata().put("source", file.getOriginalFilename());

                var splitter = DocumentSplitters.recursive(1500, 200);
                List<TextSegment> segments = splitter.split(document);
                segments.removeIf(segment -> segment.text() == null || segment.text().trim().isEmpty());

                if (segments.isEmpty()) {
                    continue;
                }

                var embeddings = ragService.embedAll(segments);
                ragService.getEmbeddingStore().addAll(embeddings, segments);
                
                chunksAdded += segments.size();
                
                if (!indexedFiles.contains(file.getOriginalFilename())) {
                    indexedFiles.add(file.getOriginalFilename());
                }
            }
        }
        totalChunks.addAndGet(chunksAdded);
        return chunksAdded;
    }
    
    public List<String> getIndexedFiles() {
        return indexedFiles;
    }
    
    public int getTotalChunks() {
        return totalChunks.get();
    }
}
