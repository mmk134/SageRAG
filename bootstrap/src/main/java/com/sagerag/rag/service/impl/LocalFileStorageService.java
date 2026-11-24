/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sagerag.rag.service.impl;

import com.sagerag.framework.exception.ClientException;
import com.sagerag.framework.exception.ServiceException;
import com.sagerag.rag.dto.StoredFileDTO;
import com.sagerag.rag.service.FileStorageService;
import com.sagerag.rag.util.FileTypeDetector;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘文件存储（默认实现）
 * <p>
 * 文件按 yyyy/MM/dd 目录归档，URL 形如 {@code local://2026/11/24/xxx.pdf}。
 * 相比对象存储少一个必须启动的外部组件，适合本地开发和单机部署；
 * 多实例部署时可通过 {@code rag.storage.type=s3} 切换回 S3 实现。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private static final Tika TIKA = new Tika();
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final String URL_PREFIX = "local://";

    @Value("${rag.storage.local-root:./data/uploads}")
    private String localRoot;

    @Override
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException("上传文件不能为空");
        }
        try (InputStream is = file.getInputStream()) {
            return upload(bucketName, is, file.getSize(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new ServiceException("读取上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public StoredFileDTO upload(String bucketName, InputStream content, long size,
                                String originalFilename, String contentType) {
        if (content == null) {
            throw new ClientException("上传内容不能为空");
        }
        Path target = resolveTarget(originalFilename);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                content.transferTo(out);
            }
        } catch (IOException e) {
            throw new ServiceException("写入本地文件失败: " + e.getMessage());
        }
        String key = root().relativize(target).toString().replace('\\', '/');
        return buildStoredFileDTO(URL_PREFIX + key, originalFilename, contentType, size);
    }

    @Override
    public StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType) {
        if (content == null) {
            throw new ClientException("上传内容不能为空");
        }
        return upload(bucketName, new ByteArrayInputStream(content), content.length, originalFilename, contentType);
    }

    @Override
    public StoredFileDTO reliableUpload(String bucketName, InputStream content, long size,
                                        String originalFilename, String contentType) {
        return upload(bucketName, content, size, originalFilename, contentType);
    }

    @Override
    public InputStream openStream(String url) {
        Path path = resolveUrl(url);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new ClientException("读取本地文件失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteByUrl(String url) {
        try {
            Files.deleteIfExists(resolveUrl(url));
        } catch (IOException e) {
            log.warn("删除本地文件失败: {}", url, e);
        }
    }

    private Path resolveTarget(String originalFilename) {
        String suffix = extractSuffix(originalFilename);
        String key = LocalDate.now().format(DATE_DIR) + "/" + UUID.randomUUID().toString().replace("-", "")
                + (suffix.isBlank() ? "" : "." + suffix);
        Path target = root().resolve(key).normalize();
        if (!target.startsWith(root())) {
            throw new ClientException("非法的文件路径");
        }
        return target;
    }

    private String extractSuffix(String filename) {
        if (filename == null) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    private Path resolveUrl(String url) {
        if (!StringUtils.hasText(url) || !url.startsWith(URL_PREFIX)) {
            throw new ClientException("非法的本地文件地址: " + url);
        }
        String key = url.substring(URL_PREFIX.length());
        Path path = root().resolve(key).normalize();
        if (!path.startsWith(root())) {
            throw new ClientException("非法的文件路径");
        }
        return path;
    }

    private Path root() {
        return Paths.get(localRoot).toAbsolutePath().normalize();
    }

    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                             String contentType, long size) {
        return StoredFileDTO.builder()
                .url(url)
                .detectedType(FileTypeDetector.detectType(originalFilename, contentType))
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }
}
