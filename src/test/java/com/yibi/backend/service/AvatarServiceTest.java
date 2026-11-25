package com.yibi.backend.service;

import com.yibi.backend.entity.User;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.repository.UserRepository;
import com.yibi.backend.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AvatarServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MediaStorage storage = mock(MediaStorage.class);
    private final AvatarService service = new AvatarService(users, storage);

    @Test
    void wideImageBecomesSquareJpeg() throws Exception {
        byte[] jpeg = AvatarService.squareJpeg(new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(out.getWidth()).isEqualTo(AvatarService.SIZE);
        assertThat(out.getHeight()).isEqualTo(AvatarService.SIZE);
    }

    @Test
    void uploadStoresNewPictureAndDeletesOldOne() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 500, BufferedImage.TYPE_INT_RGB), "png", png);
        User user = User.builder().avatarKey("2026/01/00000000-0000-0000-0000-000000000000.jpg").build();
        when(storage.store(any(InputStream.class), eq("jpg"))).thenReturn("2026/11/11111111-1111-1111-1111-111111111111.jpg");
        when(users.save(user)).thenReturn(user);

        service.upload(user, new MockMultipartFile("file", "me.png", "image/png", png.toByteArray()));

        assertThat(user.getAvatarKey()).startsWith("2026/11/1111");
        verify(storage).delete("2026/01/00000000-0000-0000-0000-000000000000.jpg");
    }

    @Test
    void fileThatIsNotAnImageIsRefused() {
        MockMultipartFile html = new MockMultipartFile("file", "x.png", "image/png", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> service.upload(new User(), html)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(storage);
    }
}
