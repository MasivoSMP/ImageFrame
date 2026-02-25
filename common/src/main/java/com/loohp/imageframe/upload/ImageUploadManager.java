/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.upload;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.utils.FileUtils;
import com.loohp.imageframe.utils.JarUtils;
import com.loohp.imageframe.utils.SizeLimitedByteArrayOutputStream;
import com.loohp.platformscheduler.Scheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.fileupload.MultipartStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ImageUploadManager implements AutoCloseable {

    public static final int EXPIRATION = 300000;

    private final HttpServer server;
    private final File webRootDir;
    private final File uploadDir;
    private final Map<UUID, PendingUpload> pendingUploads;
    private final AtomicLong imagesUploadedCounter;

    public ImageUploadManager(boolean enabled, String host, int port) {
        try {
            this.webRootDir = new File(ImageFrame.plugin.getDataFolder(), "upload/web");
            this.uploadDir = new File(ImageFrame.plugin.getDataFolder(), "upload/images");
            FileUtils.removeFolderRecursively(uploadDir);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            if (!webRootDir.exists()) {
                webRootDir.mkdirs();
            }
            JarUtils.copyFolderFromJar("upload/web", ImageFrame.plugin.getDataFolder(), JarUtils.CopyOption.COPY_IF_NOT_EXIST);
            Cache<UUID, PendingUpload> cache = CacheBuilder.newBuilder()
                    .expireAfterAccess(EXPIRATION, TimeUnit.MILLISECONDS)
                    .removalListener((RemovalNotification<UUID, PendingUpload> notification) -> {
                        if (notification.wasEvicted()) {
                            PendingUpload pendingUpload = notification.getValue();
                            if (pendingUpload != null) {
                                pendingUpload.getFuture().completeExceptionally(new LinkTimeoutException());
                            }
                        }
                    })
                    .build();
            this.pendingUploads = cache.asMap();
            this.imagesUploadedCounter = new AtomicLong(0);
            if (enabled) {
                System.setProperty("sun.net.httpserver.maxReqTime", "30");
                System.setProperty("sun.net.httpserver.maxRspTime", "30");
                this.server = HttpServer.create(new InetSocketAddress(host, port), 8);
                this.server.createContext("/", new FileHandler());
                this.server.createContext("/upload", new UploadHandler());
                this.server.setExecutor(Executors.newFixedThreadPool(8));
                this.server.start();
            } else {
                this.server = null;
            }
        } catch (BindException e) {
            throw new RuntimeException("Unable to start ImageFrame upload server (Perhaps there is a network port clash?)", e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to start ImageFrame upload server", e);
        }
    }

    public PendingUpload newPendingUpload(UUID user) {
        PendingUpload existing = pendingUploads.remove(user);
        if (existing != null) {
            existing.getFuture().completeExceptionally(new LinkTimeoutException());
        }
        PendingUpload pendingUpload = PendingUpload.create();
        pendingUploads.put(user, pendingUpload);
        return pendingUpload;
    }

    public AtomicLong getImagesUploadedCounter() {
        return imagesUploadedCounter;
    }

    public boolean wasUploaded(String url) {
        try {
            File file = Paths.get(new URL(url).toURI()).toFile();
            return uploadDir.equals(file.getParentFile());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return server != null;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                    return;
                }
                if (!webRootDir.exists()) {
                    webRootDir.mkdirs();
                }
                File file = resolvePath(webRootDir.toPath().toAbsolutePath(), Paths.get("." + exchange.getRequestURI().getPath())).toFile();
                byte[] bytes;
                if (file.exists()) {
                    if (file.isDirectory()) {
                        bytes = Files.readAllBytes(new File(file, "index.html").toPath());
                    } else {
                        bytes = Files.readAllBytes(file.toPath());
                    }
                } else {
                    bytes = Files.readAllBytes(new File(webRootDir, "index.html").toPath());
                }
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } finally {
                exchange.close();
            }
        }

        private Path resolvePath(Path baseDirPath, Path userPath) {
            Path resolvedPath = baseDirPath.resolve(userPath).normalize();
            if (!resolvedPath.startsWith(baseDirPath)) {
                return baseDirPath;
            }
            return resolvedPath;
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // Extract query parameters
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> queryParams = parseQueryParams(query);
                String user = queryParams.get("user");
                String id = queryParams.get("id");

                PendingUpload pendingUpload = findPendingUpload(user, id);
                if (pendingUpload == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid or missing user or id\"}");
                    return;
                }

                List<String> contentType = Arrays.asList(exchange.getRequestHeaders().getFirst("Content-Type").split(";"));
                if (!contentType.get(0).trim().equals("multipart/form-data")) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid content type\"}");
                    return;
                }
                byte[] boundary = contentType.stream()
                        .map(s -> s.trim())
                        .filter(s -> s.startsWith("boundary="))
                        .findFirst()
                        .map(s -> s.substring("boundary=".length()).getBytes(StandardCharsets.UTF_8))
                        .orElse(null);
                if (boundary == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid multipart boundary\"}");
                    return;
                }

                SizeLimitedByteArrayOutputStream output = new SizeLimitedByteArrayOutputStream(ImageFrame.maxImageFileSize);
                String uploadedContentType = null;
                String uploadedFilename = null;
                boolean foundImagePart = false;

                try (InputStream inputStream = exchange.getRequestBody()) {
                    MultipartStream multipartStream = new MultipartStream(inputStream, boundary, 2048, null);
                    boolean nextPart = multipartStream.skipPreamble();
                    while (nextPart) {
                        String rawHeaders = multipartStream.readHeaders();
                        String name = extractHeaderParameter(rawHeaders, "name");
                        if ("image".equals(name)) {
                            uploadedContentType = extractPartContentType(rawHeaders);
                            uploadedFilename = extractHeaderParameter(rawHeaders, "filename");
                            multipartStream.readBodyData(output);
                            foundImagePart = true;
                            break;
                        } else {
                            multipartStream.discardBodyData();
                            nextPart = multipartStream.readBoundary();
                        }
                    }
                }
                if (!foundImagePart) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing image field\"}");
                    return;
                }
                byte[] fileData = output.toByteArray();
                if (fileData.length == 0) {
                    sendResponse(exchange, 400, "{\"error\":\"Uploaded file is empty\"}");
                    return;
                }
                String extension = resolveImageExtension(uploadedContentType, uploadedFilename, fileData);
                if (extension == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Unsupported image format\"}");
                    return;
                }

                // Ensure upload directory exists
                if (!uploadDir.exists()) {
                    uploadDir.mkdir();
                }

                // Save the file with UUID as the filename
                File outputFile = new File(uploadDir, id + extension);
                Files.write(outputFile.toPath(), fileData);
                imagesUploadedCounter.incrementAndGet();

                pendingUploads.remove(UUID.fromString(user));
                pendingUpload.getFuture().complete(outputFile);
                Scheduler.runTaskLaterAsynchronously(ImageFrame.plugin, () -> outputFile.delete(), EXPIRATION / 50);

                // Send response
                sendResponse(exchange, 200, "{\"message\":\"File uploaded successfully\"}");
            } finally {
                exchange.close();
            }
        }

        // Parse query parameters from URL
        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> map = new HashMap<>();
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=");
                    if (keyValue.length == 2) {
                        map.put(keyValue[0], keyValue[1]);
                    }
                }
            }
            return map;
        }

        private PendingUpload findPendingUpload(String user, String id) {
            try {
                PendingUpload pendingUpload = pendingUploads.get(UUID.fromString(user));
                if (!pendingUpload.getId().equals(UUID.fromString(id))) {
                    return null;
                }
                return pendingUpload;
            } catch (Exception e) {
                return null;
            }
        }

        // Send JSON response
        private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, message.length());
            exchange.getResponseBody().write(message.getBytes());
            exchange.getResponseBody().close();
        }

        private String extractPartContentType(String rawHeaders) {
            for (String headerLine : rawHeaders.split("\\r?\\n")) {
                int colonIndex = headerLine.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                String headerName = headerLine.substring(0, colonIndex).trim();
                if ("content-type".equalsIgnoreCase(headerName)) {
                    return headerLine.substring(colonIndex + 1).trim();
                }
            }
            return null;
        }

        private String extractHeaderParameter(String rawHeaders, String parameterName) {
            for (String headerLine : rawHeaders.split("\\r?\\n")) {
                int colonIndex = headerLine.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                String headerName = headerLine.substring(0, colonIndex).trim();
                if (!"content-disposition".equalsIgnoreCase(headerName)) {
                    continue;
                }
                String[] values = headerLine.substring(colonIndex + 1).split(";");
                for (String value : values) {
                    String part = value.trim();
                    int equalsIndex = part.indexOf('=');
                    if (equalsIndex <= 0) {
                        continue;
                    }
                    String key = part.substring(0, equalsIndex).trim();
                    if (!parameterName.equalsIgnoreCase(key)) {
                        continue;
                    }
                    String raw = part.substring(equalsIndex + 1).trim();
                    return stripSurroundingQuotes(raw);
                }
            }
            return null;
        }

        private String stripSurroundingQuotes(String value) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private String resolveImageExtension(String uploadedContentType, String uploadedFilename, byte[] fileData) {
            String fromType = extensionFromMimeType(uploadedContentType);
            if (fromType != null) {
                return fromType;
            }

            String fromName = extensionFromFilename(uploadedFilename);
            if (fromName != null) {
                return fromName;
            }

            return extensionFromMagic(fileData);
        }

        private String extensionFromMimeType(String uploadedContentType) {
            if (uploadedContentType == null) {
                return null;
            }
            String contentType = uploadedContentType.trim().toLowerCase(Locale.ROOT);
            if ("image/gif".equals(contentType)) {
                return ".gif";
            }
            if ("image/png".equals(contentType)) {
                return ".png";
            }
            if ("image/jpeg".equals(contentType) || "image/jpg".equals(contentType)) {
                return ".jpg";
            }
            if ("image/webp".equals(contentType)) {
                return ".webp";
            }
            return null;
        }

        private String extensionFromFilename(String uploadedFilename) {
            if (uploadedFilename == null) {
                return null;
            }
            String filename = uploadedFilename.trim().toLowerCase(Locale.ROOT);
            if (filename.endsWith(".gif")) {
                return ".gif";
            }
            if (filename.endsWith(".png")) {
                return ".png";
            }
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                return ".jpg";
            }
            if (filename.endsWith(".webp")) {
                return ".webp";
            }
            return null;
        }

        private String extensionFromMagic(byte[] fileData) {
            if (fileData.length >= 6) {
                if (fileData[0] == 'G' && fileData[1] == 'I' && fileData[2] == 'F' && fileData[3] == '8' && (fileData[4] == '7' || fileData[4] == '9') && fileData[5] == 'a') {
                    return ".gif";
                }
            }
            if (fileData.length >= 8) {
                if ((fileData[0] & 0xFF) == 0x89 && fileData[1] == 'P' && fileData[2] == 'N' && fileData[3] == 'G' && (fileData[4] & 0xFF) == 0x0D && (fileData[5] & 0xFF) == 0x0A && (fileData[6] & 0xFF) == 0x1A && (fileData[7] & 0xFF) == 0x0A) {
                    return ".png";
                }
            }
            if (fileData.length >= 3) {
                if ((fileData[0] & 0xFF) == 0xFF && (fileData[1] & 0xFF) == 0xD8 && (fileData[2] & 0xFF) == 0xFF) {
                    return ".jpg";
                }
            }
            if (fileData.length >= 12) {
                if (fileData[0] == 'R' && fileData[1] == 'I' && fileData[2] == 'F' && fileData[3] == 'F' && fileData[8] == 'W' && fileData[9] == 'E' && fileData[10] == 'B' && fileData[11] == 'P') {
                    return ".webp";
                }
            }
            return null;
        }
    }

    public static class LinkTimeoutException extends Exception {

        public LinkTimeoutException() {
            super();
        }

        public LinkTimeoutException(String message) {
            super(message);
        }

        public LinkTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }

        public LinkTimeoutException(Throwable cause) {
            super(cause);
        }

    }
}
