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

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Kafka producer with a durable file outbox and automatic reconnection. */
@Component
public class KafkaConnector {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnector.class);
    private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final AppConfig cfg;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile KafkaProducer<String, String> producer;
    private volatile String status = "disconnected";
    private Path outboxDir;
    private ScheduledExecutorService worker;

    public KafkaConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            outboxDir = Path.of(cfg.getKafka().getOutboxDir()).toAbsolutePath().normalize();
            Files.createDirectories(outboxDir);
            recoverTemporaryFiles();
            pending.set(listOutboxFiles().size());
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 Kafka outbox: " + e.getMessage(), e);
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "kafka-outbox");
            thread.setDaemon(true);
            return thread;
        });
        connectIfNeeded();
        long interval = Math.max(1000, cfg.getKafka().getReconnectIntervalMs());
        worker.scheduleWithFixedDelay(this::flushOutbox, 0, interval, TimeUnit.MILLISECONDS);
    }

    private synchronized boolean connectIfNeeded() {
        if (producer != null) return true;
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
            log.info("Kafka 已连接: {}, pending={}", cfg.getKafka().getBrokers(), pending.get());
            return true;
        } catch (Exception e) {
            status = "disconnected";
            log.warn("Kafka 连接失败，将保留 outbox 并重试: {}", e.getMessage());
            return false;
        } finally {
            if (candidate != null) {
                try { candidate.close(Duration.ZERO); } catch (Exception ignored) { }
            }
        }
    }

    public KafkaProducer<String, String> producer() { return producer; }
    public boolean available() { return "connected".equals(status) && producer != null; }
    public String status() { return status + (pending.get() > 0 ? " (pending=" + pending.get() + ")" : ""); }

    /** Persist first, then deliver asynchronously. Successful Kafka acknowledgement removes the file. */
    public void publish(String eventType, String payload) {
        try {
            persist(eventType, payload);
            if (worker != null) worker.execute(this::flushOutbox);
        } catch (Exception e) {
            log.error("Kafka outbox 写入失败: type={}, payload_sha256={}, cause={}",
                    eventType, sha256(payload), e.getMessage());
        }
    }

    private synchronized void persist(String eventType, String payload) throws Exception {
        int limit = Math.max(1, cfg.getKafka().getMaxPendingEvents());
        if (pending.get() >= limit) throw new IllegalStateException("outbox 已达到上限 " + limit);
        String fileName = String.format("%013d-%s.evt", System.currentTimeMillis(), UUID.randomUUID());
        Path target = outboxDir.resolve(fileName);
        Path temp = outboxDir.resolve(fileName + ".tmp");
        String encoded = encode(eventType) + "\n" + encode(payload == null ? "" : payload);
        Files.writeString(temp, encoded, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
        pending.incrementAndGet();
    }

    private void flushOutbox() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            if (!connectIfNeeded()) return;
            for (Path path : listOutboxFiles()) {
                PendingEvent event;
                try {
                    event = read(path);
                } catch (Exception invalid) {
                    Path quarantined = path.resolveSibling(path.getFileName() + ".bad");
                    Files.move(path, quarantined, StandardCopyOption.REPLACE_EXISTING);
                    pending.updateAndGet(value -> Math.max(0, value - 1));
                    log.error("Kafka outbox 文件损坏，已隔离: file={}, cause={}",
                            quarantined.getFileName(), invalid.getMessage());
                    continue;
                }
                try {
                    producer.send(new ProducerRecord<>(cfg.getKafka().getTopic(), event.type(), event.payload()))
                            .get(6, TimeUnit.SECONDS);
                    if (Files.deleteIfExists(path)) pending.updateAndGet(value -> Math.max(0, value - 1));
                } catch (Exception e) {
                    log.warn("Kafka outbox 投递失败，将重连重试: file={}, cause={}",
                            path.getFileName(), e.getMessage());
                    disconnect();
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Kafka outbox 刷新失败: {}", e.getMessage());
        } finally {
            flushing.set(false);
        }
    }

    private List<Path> listOutboxFiles() throws Exception {
        try (var stream = Files.list(outboxDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".evt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private void recoverTemporaryFiles() throws Exception {
        try (var stream = Files.list(outboxDir)) {
            for (Path temp : stream.filter(path -> path.getFileName().toString().endsWith(".evt.tmp")).toList()) {
                try {
                    read(temp);
                    String name = temp.getFileName().toString();
                    Files.move(temp, temp.resolveSibling(name.substring(0, name.length() - 4)),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception invalid) {
                    Files.move(temp, temp.resolveSibling(temp.getFileName() + ".bad"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static PendingEvent read(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 2) throw new IllegalArgumentException("outbox 文件格式无效: " + path.getFileName());
        return new PendingEvent(decode(lines.get(0)), decode(lines.get(1)));
    }

    private synchronized void disconnect() {
        KafkaProducer<String, String> old = producer;
        producer = null;
        status = "disconnected";
        if (old != null) {
            try { old.close(Duration.ZERO); } catch (Exception ignored) { }
        }
    }

    @PreDestroy
    public void close() {
        if (worker != null) {
            worker.shutdown();
            try { worker.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        KafkaProducer<String, String> old = producer;
        producer = null;
        if (old != null) {
            try { old.close(Duration.ofSeconds(5)); } catch (Exception ignored) { }
        }
    }

    private static String encode(String value) {
        return B64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(B64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private record PendingEvent(String type, String payload) { }
}
