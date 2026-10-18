package com.jianjin.assistant.domain.sandbox;

import com.jianjin.assistant.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Validator {

    /** Block 级规则：任意一条命中即拒绝 */
    private static final List<Rule> BLOCK_RULES = List.of(
            r("\\u0000", "禁止 NUL 控制字符"),
            r("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "禁止 Shell 控制字符"),
            r("\\$(?:\\{|[A-Za-z0-9_?!#$*@-])", "禁止 Shell 变量展开"),
            r("\\brm\\b[^\\r\\n]*\\s/(?:\\s|$)", "禁止删除根路径"),
            r("\\brm\\b[^\\r\\n]*(?:-[^\\s]*r[^\\s]*f|-[^\\s]*f[^\\s]*r)", "禁止 rm -rf"),
            r("\\bdd\\s+if=", "禁止 dd 设备写入"),
            r("\\bmkfs\\b", "禁止格式化文件系统"),
            r(">\\s*/dev/(sd|hd|nvme|vd|xvd)", "禁止写入块设备"),
            r(":\\s*\\(\\s*\\)\\s*\\{.*:\\s*\\|", "禁止 Fork 炸弹"),
            r("\\bsudo\\b", "禁止 sudo"),
            r("\\bsu\\s", "禁止 su"),
            r("\\bchmod\\s+[0-7]*7[0-7][0-7]\\b", "禁止 chmod 777"),
            r("\\bchown\\s+root\\b", "禁止变更为 root 属主"),
            r("\\b(shutdown|reboot|halt|poweroff|init 0)\\b", "禁止系统关机/重启"),
            r("\\bsystemctl\\s+(stop|disable|mask)\\b", "禁止停止系统服务"),
            r("\\biptables\\b", "禁止修改防火墙规则"),
            r("\\$\\(", "禁止命令替换 $()"),
            r("`", "禁止反引号命令替换"),
            r("\\beval\\b", "禁止 eval"),
            r("/etc/(passwd|shadow|sudoers|ssh)", "禁止访问系统凭证文件"),
            r("(?:~|\\$HOME|/root|/home/[^/\\s]+)/?\\.(ssh|aws|docker|kube)(?:/|\\b)", "禁止访问凭证目录"),
            r("\\.\\./\\.\\./", "禁止多级路径遍历"),
            r("\\b(curl|wget|nc|netcat|ncat)\\b", "禁止网络外联（沙箱无网）"),
            r("\\bssh\\b", "禁止 SSH 连接"),
            r("\\bkillall\\b", "禁止 killall"),
            r("\\bnohup\\b", "禁止 nohup 后台驻留")
    );

    /** Warn 级规则：收集所有命中项 */
    private static final List<Rule> WARN_RULES = List.of(
            r("\\brm\\s", "删除文件操作"),
            r("(?:>>?|<)\\s*\\S", "输入/输出重定向"),
            r("\\bkill\\s", "进程终止操作"),
            r("\\bpip\\s+install\\b", "安装 Python 包"),
            r("\\bnpm\\s+install\\b", "安装 Node 包"),
            r("\\bapt(-get)?\\s+install\\b", "安装系统包"),
            r("\\bapk\\s+add\\b", "安装 Alpine 包"),
            r("[\\r\\n]", "多行 Shell 命令"),
            r(";\\s*\\S", "命令链（分号分隔）"),
            r("&&", "条件命令链 &&"),
            r("\\|\\|", "条件命令链 ||"),
            r("(?<!\\|)\\|(?!\\|)", "管道符")
    );

    private final AppConfig.SecurityConfig cfg;

    public Validator(AppConfig.SecurityConfig cfg) {
        this.cfg = cfg;
    }

    public ValidationResult validate(String command) {
        if (command != null && command.length() > cfg.getMaxCommandLength()) {
            return new ValidationResult(RiskLevel.BLOCK, "命令超过最大长度限制");
        }
        if (command == null || command.trim().isEmpty()) {
            return new ValidationResult(RiskLevel.BLOCK, "命令不能为空");
        }
        String normalized;
        try {
            normalized = normalizeShell(command);
        } catch (IllegalArgumentException e) {
            return new ValidationResult(RiskLevel.BLOCK, e.getMessage());
        }
        if (cfg.isAllowlistMode() && cfg.getAllowlist() != null && !cfg.getAllowlist().isEmpty()) {
            String[] tokens = normalized.trim().split("\\s+", 2);
            String first = tokens.length > 0 ? tokens[0] : "";
            boolean allowed = false;
            for (String a : cfg.getAllowlist()) {
                if (a.equalsIgnoreCase(first)) { allowed = true; break; }
            }
            if (!allowed) {
                return new ValidationResult(RiskLevel.BLOCK,
                        "白名单模式：命令 \"" + first + "\" 未在允许列表中");
            }
        }
        for (Rule rule : BLOCK_RULES) {
            if (rule.pattern.matcher(command).find() || rule.pattern.matcher(normalized).find()) {
                return new ValidationResult(RiskLevel.BLOCK, rule.text);
            }
        }
        List<String> violations = new ArrayList<>();
        for (Rule rule : WARN_RULES) {
            if (rule.pattern.matcher(command).find()) {
                violations.add(rule.text);
            }
        }
        if (!violations.isEmpty()) {
            ValidationResult v = new ValidationResult(RiskLevel.WARN);
            v.setViolations(violations);
            return v;
        }
        return new ValidationResult(RiskLevel.SAFE);
    }

    /**
     * Resolve quote concatenation and backslash escaping before policy matching. This is not a
     * shell parser; it deliberately rejects expansions and malformed quoting and only exists to
     * prevent forms such as {@code r\m} and {@code r'm'} from hiding an executable name.
     */
    static String normalizeShell(String command) {
        StringBuilder out = new StringBuilder(command.length());
        boolean single = false;
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (single) {
                if (ch == '\'') single = false;
                else out.append(ch);
                continue;
            }
            if (quoted) {
                if (ch == '"') quoted = false;
                else if (ch == '\\') {
                    if (++i >= command.length()) throw new IllegalArgumentException("Shell 转义符不完整");
                    out.append(command.charAt(i));
                } else out.append(ch);
                continue;
            }
            if (ch == '\'') single = true;
            else if (ch == '"') quoted = true;
            else if (ch == '\\') {
                if (++i >= command.length()) throw new IllegalArgumentException("Shell 转义符不完整");
                out.append(command.charAt(i));
            } else out.append(ch);
        }
        if (single || quoted) throw new IllegalArgumentException("Shell 引号未闭合");
        return out.toString();
    }

    private static Rule r(String pattern, String text) {
        return new Rule(Pattern.compile(pattern,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL), text);
    }

    private record Rule(Pattern pattern, String text) {}
}
