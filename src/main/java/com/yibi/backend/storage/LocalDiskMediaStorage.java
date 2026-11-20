package com.yibi.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps media files on the server's disk under {@code app.media.dir}, outside the web root; they are served only through
 * the signed media endpoint. Keys look like {@code 2026/11/<uuid>.mp4}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.media.storage", havingValue = "local", matchIfMissing = true)
public class LocalDiskMediaStorage implements MediaStorage {

    static final Pattern KEY = Pattern.compile("^\\d{4}/\\d{2}/[0-9a-f-]{36}\\.[a-z0-9]{1,5}$");

    private final Path root;

    public LocalDiskMediaStorage(@Value("${app.media.dir:./media-storage}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Media files are stored in {}", root);
    }

    @Override
    public String store(InputStream content, String extension) throws IOException {
        String ext = extension == null ? "bin" : extension.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 5) ext = "bin";
        LocalDate today = LocalDate.now();
        String key = "%04d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID(), ext);
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "upload-", ".part");
        try {
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return key;
    }

    @Override
    public Resource load(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) throw new FileNotFoundException(key);
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Could not delete media {}: {}", key, ex.getMessage());
        }
    }

    /** Only keys this class made are accepted, so a key can never point outside the media folder. */
    Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Bad media key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Bad media key");
        return path;
    }
}
