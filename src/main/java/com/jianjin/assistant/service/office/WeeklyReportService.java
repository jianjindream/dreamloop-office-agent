package com.jianjin.assistant.service.office;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.domain.document.WriteRequest;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeeklyReportService {
    public static final String TEMPLATE_TYPE = "weekly_report_template";
    public static final String REPORT_TYPE = "weekly_report";
    public static final String DEFAULT_TEMPLATE_ID = "builtin-weekly-report";

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!\\n]+[。！？!]?|[^\\n]+$");
    private static final Pattern CHINESE_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern DEFECTS_A = Pattern.compile("计划[^。；\\n]*?(\\d+)\\s*个缺陷[^。；\\n]*?已关闭\\s*(\\d+)\\s*个");
    private static final Pattern DEFECTS_B = Pattern.compile("缺陷关闭\\s*(\\d+)\\s*个[^。；\\n]*?总计划缺陷\\s*(\\d+)\\s*个");
    private static final Pattern OWNER = Pattern.compile("通知联调负责人(?:为|是)\\s*([\\p{L}]{2,12})");
    private static final Pattern NOTIFICATION_DUE = Pattern.compile("(?:通知联调|完成通知联调)[^。；\\n]*?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    private final DocumentLibraryService library;

    public WeeklyReportService(DocumentLibraryService library) { this.library = library; }

    public record GenerateRequest(
            @JsonProperty("project_name") String projectName,
            @JsonProperty("period_start") LocalDate periodStart,
            @JsonProperty("period_end") LocalDate periodEnd,
            @JsonProperty("template_id") String templateId,
            @JsonProperty("document_ids") List<String> documentIds,
            @JsonProperty("user_facts") String userFacts,
            @JsonProperty("save_draft") Boolean saveDraft) {}

    public record Source(String sourceId, String documentId, String versionId, int version,
                         String title, String locator) {}
    public record Calculation(String metric, List<BigDecimal> inputs, String formula,
                              BigDecimal result, String unit, String sourceId) {}
    public record Issue(String type, String field, String message, List<String> sourceIds,
                        Map<String, String> values) {}
    public record Review(List<Issue> conflicts, List<Issue> missing, List<Calculation> calculations) {}
    public record Result(String documentId, String versionId, int version, String contentMd,
                         Review review, List<Source> sources, String indexStatus) {}
    public record Template(String id, String name, String description, boolean builtIn, String contentMd) {}

    private record Input(Document document, DocumentVersion version, String sourceId) {}

    public Result generate(String ownerKey, GenerateRequest request) {
        validate(request);
        List<Input> inputs = ownedInputs(ownerKey, request.documentIds());
        String template = resolveTemplate(ownerKey, request.templateId());
        Extraction extraction = extract(inputs, request.periodStart(), request.periodEnd());
        if (request.userFacts() != null && !request.userFacts().isBlank()) {
            extraction.inProgress.add(request.userFacts().trim() + "（用户补充）");
        }
        String content = render(request, template, extraction, inputs);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner_key", ownerKey);
        metadata.put("project_name", request.projectName());
        metadata.put("period_start", request.periodStart().toString());
        metadata.put("period_end", request.periodEnd().toString());
        metadata.put("template_id", request.templateId());
        metadata.put("source_document_ids", request.documentIds());
        metadata.put("review", extraction.review());
        String title = request.projectName().trim() + "项目周报 " + request.periodEnd();
        WriteRequest write = new WriteRequest(title, REPORT_TYPE, Document.SOURCE_AGENT, ownerKey,
                content, extraction.summary(), metadata);
        write.setStatus(Boolean.FALSE.equals(request.saveDraft()) ? Document.STATUS_ACTIVE : "draft");
        DocumentLibraryService.Result saved = library.writeDocument(write, false);
        List<Source> sources = inputs.stream().map(value -> new Source(value.sourceId(), value.document().getId(),
                value.version().getId(), value.version().getVersion(), value.document().getTitle(),
                "v" + value.version().getVersion())).toList();
        return new Result(saved.document.getId(), saved.version.getId(), saved.version.getVersion(), content,
                extraction.review(), sources, saved.indexStatus);
    }

    public List<Template> templates(String ownerKey) {
        List<Template> result = new ArrayList<>();
        result.add(defaultTemplate());
        for (Document document : library.list()) {
            if (!TEMPLATE_TYPE.equals(document.getDocType())) continue;
            LibraryRepo.DocumentWithVersion value = library.get(document.getId());
            if (owned(ownerKey, value.version)) result.add(new Template(document.getId(), document.getTitle(),
                    value.version.getSummary(), false, value.version.getContentMd()));
        }
        return result;
    }

    public Template createTemplate(String ownerKey, String name, String description, String content) {
        if (name == null || name.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("模板名称和正文必填");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner_key", ownerKey);
        WriteRequest write = new WriteRequest(name.trim(), TEMPLATE_TYPE, Document.SOURCE_UPLOAD, ownerKey,
                content.trim(), description == null ? "" : description.trim(), metadata);
        DocumentLibraryService.Result result = library.writeDocument(write, false);
        return new Template(result.document.getId(), result.document.getTitle(), result.version.getSummary(),
                false, result.version.getContentMd());
    }

    public Template defaultTemplate() {
        return new Template(DEFAULT_TEMPLATE_ID, "标准项目周报", "摘要、进展、风险、计划和来源", true,
                "# {{项目名称}}项目周报\n\n汇报周期：{{开始日期}} 至 {{结束日期}}\n\n" +
                "## 本周摘要\n{{本周摘要}}\n\n## 本周完成\n{{本周完成}}\n\n" +
                "## 进行中事项\n{{进行中事项}}\n\n## 风险与阻塞\n{{风险与阻塞}}\n\n" +
                "## 下周计划\n{{下周计划}}\n\n## 待确认信息\n{{待确认信息}}\n\n" +
                "## 资料来源与版本\n{{资料来源与版本}}");
    }

    /** Internal task metadata lookup; access checks remain at the task submission boundary. */
    public String documentType(String documentId) { return library.get(documentId).document.getDocType(); }

    private void validate(GenerateRequest request) {
        if (request == null || request.projectName() == null || request.projectName().isBlank()
                || request.periodStart() == null || request.periodEnd() == null
                || request.templateId() == null || request.templateId().isBlank()
                || request.documentIds() == null || request.documentIds().isEmpty()) {
            throw new IllegalArgumentException("项目名称、汇报周期、模板和资料列表必填");
        }
        if (request.periodEnd().isBefore(request.periodStart())) throw new IllegalArgumentException("汇报结束日期不能早于开始日期");
        if (request.documentIds().size() > 20) throw new IllegalArgumentException("一次最多选择 20 份资料");
    }

    private List<Input> ownedInputs(String ownerKey, List<String> ids) {
        Set<String> unique = new LinkedHashSet<>(ids);
        List<Input> result = new ArrayList<>();
        int index = 1;
        for (String id : unique) {
            LibraryRepo.DocumentWithVersion value = library.get(id);
            if (!owned(ownerKey, value.version) || "deleted".equals(value.document.getStatus())) {
                throw new IllegalArgumentException("资料不存在或无权访问: " + id);
            }
            if (TEMPLATE_TYPE.equals(value.document.getDocType())) throw new IllegalArgumentException("模板不能作为事实资料: " + id);
            result.add(new Input(value.document, value.version, "S" + index++));
        }
        return result;
    }

    private String resolveTemplate(String ownerKey, String templateId) {
        if (DEFAULT_TEMPLATE_ID.equals(templateId)) return defaultTemplate().contentMd();
        LibraryRepo.DocumentWithVersion value = library.get(templateId);
        if (!TEMPLATE_TYPE.equals(value.document.getDocType()) || !owned(ownerKey, value.version)) {
            throw new IllegalArgumentException("模板不存在或无权访问");
        }
        return value.version.getContentMd();
    }

    private boolean owned(String ownerKey, DocumentVersion version) {
        Object owner = version.getMetadata().get("owner_key");
        return ownerKey.equals(String.valueOf(owner)) || (owner == null && "default:default".equals(ownerKey));
    }

    private Extraction extract(List<Input> inputs, LocalDate start, LocalDate end) {
        Extraction result = new Extraction();
        Map<String, Map<String, String>> claims = new LinkedHashMap<>();
        for (Input input : inputs) {
            String content = input.version().getContentMd();
            Matcher sentenceMatcher = SENTENCE.matcher(content);
            while (sentenceMatcher.find()) {
                String sentence = clean(sentenceMatcher.group());
                if (sentence.isBlank() || sentence.startsWith("#") || sentence.startsWith("|")) continue;
                boolean plan = containsAny(sentence, "计划", "下周", "目标", "预计");
                if (containsAny(sentence, "完成", "通过验收", "已关闭") && !plan && inPeriod(sentence, start, end)) {
                    result.completed.add(withSource(sentence, input.sourceId()));
                } else if (containsAny(sentence, "正在", "进行中", "仍在处理中", "处理中", "尚未确认")) {
                    result.inProgress.add(withSource(sentence, input.sourceId()));
                }
                if (containsAny(sentence, "风险", "阻塞", "影响", "尚未提供", "缺少", "缺失")) {
                    result.risks.add(withSource(sentence, input.sourceId()));
                }
                if (plan) result.plans.add(withSource(sentence, input.sourceId()));
                if (containsAny(sentence, "尚未确认", "没有确认", "未确认")) {
                    result.missing.add(new Issue("missing", "source_fact", sentence, List.of(input.sourceId()), Map.of()));
                }
            }
            metric(input, result, claims);
        }
        claims.forEach((field, values) -> {
            if (new LinkedHashSet<>(values.values()).size() > 1) {
                result.conflicts.add(new Issue("conflict", field, "资料对同一字段给出了不同值，请人工确认",
                        new ArrayList<>(values.keySet()), values));
            }
        });
        if (result.completed.isEmpty()) result.missing.add(new Issue("missing", "completed", "没有找到本期已完成事项",
                List.of(), Map.of()));
        return result;
    }

    private void metric(Input input, Extraction result, Map<String, Map<String, String>> claims) {
        String content = input.version().getContentMd();
        Matcher a = DEFECTS_A.matcher(content);
        Matcher b = DEFECTS_B.matcher(content);
        Integer total = null, closed = null;
        if (a.find()) { total = Integer.parseInt(a.group(1)); closed = Integer.parseInt(a.group(2)); }
        else if (b.find()) { closed = Integer.parseInt(b.group(1)); total = Integer.parseInt(b.group(2)); }
        if (closed != null && total != null) {
            claims.computeIfAbsent("defects_closed", ignored -> new LinkedHashMap<>())
                    .put(input.sourceId(), String.valueOf(closed));
            result.calculations.add(new Calculation("缺陷关闭比例",
                    List.of(BigDecimal.valueOf(closed), BigDecimal.valueOf(total)), "已关闭 / 计划总数",
                    total == 0 ? null : BigDecimal.valueOf(closed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP),
                    "ratio", input.sourceId()));
            result.calculations.add(new Calculation("剩余缺陷",
                    List.of(BigDecimal.valueOf(total), BigDecimal.valueOf(closed)), "计划总数 - 已关闭",
                    BigDecimal.valueOf(total - closed), "count", input.sourceId()));
            result.metrics.add("计划缺陷 " + total + " 个，已关闭 " + closed + " 个，剩余 " + (total - closed)
                    + " 个，关闭比例 " + (total == 0 ? "无法计算" : percentage(closed, total)) + " [" + input.sourceId() + "]");
        } else {
            Matcher standalone = Pattern.compile("缺陷关闭\\s*(\\d+)\\s*个").matcher(content);
            if (standalone.find()) claims.computeIfAbsent("defects_closed", ignored -> new LinkedHashMap<>())
                    .put(input.sourceId(), standalone.group(1));
        }
        Matcher owner = OWNER.matcher(content);
        if (owner.find()) claims.computeIfAbsent("notification_owner", ignored -> new LinkedHashMap<>())
                .put(input.sourceId(), owner.group(1));
        Matcher due = NOTIFICATION_DUE.matcher(content);
        if (due.find()) claims.computeIfAbsent("notification_due", ignored -> new LinkedHashMap<>())
                .put(input.sourceId(), String.format(Locale.ROOT, "%02d-%02d", Integer.parseInt(due.group(1)), Integer.parseInt(due.group(2))));
    }

    private String render(GenerateRequest request, String template, Extraction data, List<Input> inputs) {
        String summary = data.summary();
        String completed = bullets(data.completed, data.metrics);
        String progress = bullets(data.inProgress, List.of());
        String risks = bullets(data.risks, List.of());
        String plans = bullets(data.plans, List.of());
        List<String> pending = new ArrayList<>();
        data.missing.forEach(issue -> pending.add(issue.message()));
        data.conflicts.forEach(issue -> pending.add(issue.message() + "：" + issue.values()));
        String sources = inputs.stream().map(value -> "- [" + value.sourceId() + "] " + value.document().getTitle()
                + " · v" + value.version().getVersion() + " · " + value.version().getCreatedAt()).reduce((a, b) -> a + "\n" + b).orElse("- 无");
        String rendered = template
                .replace("{{项目名称}}", request.projectName().trim())
                .replace("{{开始日期}}", request.periodStart().toString())
                .replace("{{结束日期}}", request.periodEnd().toString())
                .replace("{{本周摘要}}", summary)
                .replace("{{本周完成}}", completed)
                .replace("{{进行中事项}}", progress)
                .replace("{{风险与阻塞}}", risks)
                .replace("{{下周计划}}", plans)
                .replace("{{待确认信息}}", bullets(pending, List.of()))
                .replace("{{资料来源与版本}}", sources);
        if (!template.contains("{{本周摘要}}")) return defaultTemplate().contentMd()
                .replace("{{项目名称}}", request.projectName().trim()).replace("{{开始日期}}", request.periodStart().toString())
                .replace("{{结束日期}}", request.periodEnd().toString()).replace("{{本周摘要}}", summary)
                .replace("{{本周完成}}", completed).replace("{{进行中事项}}", progress)
                .replace("{{风险与阻塞}}", risks).replace("{{下周计划}}", plans)
                .replace("{{待确认信息}}", bullets(pending, List.of())).replace("{{资料来源与版本}}", sources);
        return rendered.trim();
    }

    private static String bullets(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first); all.addAll(second);
        if (all.isEmpty()) return "- 无可核实信息";
        return all.stream().distinct().map(value -> "- " + value).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String percentage(int value, int total) {
        return BigDecimal.valueOf(value * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String clean(String value) {
        return value.replaceAll("(?m)^#+\\s*", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String withSource(String value, String source) { return value + " [" + source + "]"; }

    private static boolean inPeriod(String value, LocalDate start, LocalDate end) {
        Matcher matcher = CHINESE_DATE.matcher(value);
        if (!matcher.find()) return true;
        LocalDate date = LocalDate.of(end.getYear(), Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private static final class Extraction {
        final List<String> completed = new ArrayList<>();
        final List<String> inProgress = new ArrayList<>();
        final List<String> risks = new ArrayList<>();
        final List<String> plans = new ArrayList<>();
        final List<String> metrics = new ArrayList<>();
        final List<Issue> conflicts = new ArrayList<>();
        final List<Issue> missing = new ArrayList<>();
        final List<Calculation> calculations = new ArrayList<>();
        Review review() { return new Review(conflicts, missing, calculations); }
        String summary() {
            return "本期核实完成事项 " + completed.size() + " 项，进行中事项 " + inProgress.size()
                    + " 项，风险或阻塞 " + risks.size() + " 项；待确认 " + (conflicts.size() + missing.size()) + " 项。";
        }
    }
}
