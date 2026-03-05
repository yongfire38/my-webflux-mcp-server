package com.example.webflux.repository;

import com.example.webflux.model.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {
    Optional<DocumentMetadata> findByFilenameAndChunkIndex(String filename, int chunkIndex);
    List<DocumentMetadata> findByFilename(String filename);
}
