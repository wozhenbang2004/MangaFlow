/*
 * MIT License
 * 
 * Copyright (c) 2025 JUKOMU
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * 
 */
/*
 * Android adaptation of JUKOMU/JMComic-Api-Java (MIT), v1.1.12 / 5a7a4bb4870edb512274dc2adeb4f7c157445321.
 * The upstream JmImageTool segment/format logic and AndroidImageProcessor strip
 * restoration are retained. This Android-only adaptation owns Bitmap cleanup,
 * checks encoder completion and never initializes the desktop AWT fallback.
 */
package io.github.jukomu.jmcomic.core.crypto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;

import io.github.jukomu.jmcomic.api.exception.JmComicException;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.core.constant.JmConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * @author JUKOMU
 * @Description: 内部工具类，负责处理JMComic的图片加解密逻辑
 * @Project: jmcomic-api-java
 * @Date: 2025/10/28
 */
public final class JmImageTool {
    private JmImageTool() {
    }

    /**
     * 对JMComic的图片数据进行解密（重组）
     * 如果根据算法判断图片无需重组，将直接返回原始数据
     *
     * @param imageData 原始的、未解密的图片字节数组
     * @param image     包含解密所需元数据的 JmImage 对象
     * @return 解密（重组）后的图片字节数组
     */
    public static byte[] decryptImage(byte[] imageData, JmImage image) {
        checkInterrupted();
        int numSegments = calculateNumSegments(Long.parseLong(image.scrambleId()),
                Long.parseLong(image.photoId()), image.getFilenameWithoutSuffix());
        if (numSegments == 0) {
            return imageData;
        }

        Bitmap originalBitmap = null;
        Bitmap decryptedBitmap = null;
        try {
            // Keep the upstream software decode, without scaling or changing the source pixels.
            originalBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (originalBitmap == null) {
                throw new JmComicException("禁漫图片还原失败：无法解码下载的图片。");
            }
            checkInterrupted();
            int width = originalBitmap.getWidth();
            int height = originalBitmap.getHeight();
            if (height < numSegments) {
                return imageData;
            }

            // getConfig() can be null for an internal format, but a mutable destination
            // requires a concrete config. BitmapFactory's default decode is software-backed.
            Bitmap.Config config = originalBitmap.getConfig();
            decryptedBitmap = Bitmap.createBitmap(width, height,
                    config != null ? config : Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(decryptedBitmap);
            int segmentHeight = height / numSegments;
            int remainder = height % numSegments;
            int currentY = 0;
            for (int i = 0; i < numSegments; i++) {
                checkInterrupted();
                int ySrc;
                int hSrc = segmentHeight;
                if (i == 0) {
                    hSrc += remainder;
                    ySrc = height - hSrc;
                } else {
                    ySrc = height - (segmentHeight * (i + 1)) - remainder;
                }
                Rect srcRect = new Rect(0, ySrc, width, ySrc + hSrc);
                Rect destRect = new Rect(0, currentY, width, currentY + hSrc);
                canvas.drawBitmap(originalBitmap, srcRect, destRect, null);
                currentY += hSrc;
            }

            checkInterrupted();
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                Bitmap.CompressFormat format = getCompressFormat(getFormatName(image.filename()));
                if (!decryptedBitmap.compress(format, 100, output)) {
                    throw new JmComicException("禁漫图片还原失败：图片编码器未生成有效数据。");
                }
                checkInterrupted();
                return output.toByteArray();
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (JmComicException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new JmComicException("禁漫图片还原失败：图片编码未能完成。", failure);
        } catch (RuntimeException failure) {
            throw new JmComicException("禁漫图片还原失败。", failure);
        } finally {
            // Both bitmaps belong only to this invocation, including a failed/cancelled one.
            if (decryptedBitmap != null) {
                decryptedBitmap.recycle();
            }
            if (originalBitmap != null) {
                originalBitmap.recycle();
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("JM image restoration was cancelled.");
        }
    }

    private static Bitmap.CompressFormat getCompressFormat(String formatName) {
        return switch (formatName.toLowerCase()) {
            case "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG;
            case "png" -> Bitmap.CompressFormat.PNG;
            case "webp" -> Bitmap.CompressFormat.WEBP;
            default -> Bitmap.CompressFormat.JPEG;
        };
    }

    /**
     * 根据 scrambleId, photoId, 和图片文件名计算图片被切割的块数
     *
     * @param scrambleId            本子的 scramble ID
     * @param photoId               章节的 ID (在Python代码中被称为 aid)
     * @param filenameWithoutSuffix 不带后缀的文件名
     * @return 分割数 (如果无需分割则为0)
     */
    public static int calculateNumSegments(long scrambleId, long photoId, String filenameWithoutSuffix) {
        if (photoId < scrambleId) {
            return 0;
        }

        if (photoId < JmConstants.SCRAMBLE_268850) {
            return 10;
        }

        int x = (photoId < JmConstants.SCRAMBLE_421926) ? 10 : 8;
        String s = photoId + filenameWithoutSuffix;
        String md5 = JmCryptoTool.md5Hex(s);

        // 获取MD5哈希值的最后一个字符的ASCII码
        char lastChar = md5.charAt(md5.length() - 1);
        int num = lastChar;

        num %= x;
        num = num * 2 + 2;

        return num;
    }

    /**
     * 从文件名中提取图片格式，用于 ImageIO.write
     *
     * @param filename 包含后缀的文件名
     * @return 图片格式字符串 (例如 "jpg", "png")
     */
    public static String getFormatName(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == filename.length() - 1) {
            return "jpeg"; // 默认返回jpeg
        }
        return filename.substring(dotIndex + 1).toLowerCase();
    }
}
