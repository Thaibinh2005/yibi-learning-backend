package com.yibi.backend.service;

import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.repository.UserRepository;
import com.yibi.backend.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Pictures learners upload on the account page. Every upload is decoded and drawn again as a 256×256 JPEG,
 * so whatever is stored is a plain picture: a file that is not an image is refused, and nothing hidden in the
 * original (scripts, metadata, location) is kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int SIZE = 256;
    /** Decoding a huge picture takes a lot of memory; phone photos stay well below this. */
    private static final long MAX_PIXELS = 40_000_000L;

    private final UserRepository users;
    private final MediaStorage storage;

    /** Replaces the learner's uploaded picture; the old file is deleted once the new one is saved. */
    public User upload(User user, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Hãy chọn một ảnh.");
        if (file.getSize() > MAX_BYTES) throw new BadRequestException("Ảnh quá lớn (tối đa 5 MB).");

        byte[] jpeg = squareJpeg(read(file));
        String key = storage.store(new ByteArrayInputStream(jpeg), "jpg");
        String old = user.getAvatarKey();
        user.setAvatarKey(key);
        User saved = users.save(user);
        if (old != null) storage.delete(old);
        log.info("Avatar uploaded for user {}", user.getId());
        return saved;
    }

    /** Removes the uploaded picture; the Google picture (if any) or the initial shows again. */
    public User remove(User user) {
        String old = user.getAvatarKey();
        if (old == null) return user;
        user.setAvatarKey(null);
        User saved = users.save(user);
        storage.delete(old);
        return saved;
    }

    /** The uploaded picture of a user, or null when they have none. */
    public Resource load(UUID userId) {
        String key = users.findById(userId).map(User::getAvatarKey).orElse(null);
        if (key == null) return null;
        try {
            return storage.load(key);
        } catch (IOException ex) {
            log.warn("Avatar file missing for user {}: {}", userId, ex.getMessage());
            return null;
        }
    }

    private static BufferedImage read(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream(); var stream = ImageIO.createImageInputStream(in)) {
            var readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) throw notAnImage();
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new BadRequestException("Ảnh có độ phân giải quá lớn.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw notAnImage();
        }
    }

    /** The centre square, scaled to SIZE, on white (a transparent PNG would otherwise turn black in JPEG). */
    static byte[] squareJpeg(BufferedImage source) throws IOException {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "jpg", bytes)) throw new IOException("No JPEG writer");
        return bytes.toByteArray();
    }

    private static BadRequestException notAnImage() {
        return new BadRequestException("File không phải ảnh hợp lệ. Hãy chọn ảnh JPG, PNG hoặc GIF.");
    }
}
