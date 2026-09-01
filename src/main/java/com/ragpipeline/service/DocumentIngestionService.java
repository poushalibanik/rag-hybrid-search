package com.ragpipeline.service;

import com.ragpipeline.kafka.IngestionProducer;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJob;
import com.ragpipeline.model.IngestionJobRecord;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DocumentIngestionService {
    private final DocumentRepository documents;
    private final IngestionJobRepository jobs;
    private final IngestionProducer producer;
    private final Tika tika = new Tika();

    /** Creates one durable async job. Identical content is returned without duplicate chunks. */
    public Document ingest(MultipartFile file, String strategy, String organization,
                           String authority, String documentType, Boolean current) {
        try {
            String name = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
            String content = tika.parseToString(file.getInputStream());
            String hash = sha256(content);
            // rawContent fallback also protects documents ingested before the content_hash migration.
            var existing = documents.findByContentHash(hash).or(() -> documents.findFirstByRawContent(content));
            if (existing.isPresent()) return existing.get();

            Metadata metadata = metadata(name, content, organization, authority, documentType, current);
            Document document = documents.save(Document.builder()
                    .fileName(name)
                    .fileType(Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"))
                    .rawContent(content).contentHash(hash)
                    .chunkingStrategy(strategy == null ? "RECURSIVE" : strategy.toUpperCase(Locale.ROOT))
                    .organization(metadata.organization()).authority(metadata.authority())
                    .documentType(metadata.documentType()).current(metadata.current()).build());
            IngestionJobRecord job = jobs.save(IngestionJobRecord.builder()
                    .documentId(document.getId()).status("QUEUED").build());
            producer.send(IngestionJob.builder().jobId(job.getId()).documentId(document.getId())
                    .fileName(document.getFileName()).chunkingStrategy(document.getChunkingStrategy()).build());
            return document;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse or enqueue uploaded document", exception);
        }
    }

    public void markProcessing(IngestionJob message) {
        jobs.findById(message.getJobId()).ifPresent(job -> {
            job.setStatus("PROCESSING");
            job.setAttempts(job.getAttempts() + 1);
            job.setStartedAt(java.time.Instant.now());
            jobs.save(job);
        });
    }

    public void markIndexed(IngestionJob message) {
        documents.findById(message.getDocumentId()).ifPresent(document -> {
            document.setStatus("INDEXED"); documents.save(document);
        });
        jobs.findById(message.getJobId()).ifPresent(job -> {
            job.setStatus("INDEXED"); job.setCompletedAt(java.time.Instant.now()); jobs.save(job);
        });
    }

    public void markFailed(IngestionJob message, Exception error) {
        documents.findById(message.getDocumentId()).ifPresent(document -> {
            document.setStatus("FAILED"); documents.save(document);
        });
        jobs.findById(message.getJobId()).ifPresent(job -> {
            job.setStatus("FAILED"); job.setErrorMessage(error.getMessage());
            job.setCompletedAt(java.time.Instant.now()); jobs.save(job);
        });
    }

    private String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private Metadata metadata(String name, String content, String organization, String authority,
                              String documentType, Boolean current) {
        String lower = (name + " " + content).toLowerCase(Locale.ROOT);
        String org = organization != null ? organization : lower.contains("acme cloud") ? "Acme"
                : lower.contains("techcorp") ? "TechCorp" : "Unknown";
        String inferredAuthority = lower.contains("superseded") || lower.contains("historical") || lower.contains("archived")
                ? "HISTORICAL" : !"TechCorp".equalsIgnoreCase(org) && !"Unknown".equalsIgnoreCase(org)
                ? "EXTERNAL" : lower.contains("faq") || lower.contains("training") ? "REFERENCE" : "AUTHORITATIVE";
        String type = documentType != null ? documentType : lower.contains("faq") ? "FAQ"
                : lower.contains("announcement") ? "ANNOUNCEMENT" : lower.contains("training") ? "TRAINING" : "POLICY";
        String level = authority != null ? authority : inferredAuthority;
        return new Metadata(org, level.toUpperCase(Locale.ROOT), type.toUpperCase(Locale.ROOT),
                current != null ? current : "AUTHORITATIVE".equalsIgnoreCase(level));
    }

    private record Metadata(String organization, String authority, String documentType, Boolean current) { }
}
