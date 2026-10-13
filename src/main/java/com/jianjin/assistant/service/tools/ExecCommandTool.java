package com.jianjin.assistant.service.tools;

import com.jianjin.assistant.model.Tool;
import com.jianjin.assistant.model.ToolParam;
import com.jianjin.assistant.domain.sandbox.ExecRequest;
import com.jianjin.assistant.domain.sandbox.ExecResult;
import com.jianjin.assistant.domain.sandbox.Sandbox;

import java.util.List;

public final class ExecCommandTool {

    private ExecCommandTool() {}

    public static Tool create(Sandbox sandbox) {
        Tool tool = new Tool();
        tool.setName("exec_command");
        tool.setDescription("在隔离沙箱中执行终端命令，可用命令取决于配置的沙箱镜像；"
                + "危险命令（rm -rf、sudo、网络外联等）会被自动拒绝；"
                + "涉及删除/安装/管道等中等风险命令必须先取得确认令牌，并在用户明确确认后再次调用。");
        tool.setParameters(List.of(
                new ToolParam("command", "string", "要执行的 Shell 命令", true),
                new ToolParam("confirm", "boolean", "用户已明确确认 warn 级命令；默认 false", false),
                new ToolParam("confirmation_token", "string", "首次 warn 响应签发的单次确认令牌", false)
        ));
        tool.setExecute(params -> {
            Object cmdObj = params.get("command");
            String cmdStr = cmdObj == null ? "" : cmdObj.toString();
            if (cmdStr.trim().isEmpty()) {
                throw new RuntimeException("参数 command 不能为空");
            }
            boolean confirm = false;
            Object cv = params.get("confirm");
            if (cv instanceof Boolean b) confirm = b;
            else if (cv instanceof String s) confirm = "true".equalsIgnoreCase(s) || "1".equals(s);

            ExecRequest req = new ExecRequest(cmdStr, null, confirm);
            Object token = params.get("confirmation_token");
            if (token != null) req.setConfirmationToken(token.toString());
            ExecResult result = sandbox.exec(req);
            return formatExecResult(result);
        });
        return tool;
    }

    public static String formatExecResult(ExecResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.getValidation() != null) {
            switch (r.getValidation().getLevel()) {
                case BLOCK -> {
                    sb.append("🛑 **命令被拒绝**\n原因：")
                            .append(r.getValidation().getReason()).append("\n");
                    return sb.toString();
                }
                case WARN -> {
                    if (r.getExitCode() == -2) {
                        sb.append("⚠️ **命令需要确认**\n触发规则：")
                                .append(String.join("、", r.getValidation().getViolations()))
                                .append("\n请先向用户展示风险；仅在用户明确确认后，使用 `confirm=true` 和以下单次令牌重新执行：\n`")
                                .append(r.getConfirmationToken()).append("`\n");
                        return sb.toString();
                    }
                    sb.append("⚠️ 警告级命令已执行（触发规则：")
                            .append(String.join("、", r.getValidation().getViolations()))
                            .append("）\n");
                }
                default -> {}
            }
        }
        sb.append(String.format("**沙箱后端**: %s | **退出码**: %d | **耗时**: %dms\n",
                r.getBackend(), r.getExitCode(), r.getDurationMs()));
        if (r.isKilled()) sb.append("⏱ 因超时被强制终止\n");
        if (r.isTruncated()) sb.append("✂️ 输出过长已被截断\n");

        if (r.getStdout() != null && !r.getStdout().isEmpty()) {
            sb.append("\n**stdout**\n```\n").append(r.getStdout());
            if (!r.getStdout().endsWith("\n")) sb.append("\n");
            sb.append("```\n");
        }
        if (r.getStderr() != null && !r.getStderr().isEmpty()) {
            sb.append("\n**stderr**\n```\n").append(r.getStderr());
            if (!r.getStderr().endsWith("\n")) sb.append("\n");
            sb.append("```\n");
        }
        if ((r.getStdout() == null || r.getStdout().isEmpty())
                && (r.getStderr() == null || r.getStderr().isEmpty())) {
            sb.append("（无输出）\n");
        }
        return sb.toString();
    }
}
