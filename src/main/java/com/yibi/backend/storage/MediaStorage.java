package com.yibi.backend.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where learners' uploaded audio and video files live. Callers keep only the returned key, so moving from the local
 * disk to S3/R2 is a new implementation of this interface (selected by {@code app.media.storage}) and nothing else.
 */
public interface MediaStorage {

    /**
     * Stores the stream under a new key.
     *
     * @param extension file extension without the dot, kept so players and the transcriber recognise the format
     * @return the key to save with the video
     */
    String store(InputStream content, String extension) throws IOException;

    /** The stored file for reading; it supports byte ranges so a player can seek. */
    Resource load(String key) throws IOException;

    /** Removes the file; a key that is already gone is not an error. */
    void delete(String key);
}
