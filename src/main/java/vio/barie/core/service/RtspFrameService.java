package vio.barie.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RtspFrameService {

    @Value("${camera.rtsp.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${camera.rtsp.timeout-ms:10000}")
    private long timeoutMs;

    public byte[] capture(String rtspUrl) {
        validateRtspUrl(rtspUrl);

        List<String> command = List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error",
                "-rtsp_transport", "tcp",
                "-i", rtspUrl,
                "-frames:v", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "-"
        );

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] image = readWithLimit(process.getInputStream(), 10 * 1024 * 1024);
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("RTSP capture timed out");
            }
            if (process.exitValue() != 0 || image.length == 0 || !isJpeg(image)) {
                throw new IllegalStateException("Unable to capture a frame from RTSP stream");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start ffmpeg. Ensure ffmpeg is installed and configured", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RTSP capture was interrupted", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static void validateRtspUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("rtspUrl must be a valid rtsp:// URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rtspUrl must be a valid rtsp:// URL", e);
        }
    }

    private static byte[] readWithLimit(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Captured frame exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isJpeg(byte[] image) {
        return image.length >= 4
                && (image[0] & 0xff) == 0xff
                && (image[1] & 0xff) == 0xd8
                && (image[image.length - 2] & 0xff) == 0xff
                && (image[image.length - 1] & 0xff) == 0xd9;
    }
}
