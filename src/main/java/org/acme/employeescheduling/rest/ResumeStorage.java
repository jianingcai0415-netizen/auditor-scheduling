package org.acme.employeescheduling.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @brief 审核员简历附件的落盘与读取。
 *
 * @details 设计要点（为什么不把 PDF 存进数据库）：
 * <ul>
 *   <li>备份是整库拷贝，附件入库会让每个备份副本都翻倍；</li>
 *   <li>SQLite 单文件 + 大 BLOB 会拖慢每次 VACUUM/备份；</li>
 *   <li>附件只需要"取回来"，不需要在 SQL 里查询内容。</li>
 * </ul>
 *
 * 安全边界：
 * <ul>
 *   <li>磁盘文件名**由服务端生成**，绝不使用用户提交的名字（可能含 {@code ../}）；</li>
 *   <li>用户提交的名字只写进 {@code resume_file_name} 用于展示，落库前做白名单清洗；</li>
 *   <li>读取时用 {@code normalize + startsWith(base)} 二次确认，防止记录被篡改后越界读文件。</li>
 * </ul>
 */
@ApplicationScoped
public class ResumeStorage {

    /** 只接受 PDF：审核员简历的通行格式，且用魔数就能可靠识别，无需额外解析库。 */
    public static final String CONTENT_TYPE = "application/pdf";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter UPLOADED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ConfigProperty(name = "app.resume.dir", defaultValue = "documents/resumes")
    String resumeDir;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    /**
     * 单个附件上限，默认 10 MB。做成配置是为了测试能压到 2 KB 覆盖边界，
     * 生产上也能在磁盘紧张时收紧而不必改代码。
     */
    @ConfigProperty(name = "app.resume.max-bytes", defaultValue = "10485760")
    long maxBytes;

    /** @brief 当前生效的附件大小上限（字节）。 */
    public long maxBytes() {
        return maxBytes;
    }

    /** @brief 附件根目录（绝对、规范化）。 */
    public Path baseDir() {
        return Path.of(resumeDir).toAbsolutePath().normalize();
    }

    /**
     * @brief 判定上传内容是否是可接受的 PDF。
     *
     * @details 检查 {@code %PDF-} 魔数。扩展名不可信：浏览器按 MIME 提交，
     *          而 MIME 由客户端决定，只有文件头是内容自证的。
     */
    public boolean isPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    /**
     * @brief 把用户提交的文件名清洗成可安全展示的文本。
     *
     * @details 去掉目录分隔符与控制字符（避免展示层被注入换行/路径暗示），
     *          压缩连续空白，长度截到 120 字符，保留中文与常见符号。
     *          结果只用于展示，**不参与任何路径拼接**。
     */
    public static String sanitizeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) return "resume.pdf";
        String name = raw.trim();
        // 只取最后一段，丢弃任何目录成分（同时处理 / 与 \）
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;                  // 控制字符
            if ("<>:\"|?*".indexOf(c) >= 0) continue;              // Windows 非法字符
            out.append(c);
        }
        String cleaned = out.toString().replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) cleaned = "resume.pdf";
        // 先补扩展名再截断，保证总长（含 .pdf）不超过 120，而不是截断后又超回去
        if (!cleaned.toLowerCase(Locale.ROOT).endsWith(".pdf")) cleaned = cleaned + ".pdf";
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 116) + ".pdf";
        return cleaned;
    }

    /**
     * @brief 写入附件并返回磁盘文件名。
     *
     * @details 文件名 {@code {timestamp}-{employeeId}.pdf} 由服务端拼装：带时间戳便于同人多次上传时
     *          保留历史（旧文件由调用方先删），带 employeeId 便于人工排查。
     *          目录按 employeeId 分隔，避免单目录堆几万文件。
     */
    public String store(int employeeId, byte[] bytes) throws IOException {
        Path dir = baseDir().resolve(String.valueOf(employeeId)).normalize();
        if (!dir.startsWith(baseDir())) {
            throw new IOException("resume directory escapes base");
        }
        Files.createDirectories(dir);
        String storedName = LocalDateTime.now().format(STAMP) + "-" + employeeId + ".pdf";
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new IOException("resume path escapes employee directory");
        }
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        return storedName;
    }

    /**
     * @brief 解析记录对应的实际文件路径；越界或不存在都返回 {@code null}。
     *
     * @details 返回 null 而不是抛异常：历史数据可能只有文件名没有 storedName（V25 期间），
     *          调用方应把它当成"附件缺失"，给出可恢复的提示而不是 500。
     */
    public Path resolve(int employeeId, String storedName) {
        if (storedName == null || storedName.isBlank()) return null;
        Path dir = baseDir().resolve(String.valueOf(employeeId)).normalize();
        if (!dir.startsWith(baseDir())) return null;
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) return null;
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? target : null;
    }

    /** @brief 删除附件；文件不存在不算错误（幂等），返回是否真的删掉了文件。 */
    public boolean delete(int employeeId, String storedName) {
        Path target = resolve(employeeId, storedName);
        if (target == null) return false;
        try {
            Files.delete(target);
            return true;
        } catch (IOException ignored) {
            // 删除失败不阻断业务：记录已被清空，孤儿文件可由运维清理。
            return false;
        }
    }

    /** @brief 当前时间戳，与库内 {@code resume_uploaded_at} 同格式。 */
    public String nowStamp() {
        return LocalDateTime.now().format(UPLOADED_AT);
    }

    /**
     * @brief 删除员工时清掉他的整个附件目录。
     *
     * @details 员工被删后目录里剩下的是无主文件，没有任何入口能再访问到，
     *          只能靠人工翻磁盘清理——所以删除动作必须带上它。
     *          沿用 {@code invalidateEmployeesAfterCommit} 的做法，**提交后再删**：
     *          事务回滚时文件还在，记录也还在，两边仍然一致。
     */
    public void purgeEmployeeAfterCommit(int employeeId) {
        try {
            transactionRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() { }
                @Override public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) purgeEmployee(employeeId);
                }
            });
        } catch (IllegalStateException noTransaction) {
            purgeEmployee(employeeId);
        }
    }

    /** @brief 递归删除 {@code {base}/{employeeId}}；目录不存在不是错误。 */
    public void purgeEmployee(int employeeId) {
        Path dir = baseDir().resolve(String.valueOf(employeeId)).normalize();
        if (!dir.startsWith(baseDir()) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 单个文件删不掉（被占用等）不阻断：留待运维清理，不影响业务数据。
                }
            });
        } catch (IOException ignored) {
            // 同上：附件清理失败不能影响员工删除的结果。
        }
    }
}
