package com.jianjin.assistant.infrastructure.sandbox;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.domain.sandbox.ExecRequest;
import com.jianjin.assistant.domain.sandbox.ExecResult;
import com.jianjin.assistant.domain.sandbox.Executor;
import com.jianjin.assistant.domain.sandbox.RiskLevel;
import com.jianjin.assistant.domain.sandbox.ValidationResult;
import com.jianjin.assistant.domain.sandbox.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalSandbox implements Executor {

    private static final Logger log = LoggerFactory.getLogger(LocalSandbox.class);

    private final AppConfig.SandboxConfig cfg;
    private final Validator validator;

    public LocalSandbox(AppConfig.SandboxConfig cfg, AppConfig.SecurityConfig securityCfg) {
        this.cfg = cfg;
        this.validator = new Validator(securityCfg);
    }

    @Override public String backend() { return "local"; }
    @Override public boolean available() { return true; }

    @Override
    public ExecResult exec(ExecRequest req) {
        long start = System.currentTimeMillis();
        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setBackend("local");

        Duration timeout = req.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofMillis(cfg.getTimeoutMs());
        }
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(15);
        }

        // 本地模式只允许 SAFE 级别命令
        ValidationResult v = validator.validate(req.getCommand());
        if (v.getLevel() != RiskLevel.SAFE) {
            result.setExitCode(-1);
            result.setStderr("[本地模式拒绝] 只允许 safe 级别命令，当前: "
                    + v.getLevel().value() + " " + v.getViolations());
            return result;
        }

        try {
            List<String> args = parseArgv(req.getCommand());
            ProcessBuilder pb = new ProcessBuilder(args);
            Process proc = pb.start();
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            int maxOut = cfg.getMaxOutputBytes();
            AtomicInteger remainingOutput = new AtomicInteger(maxOut);

            Thread tOut = DockerSandbox.pumpStream(proc.getInputStream(), stdoutBuf, maxOut, remainingOutput);
            Thread tErr = DockerSandbox.pumpStream(proc.getErrorStream(), stderrBuf, maxOut, remainingOutput);

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.setDurationMs(System.currentTimeMillis() - start);

            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                result.setStdout(stdoutBuf.toString());
                result.setStderr(stderrBuf.toString() + "\n[超时] 执行超过 " + timeout + " 被终止");
                result.setKilled(true);
                result.setExitCode(-4);
                return result;
            }
            tOut.join();
            tErr.join();

            result.setStdout(stdoutBuf.toString());
            result.setStderr(stderrBuf.toString());
            result.setExitCode(proc.exitValue());
            if (maxOut > 0 && remainingOutput.get() <= 0) {
                result.setTruncated(true);
            }
            return result;
        } catch (Exception e) {
            log.warn("Local exec 失败: {}", e.getMessage());
            result.setExitCode(-5);
            result.setStderr(result.getStderr() + "\n" + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    /** Local mode never invokes a shell: metacharacters and expansions are rejected outright. */
    static List<String> parseArgv(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean single = false;
        boolean quoted = false;
        boolean tokenStarted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (single) {
                if (ch == '\'') single = false;
                else token.append(ch);
                tokenStarted = true;
                continue;
            }
            if (quoted) {
                if (ch == '"') quoted = false;
                else {
                    if (ch == '\\' || ch == '$' || ch == '`')
                        throw new IllegalArgumentException("Local 后端禁止 Shell 转义和展开");
                    token.append(ch);
                }
                tokenStarted = true;
                continue;
            }
            if (ch == '\'') { single = true; tokenStarted = true; }
            else if (ch == '"') { quoted = true; tokenStarted = true; }
            else if (Character.isWhitespace(ch)) {
                if (tokenStarted) {
                    args.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
            } else if (";&|<>`$\\".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("Local 后端禁止 Shell 操作符、转义和展开");
            } else {
                token.append(ch);
                tokenStarted = true;
            }
        }
        if (single || quoted) throw new IllegalArgumentException("Shell 引号未闭合");
        if (tokenStarted) args.add(token.toString());
        if (args.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return args;
    }
}
