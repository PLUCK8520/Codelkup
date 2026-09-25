package com.codelkup.module.user.service;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储（头像）。
 *
 * <p><b>安全要点</b>（这类上传接口是 Web 漏洞高发区，逐条说明）：
 * <ol>
 *   <li><b>类型白名单</b>：只允许 jpg/jpeg/png/webp。不用黑名单（黑名单永远列不全，
 *       且 {@code .jsp} 之外的 {@code .jspx / .phtml} 等变体容易漏）。</li>
 *   <li><b>魔数校验</b>：不信任 {@code Content-Type} 与文件扩展名（客户端可伪造），
 *       读取文件头字节判断真实类型。</li>
 *   <li><b>文件名随机化</b>：丢弃原始文件名，用 UUID 重命名 ——
 *       这是防路径穿越最彻底的做法。攻击者传 {@code ../../../../etc/passwd}
 *       或 Windows 下的 {@code ..\..\web.config} 都无从下手。</li>
 *   <li><b>目录按日期分片</b>：{@code yyyy/MM/dd}，避免单目录文件数爆炸。</li>
 *   <li><b>存储根目录与静态资源目录分离</b>：上传目录不承担"代码/配置"职责。</li>
 * </ol>
 *
 * @author 刘
 */
@Slf4j
@Service
public class FileStorageService {

    /** 允许的扩展名（白名单，小写比较） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** 头像最大字节数：2MB（比全局 multipart 上限 5MB 更严，头像不需要那么大） */
    private static final long MAX_AVATAR_SIZE = 2L * 1024 * 1024L;

    @Value("${codelkup.upload.root:./uploads}")
    private String uploadRoot;

    /** 对外访问前缀，需与 WebMvcConfig 的资源映射保持一致 */
    @Value("${codelkup.upload.url-prefix:/uploads}")
    private String urlPrefix;

    /** 运行期校验过的存储根目录（绝对路径） */
    private Path rootPath;

    @PostConstruct
    void init() {
        try {
            this.rootPath = Paths.get(uploadRoot).toAbsolutePath().normalize();
            Files.createDirectories(rootPath);
            log.info("文件存储根目录：{}", rootPath);
        } catch (IOException e) {
            throw new IllegalStateException("初始化上传目录失败：" + uploadRoot, e);
        }
    }

    /**
     * 保存头像，返回可直接放入 {@code <img src>} 的相对 URL。
     */
    public String saveAvatar(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "头像不能超过 2MB");
        }

        String extension = resolveExtension(file);
        verifyMagicBytes(file, extension);

        // ---- 生成路径：avatars/{userId}/{UUID}.{ext} ----
        // ⚠️ 文件名完全由服务端生成，不含任何用户输入
        String relativePath = "avatars/" + userId + "/" + UUID.randomUUID() + "." + extension;

        Path target = rootPath.resolve(relativePath).normalize();

        // 二次防线：即便前面逻辑被绕过，这里也确保最终路径没跑到根目录之外
        if (!target.startsWith(rootPath)) {
            log.error("检测到路径穿越尝试 | relativePath={} | resolved={}", relativePath, target);
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "非法的文件路径");
        }

        try {
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存文件失败 | target={}", target, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败，请稍后重试");
        }

        // 返回时用正斜杠，保证前端拿到的 URL 在 Windows/Linux 下一致
        return urlPrefix + "/" + relativePath;
    }

    /**
     * 解析并校验扩展名。
     *
     * <p>原始文件名只用来"取扩展名"，且取到后立即被丢弃 ——
     * 绝不用它拼接存储路径。
     */
    private String resolveExtension(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || !original.contains(".")) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件缺少扩展名");
        }

        // 只取最后一段：a.jpg.jsp 会被识别成 jsp，从而被白名单拒绝（这正是想要的效果）
        String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).trim();

        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持 jpg / jpeg / png / webp 格式");
        }
        return ext;
    }

    /**
     * 魔数（文件头）校验。
     *
     * <p>为什么必须做：把 {@code shell.jsp} 改名为 {@code avatar.png} 就能通过扩展名白名单，
     * 但文件头字节骗不过去。这是"扩展名白名单"之外必须补的第二道。
     */
    private void verifyMagicBytes(MultipartFile file, String extension) {
        byte[] header = new byte[12];
        int read;
        try (var in = file.getInputStream()) {
            read = in.read(header);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
        if (read < 4) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件内容不完整");
        }

        boolean matched = switch (extension) {
            // JPEG: FF D8 FF
            case "jpg", "jpeg" -> (header[0] & 0xFF) == 0xFF
                    && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF;
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            case "png" -> (header[0] & 0xFF) == 0x89
                    && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
            // WebP: "RIFF" .... "WEBP"
            case "webp" -> read >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            default -> false;
        };

        if (!matched) {
            log.warn("文件魔数校验失败 | 声称类型={} | 实际文件头={}", extension, toHex(header));
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件内容与格式不符，疑似伪造");
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
