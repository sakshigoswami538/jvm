package com.jvmobservability.demo.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Constructs the MinioClient bean from application.properties values.
 *
 * MinioClient is a heavyweight singleton — it holds an OkHttp connection
 * pool internally.  Those connections are off-heap (direct ByteBuffers),
 * so you will see them reflected in:
 *   jvm.buffer.count{id=direct}
 *   jvm.buffer.memory.used{id=direct}
 * even at idle, because the pool keeps connections warm.
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        // Guard against a doubled scheme (e.g. "http://http://host:port") that
        // occurs when the env var already contains "http://" and the YAML template
        // also had "http://" baked in, resulting in an invalid URL that MinioClient
        // rejects with "no path allowed in endpoint".
        String url = endpoint;
        if (url.startsWith("http://http://"))  url = url.substring("http://".length());
        if (url.startsWith("https://https://")) url = url.substring("https://".length());

        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .build();
    }
}
