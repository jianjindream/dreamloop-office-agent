package com.jianjin.assistant.infrastructure.platform;

import com.jianjin.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class KafkaConnector {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnector.class);

    private final AppConfig cfg;
    private volatile KafkaProducer<String, String> producer;
    private volatile String status = "disconnected";

    public KafkaConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        KafkaProducer<String, String> candidate = null;
        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getKafka().getBrokers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            candidate = new KafkaProducer<>(props);
            candidate.partitionsFor(cfg.getKafka().getTopic());
            producer = candidate;
            candidate = null;
            status = "connected";
            log.info("Kafka 已连接: {}", cfg.getKafka().getBrokers());
        } catch (Exception e) {
            log.warn("Kafka 连接失败: {} (事件将输出到日志)", e.getMessage());
            status = "disconnected";
            producer = null;
        } finally {
            if (candidate != null) {
                try { candidate.close(); } catch (Exception ignored) {}
            }
        }
    }

    public KafkaProducer<String, String> producer() { return producer; }
    public boolean available() { return "connected".equals(status) && producer != null; }
    public String status() { return status; }

    /** 发送事件；未连接时降级到日志。 */
    public void publish(String eventType, String payload) {
        if (available()) {
            try {
                producer.send(new ProducerRecord<>(cfg.getKafka().getTopic(), eventType, payload),
                        (metadata, error) -> {
                            if (error != null) {
                                log.warn("Kafka 异步写入失败: topic={}, type={}, cause={}",
                                        cfg.getKafka().getTopic(), eventType, error.getMessage());
                                log.info("[Kafka-fallback] {}: {}", eventType, payload);
                            }
                        });
            } catch (Exception e) {
                log.warn("Kafka 写入失败: {}", e.getMessage());
                log.info("[Kafka-fallback] {}: {}", eventType, payload);
            }
        } else {
            log.info("[Kafka-fallback] {}: {}", eventType, payload);
        }
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            try { producer.close(); } catch (Exception ignored) {}
        }
    }
}
