package com.jvmobservability.demo.service;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Handles all MinIO object storage operations.
 *
 * Upload flow:
 *   1. Ensure the bucket exists (create on first run).
 *   2. Generate a unique object name: UUID + original filename.
 *   3. Stream the MultipartFile bytes directly to MinIO.
 *   4. Return the public URL so the caller can store it in MongoDB.
 *
 * Delete flow:
 *   1. Extract the object name from the stored URL.
 *   2. Call removeObject to permanently delete the MP3 from MinIO.
 */
@Service
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Uploads an MP3 file to MinIO and returns its public URL.
     *
     * @param file the multipart MP3 file from the HTTP request
     * @return full URL: http://<endpoint>/<bucket>/<uuid>-<filename>
     */
    public String uploadFile(MultipartFile file) {
        try {
            // Create bucket if it does not exist yet
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build());
            }

            // Unique object name prevents collisions for songs with the same filename
            String objectName = UUID.randomUUID() + "-" + file.getOriginalFilename();

            // Stream the file bytes to MinIO (no temp file on disk)
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());

            // Return the direct-access URL stored in MongoDB
            return endpoint + "/" + bucketName + "/" + objectName;

        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file to MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes an MP3 from MinIO using the URL stored in MongoDB.
     *
     * @param fileUrl the URL returned by uploadFile()
     */
    public void deleteFile(String fileUrl) {
        try {
            // Extract the object name from: http://host/bucket/objectName
            String prefix = endpoint + "/" + bucketName + "/";
            String objectName = fileUrl.substring(prefix.length());

            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());

        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file from MinIO: " + e.getMessage(), e);
        }
    }
}
