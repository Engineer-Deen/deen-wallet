package com.glr.deenwallet.photo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores profile photos. Whatever is uploaded is decoded and re-encoded as a 256x256 JPEG, so only a real image is kept. */
@Service
@RequiredArgsConstructor
public class UserPhotoService {

    private static final int SIZE = 256;
    private static final int MAX_BASE64_CHARS = 2_800_000;
    private static final int MAX_SIDE = 8000;

    private final UserPhotoRepository repo;

    @Transactional
    public Instant save(UUID userId, String image) {
        byte[] jpeg = normalize(decode(image));
        UserPhoto p = repo.findById(userId).orElseGet(() -> UserPhoto.builder().userId(userId).build());
        p.setContent(jpeg);
        p.setUpdatedAt(Instant.now());
        repo.save(p);
        return p.getUpdatedAt();
    }

    @Transactional
    public void remove(UUID userId) {
        if (repo.existsById(userId)) repo.deleteById(userId);
    }

    /** {"image": data-url or null, "version": millis or 0}. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        UserPhoto p = repo.findById(userId).orElse(null);
        out.put("image", p == null ? null : toDataUrl(p));
        out.put("version", p == null ? 0L : p.getUpdatedAt().toEpochMilli());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMany(List<UUID> ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (UserPhoto p : repo.findAllById(ids)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("image", toDataUrl(p));
            one.put("version", p.getUpdatedAt().toEpochMilli());
            out.put(p.getUserId().toString(), one);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> versions() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : repo.versions()) {
            out.put(String.valueOf(row[0]), ((Instant) row[1]).toEpochMilli());
        }
        return out;
    }

    private static String toDataUrl(UserPhoto p) {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(p.getContent());
    }

    private static byte[] decode(String image) {
        if (image == null || image.isBlank()) throw new IllegalArgumentException("Choose a photo first.");
        String s = image.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) s = s.substring(comma + 1);
        if (s.length() > MAX_BASE64_CHARS) throw new IllegalArgumentException("That photo is too large.");
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That photo could not be read.");
        }
    }

    private static byte[] normalize(byte[] raw) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("Choose a JPEG or PNG photo.");
            ImageReader reader = readers.next();
            BufferedImage src;
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 16 || h < 16 || w > MAX_SIDE || h > MAX_SIDE) {
                    throw new IllegalArgumentException("That photo size is not supported.");
                }
                src = reader.read(0);
            } finally {
                reader.dispose();
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int side = Math.min(w, h);
            int x = (w - side) / 2;
            int y = (h - side) / 2;
            BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, SIZE, SIZE);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
            } finally {
                g.dispose();
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(out, null, null), param);
                }
                return bos.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("That photo could not be read.");
        }
    }
}
