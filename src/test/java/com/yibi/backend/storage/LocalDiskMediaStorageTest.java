package com.yibi.backend.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskMediaStorageTest {

    @TempDir
    Path dir;

    @Test
    void storesLoadsAndDeletes() throws Exception {
        LocalDiskMediaStorage storage = new LocalDiskMediaStorage(dir.toString());
        String key = storage.store(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "MP3");
        assertThat(key).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.mp3");
        assertThat(storage.load(key).getContentAsByteArray()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        storage.delete(key);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(FileNotFoundException.class);
        storage.delete(key); // already gone: no error
    }

    @Test
    void refusesKeysItDidNotMake() throws Exception {
        LocalDiskMediaStorage storage = new LocalDiskMediaStorage(dir.toString());
        for (String key : new String[]{"../secret.txt", "2026/11/../../x.mp3", "/etc/passwd", "a.mp3"}) {
            assertThatThrownBy(() -> storage.resolve(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void oddExtensionsBecomeBin() throws Exception {
        LocalDiskMediaStorage storage = new LocalDiskMediaStorage(dir.toString());
        assertThat(storage.store(new ByteArrayInputStream(new byte[]{1}), "../../x")).endsWith(".x");
        assertThat(storage.store(new ByteArrayInputStream(new byte[]{1}), "toolongext")).endsWith(".bin");
    }
}
