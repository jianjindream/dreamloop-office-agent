package com.jianjin.assistant.infrastructure.sandbox;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.sandbox.ExecRequest;
import com.jianjin.assistant.domain.sandbox.ExecResult;
import com.jianjin.assistant.domain.sandbox.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DockerSandbox implements Executor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final AppConfig.SandboxConfig cfg;
    private final boolean available;

    public DockerSandbox(AppConfig.SandboxConfig cfg) {
        this(cfg, null);
    }

    DockerSandbox(AppConfig.SandboxConfig cfg, Boolean availabilityOverride) {
        this.cfg = cfg;
        this.available = availabilityOverride != null ? availabilityOverride : probe();
    }

    @Override public String backend() { return "docker"; }
    @Override public boolean available() { return available; }

    private boolean probe() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ExecResult exec(ExecRequest req) {
        long start = System.currentTimeMillis();
        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setBackend("docker");

        if (!available) {
            result.setExitCode(-3);
            result.setStderr("Docker 后端不可用");
            return result;
        }

        Duration timeout = req.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofMillis(cfg.getTimeoutMs());
        }
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(30);
        }

        String containerName = "agi-sandbox-" + UUID.randomUUID().toString().replace("-", "");
        List<String> args = buildDockerArgs(req.getCommand(), containerName);
        ProcessBuilder pb = new ProcessBuilder(args);

        try {
            Process proc = pb.start();
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            int maxOut = cfg.getMaxOutputBytes();
            AtomicInteger remainingOutput = new AtomicInteger(maxOut);

            Thread tOut = pumpStream(proc.getInputStream(), stdoutBuf, maxOut, remainingOutput);
            Thread tErr = pumpStream(proc.getErrorStream(), stderrBuf, maxOut, remainingOutput);

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - start;
            result.setDurationMs(duration);

            if (!finished) {
                proc.destroyForcibly();
                forceRemoveContainer(containerName);
                tOut.join(500);
                tErr.join(500);
                result.setStdout(stdoutBuf.toString(StandardCharsets.UTF_8));
                result.setStderr(appendIfMissing(stderrBuf.toString(StandardCharsets.UTF_8),
                        "\n[超时] 执行超过 " + timeout + " 被强制终止"));
                result.setTruncated(maxOut > 0 && remainingOutput.get() <= 0);
                result.setKilled(true);
                result.setExitCode(-4);
                return result;
            }
            tOut.join();
            tErr.join();

            result.setStdout(stdoutBuf.toString(StandardCharsets.UTF_8));
            result.setStderr(stderrBuf.toString(StandardCharsets.UTF_8));
            result.setExitCode(proc.exitValue());

            if (maxOut > 0 && remainingOutput.get() <= 0) {
                result.setTruncated(true);
            }
            return result;
        } catch (Exception e) {
            log.warn("Docker exec 失败: {}", e.getMessage());
            forceRemoveContainer(containerName);
            result.setExitCode(-5);
            result.setStderr(result.getStderr() + "\n[沙箱内部错误] " + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    List<String> buildDockerArgs(String command, String containerName) {
        List<String> args = new ArrayList<>();
        args.add("docker");
        args.add("run");
        args.add("--rm");
        args.add("--name");
        args.add(containerName);
        args.add("--pull");
        args.add("never");
        args.add("--init");
        args.add("--security-opt");
        args.add("no-new-privileges");
        args.add("--cap-drop");
        args.add("ALL");
        args.add("--user");
        args.add("65534:65534");
        args.add("--workdir");
        args.add("/tmp");
        args.add("--ipc");
        args.add("none");
        args.add("--ulimit");
        args.add("nofile=256:256");
        if (cfg.isNetworkDisabled()) {
            args.add("--network");
            args.add("none");
        }
        if (cfg.isReadonlyRootfs()) {
            args.add("--read-only");
            args.add("--tmpfs");
            args.add("/tmp:rw,noexec,nosuid,size=64m,mode=1777");
        }
        if (cfg.getMemoryLimitMb() > 0) {
            args.add("--memory");
            args.add(cfg.getMemoryLimitMb() + "m");
            args.add("--memory-swap");
            args.add(cfg.getMemoryLimitMb() + "m");
        }
        if (cfg.getCpuPercent() > 0) {
            args.add("--cpus");
            args.add(String.format(Locale.ROOT, "%.2f", cfg.getCpuPercent() / 100.0));
        }
        if (cfg.getMaxPids() > 0) {
            args.add("--pids-limit");
            args.add(String.valueOf(cfg.getMaxPids()));
        }
        String image = cfg.getImage();
        if (image == null || image.isEmpty()) image = "alpine:3.19";
        args.add(image);
        args.add("sh");
        args.add("-c");
        args.add(command);
        return args;
    }

    private void forceRemoveContainer(String containerName) {
        try {
            Process cleanup = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (Exception e) {
            log.warn("清理超时沙箱容器 {} 失败: {}", containerName, e.getMessage());
        }
    }

    static Thread pumpStream(InputStream in, OutputStream out, int max) {
        return pumpStream(in, out, max, new AtomicInteger(max));
    }

    static Thread pumpStream(InputStream in, OutputStream out, int max, AtomicInteger remaining) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    int writable = n;
                    if (max > 0) {
                        int before;
                        do {
                            before = remaining.get();
                            if (before <= 0) { writable = 0; break; }
                            writable = Math.min(n, before);
                        } while (!remaining.compareAndSet(before, before - writable));
                    }
                    if (writable > 0) {
                        out.write(buf, 0, writable);
                    }
                }
            } catch (Exception ignored) {}
        }, "sandbox-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static String appendIfMissing(String base, String suffix) {
        if (base == null) return suffix;
        if (base.contains("超时")) return base;
        return base + suffix;
    }
}
