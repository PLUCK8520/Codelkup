package com.codelkup.module.upload.service;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 图片存储（头像 + 帖子正文配图）。
 *
 * <h2>从 user 模块搬过来的原因</h2>
 * 原先叫 {@code FileStorageService}，只服务头像。V0.3 起它要服务两类调用方
 * （头像 / 正文配图），并且新增了"配额"与"删除托管文件"两项职责 ——
 * 继续挂在 {@code module.user} 下会出现"帖子配图依赖用户模块的存储服务"这种
 * 说不清的归属。现在统一收到 {@code module.upload}，user 模块单向依赖它。
 *
 * <h2>这类接口是 Web 漏洞高发区，逐条说明防线</h2>
 * <ol>
 *   <li><b>扩展名白名单</b>：只允许 jpg/jpeg/png/webp。不用黑名单（永远列不全，
 *       {@code .jspx / .phtml} 之类变体容易漏），且只取最后一段扩展名
 *       （{@code a.jpg.jsp} → jsp → 被拒）。</li>
 *   <li><b>魔数校验</b>：不信任 {@code Content-Type} 与扩展名（都可伪造），
 *       读文件头字节判断真实类型。把 {@code shell.jsp} 改名成 {@code .png} 骗不过去。</li>
 *   <li><b>文件名随机化</b>：丢掉原始文件名，用 UUID 重命名 —— 防路径穿越最彻底。</li>
 *   <li><b>二次路径校验</b>：拼出的绝对路径必须仍在存储根目录内（{@code startsWith}）。</li>
 *   <li><b>目录分片</b>：{@code posts/{userId}/{yyyy/MM}}，避免单目录文件数爆炸。</li>
 * </ol>
 *
 * <h2>配额为什么按"文件数"而不是"总字节"</h2>
 * 文件数是磁盘占用与 inode 消耗的主要风险面，且统计成本低（一次目录遍历）。
 * 按字节需要每次上传都全量 stat，收益不明显。默认上限是可配的防滥用阈值，
 * 不是给正常用户设的门槛 —— 正常写帖远够用。
 *
 * @author 刘
 */
@Slf4j
@Service
public class ImageStorageService {

    /** 允许的扩展名（白名单，小写比较） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** 头像最大字节数：2MB（比正文配图更严，头像不需要那么大） */
    private static final long MAX_AVATAR_SIZE = 2L * 1024 * 1024L;

    /** 头像子目录前缀 */
    private static final String AVATAR_DIR = "avatars";

    /** 正文配图子目录前缀 */
    private static final String POST_IMAGE_DIR = "posts";

    @Value("${codelkup.upload.root:./uploads}")
    private String uploadRoot;

    /** 对外访问前缀，需与 WebMvcConfig 的资源映射保持一致 */
    @Value("${codelkup.upload.url-prefix:/uploads}")
    private String urlPrefix;

    /** 正文配图单文件上限（MB）—— 前端会先压到长边 1600 的 WebP，正常远小于此值 */
    @Value("${codelkup.upload.max-image-size-mb:5}")
    private int maxImageSizeMb;

    /** 单用户正文配图文件数上限（防滥用阈值，不是正常使用门槛） */
    @Value("${codelkup.upload.max-files-per-user:200}")
    private int maxFilesPerUser;

    /** 运行期校验过的存储根目录（绝对路径） */
    private Path rootPath;

    @PostConstruct
    void init() {
        try {
            this.rootPath = Paths.get(uploadRoot).toAbsolutePath().normalize();
            Files.createDirectories(rootPath);
            log.info("文件存储根目录：{} | 正文配图上限 {}MB / {} 张每人",
                    rootPath, maxImageSizeMb, maxFilesPerUser);
        } catch (IOException e) {
            throw new IllegalStateException("初始化上传目录失败：" + uploadRoot, e);
        }
    }

    // ==================== 头像 ====================

    /**
     * 保存头像，返回可直接放入 {@code <img src>} 的相对 URL。
     *
     * <p>替换旧头像的清理不在这里做 —— 本服务不知道"当前头像是什么"，
     * 由调用方（UserController）先查旧值、更新成功后再调 {@link #deleteManaged}。
     * 顺序很重要：先删后传会出现在"上传失败"时旧头像也没了的窘境。
     */
    public String saveAvatar(MultipartFile file, Long userId) {
        requireNonEmpty(file);
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "头像不能超过 2MB");
        }
        return store(file, AVATAR_DIR + "/" + userId + "/", "头像不能超过 2MB");
    }

    // ==================== 帖子正文配图 ====================

    /**
     * 保存正文配图，返回可直接写进 Markdown 的 URL。
     *
     * <p>先查配额再落盘：避免"传完才发现超限"这种浪费用户流量的顺序。
     */
    public String savePostImage(MultipartFile file, Long userId) {
        requireNonEmpty(file);
        long maxBytes = maxImageSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "图片不能超过 " + maxImageSizeMb + "MB，请压缩后再上传");
        }

        int used = countUserPostImages(userId);
        if (used >= maxFilesPerUser) {
            log.warn("配图配额已满 | userId={} | used={} | limit={}", userId, used, maxFilesPerUser);
            throw new BusinessException(ErrorCode.UPLOAD_QUOTA_EXCEEDED,
                    "你上传的图片已达上限（" + maxFilesPerUser + " 张），暂不能再上传");
        }

        String monthDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return store(file, POST_IMAGE_DIR + "/" + userId + "/" + monthDir + "/",
                "图片不能超过 " + maxImageSizeMb + "MB");
    }

    // ==================== 删除（替换旧文件时用） ====================

    /**
     * 删除"由本服务托管"的文件（给了对外 URL 就能定位）。
     *
     * <p>三道护栏，缺一不可：
     * <ol>
     *   <li>URL 必须以本服务的访问前缀开头 —— 用户头像字段可能是历史遗留的
     *       外站 URL（比如 OAuth 头像），那种不能当成本地路径去删</li>
     *   <li>解析出的绝对路径必须仍在存储根目录内 —— 防 {@code ../} 穿越</li>
     *   <li>删除失败只记日志、不抛异常 —— 清理是"顺手做的事"，
     *       它失败不该让"换头像"这个主流程失败</li>
     * </ol>
     *
     * @return 真的删掉了返回 true；URL 不属于本服务 / 文件不存在返回 false
     */
    public boolean deleteManaged(String url) {
        if (url == null || !url.startsWith(urlPrefix + "/")) {
            return false;
        }
        String relative = url.substring(urlPrefix.length() + 1);
        Path target = rootPath.resolve(relative).normalize();
        if (!target.startsWith(rootPath)) {
            log.warn("拒绝删除存储根目录之外的文件 | url={} | resolved={}", url, target);
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("已清理旧文件 | {}", relative);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("清理旧文件失败（不影响主流程）| url={} | err={}", url, e.getMessage());
            return false;
        }
    }

    /** 统计某用户的正文配图文件数（遍历他的目录，与配额口径一致） */
    private int countUserPostImages(Long userId) {
        Path userDir = rootPath.resolve(POST_IMAGE_DIR + "/" + userId).normalize();
        if (!userDir.startsWith(rootPath) || !Files.isDirectory(userDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(userDir)) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            // 统计失败按 0 处理：宁可放行一次，也不要因为目录读不了就让上传全挂
            log.warn("统计配图数量失败，本次按 0 计 | userId={} | err={}", userId, e.getMessage());
            return 0;
        }
    }

    // ==================== 内部实现 ====================

    private void requireNonEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请选择要上传的文件");
        }
    }

    /**
     * 校验并落盘，返回对外 URL。
     *
     * @param relativeDir 目标目录（相对存储根，末尾带斜杠）；文件名由本方法生成
     */
    private String store(MultipartFile file, String relativeDir, String sizeMessage) {
        String extension = resolveExtension(file);
        verifyMagicBytes(file, extension);

        // 文件名完全由服务端生成，不含任何用户输入
        String relativePath = relativeDir + UUID.randomUUID() + "." + extension;
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, sizeMessage + "，或文件保存失败，请稍后重试");
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