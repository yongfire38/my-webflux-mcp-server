package com.example.webflux.repository;

import com.example.webflux.model.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {
    Optional<DocumentMetadata> findByFilenameAndChunkIndex(String filename, int chunkIndex);
    List<DocumentMetadata> findByFilename(String filename);

    @Query("SELECT DISTINCT m.filename FROM DocumentMetadata m")
    List<String> findAllDistinctFilenames();

    @Transactional
    void deleteByFilename(String filename);
}
