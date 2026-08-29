/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.feature.media;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import id.jawa.proto.Wa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads and decrypts WhatsApp media attachments from a Wa.Message or direct URLs.
 */
public final class MediaDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(MediaDownloader.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private MediaDownloader() {}

    /**
     * Download and decrypt media from a Wa.Message (Image, Video, Audio, Document, Sticker).
     *
     * @param msg the inbound Wa.Message protobuf payload
     * @return raw decrypted file byte array, or null if message has no supported media payload
     */
    public static byte[] download(Wa.Message msg) {
        if (msg == null) return null;

        if (msg.hasImageMessage()) {
            var img = msg.getImageMessage();
            return fetchAndDecrypt(img.getUrl(), img.getMediaKey().toByteArray(), MediaCrypto.MediaType.IMAGE);
        }
        if (msg.hasVideoMessage()) {
            var vid = msg.getVideoMessage();
            return fetchAndDecrypt(vid.getUrl(), vid.getMediaKey().toByteArray(), MediaCrypto.MediaType.VIDEO);
        }
        if (msg.hasAudioMessage()) {
            var aud = msg.getAudioMessage();
            return fetchAndDecrypt(aud.getUrl(), aud.getMediaKey().toByteArray(), MediaCrypto.MediaType.AUDIO);
        }
        if (msg.hasDocumentMessage()) {
            var doc = msg.getDocumentMessage();
            return fetchAndDecrypt(doc.getUrl(), doc.getMediaKey().toByteArray(), MediaCrypto.MediaType.DOCUMENT);
        }
        if (msg.hasStickerMessage()) {
            var stk = msg.getStickerMessage();
            return fetchAndDecrypt(stk.getUrl(), stk.getMediaKey().toByteArray(), MediaCrypto.MediaType.IMAGE);
        }

        return null;
    }

    /**
     * Download and decrypt media from a direct CDN URL.
     */
    public static byte[] downloadByUrl(String url, byte[] mediaKey, byte[] fileEncSha256, MediaCrypto.MediaType type) throws IOException, InterruptedException {
        return fetchAndDecrypt(url, mediaKey, type);
    }

    /**
     * Download and decrypt media using directPath and MediaConn host list.
     */
    public static byte[] downloadByDirectPath(MediaConn conn, String directPath, byte[] mediaKey, byte[] fileEncSha256, MediaCrypto.MediaType type) throws IOException, InterruptedException {
        if (conn == null || conn.hosts().isEmpty()) {
            throw new IllegalArgumentException("MediaConn has no hosts");
        }
        String path = directPath.startsWith("/") ? directPath : "/" + directPath;
        for (String host : conn.hosts()) {
            String fullUrl = "https://" + host + path;
            byte[] res = fetchAndDecrypt(fullUrl, mediaKey, type);
            if (res != null) return res;
        }
        throw new IOException("Failed downloading media from all MediaConn hosts");
    }

    /**
     * Fetch encrypted bytes from media URL and decrypt using mediaKey and mediaType.
     */
    public static byte[] fetchAndDecrypt(String url, byte[] mediaKey, MediaCrypto.MediaType type) {
        if (url == null || url.isBlank() || mediaKey == null || mediaKey.length == 0) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.warn("Media HTTP download failed with status {}: {}", response.statusCode(), url);
                return null;
            }

            byte[] encBytes = response.body();
            return MediaCrypto.decrypt(encBytes, mediaKey, type);
        } catch (Exception e) {
            LOG.warn("Failed downloading and decrypting media from {}: {}", url, e.toString());
            return null;
        }
    }
}