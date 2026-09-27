package com.codelkup.module.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.content.entity.Post;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.content.service.PostService;
import com.codelkup.module.interaction.entity.Comment;
import com.codelkup.module.interaction.mapper.CommentMapper;
import com.codelkup.module.interaction.service.CommentService;
import com.codelkup.module.moderation.dto.ReportCreateRequest;
import com.codelkup.module.moderation.dto.ReportHandleRequest;
import com.codelkup.module.moderation.entity.Report;
import com.codelkup.module.moderation.mapper.ReportMapper;
import com.codelkup.module.moderation.vo.ReportAdminVO;
import com.codelkup.module.moderation.vo.ReportCreateVO;
import com.codelkup.module.moderation.vo.ReportHandleVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 举报与审核服务。
 *
 * <h2>幂等靠数据库，不靠"先查再写"</h2>
 * 同一人对同一目标只能有一条举报：由 {@code uk_reporter_target} 唯一索引保证。
 * "{@code selectOne 查有没有} → 没有就 insert"在并发下存在竞态窗口，而唯一索引是原子的。
 * 代价是插入可能抛 {@code DuplicateKeyException}，捕获后转成"已经举报过了"即可 ——
 * 这正是 Flask/Spring 系防重实现的常规做法（与点赞防重同一手法）。
 *
 * <h2>处理动作复用现有删除路径</h2>
 * {@code delete_target} 不自己写 {@code UPDATE t_post SET deleted=1}，而是调
 * {@link PostService#delete} / {@link CommentService#delete}（传 {@code isAdmin=true}）——
 * 删除的副作用远不止改一行：板块计数、标签引用数、Redis 计数清理、评论数扣减、
 * 状态标记（管理员删=封禁 status=3）。另写一份必然漏掉其中几项，
 * 于是出现"举报删的帖子还在板块计数里"这类数据漂移。复用的代价仅仅是两行调用。
 *
 * <h2>删除成功后顺带关闭同目标的其它待处理举报</h2>
 * 否则队列里会永远挂着若干"目标已不存在"的行 —— 既没人能再处理它们
 * （目标都没了，"删除内容"无从执行），又会长期污染待处理数。
 * 被自动关闭的行同样写 {@code handled_by / handled_at / handle_note}，
 * 留痕为"系统代关"而不是"管理员逐一处理过"（备注文案可区分）。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 允许的举报原因。存字符串而非序号：新增原因不必改表结构，日志里也可直接读 */
    private static final Set<String> ALLOWED_REASONS = Set.of("SPAM", "ABUSE", "ILLEGAL", "OTHER");

    private static final String ACTION_DISMISS = "dismiss";
    private static final String ACTION_DELETE_TARGET = "delete_target";

    /** 兄弟举报自动关闭时的系统备注 —— 与管理员手填备注在文案上可区分 */
    private static final String SIBLING_CLOSE_NOTE = "同目标内容已删除，自动关闭";

    /** 评论摘要截断长度（队列里只需够判断"在说什么"） */
    private static final int COMMENT_EXCERPT_LENGTH = 80;

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;
    private final PostService postService;
    private final CommentService commentService;

    // ==================== 提交举报 ====================

    /**
     * 提交举报。
     *
     * <p>顺序刻意是"先校验目标 → 再校验自己 → 最后插入"：
     * 目标不存在时应该报 404（用户点了个已被删的链接），
     * 而不是先撞幂等再报"已举报过" —— 那会让"内容已删"与"已投诉"混成同一个提示。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportCreateVO create(ReportCreateRequest request, Long reporterId) {
        String reason = request.getReason() == null ? "" : request.getReason().trim().toUpperCase();
        if (!ALLOWED_REASONS.contains(reason)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "举报原因不合法");
        }

        Long targetAuthorId = requireTargetAuthor(request.getTargetType(), request.getTargetId());
        if (reporterId.equals(targetAuthorId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能举报自己的内容");
        }

        Report report = new Report();
        report.setReporterId(reporterId);
        report.setTargetType(request.getTargetType());
        report.setTargetId(request.getTargetId());
        report.setReason(reason);
        report.setDetail(normalizeText(request.getDetail()));
        report.setStatus(Report.STATUS_PENDING);

        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_reporter_target 拦下重复举报 —— 属预期分支，不是异常
            throw new BusinessException(ErrorCode.ALREADY_REPORTED);
        }

        log.info("提交举报 | reportId={} | reporterId={} | targetType={} | targetId={} | reason={}",
                report.getId(), reporterId, request.getTargetType(), request.getTargetId(), reason);

        return new ReportCreateVO(report.getId(), report.getStatus());
    }

    // ==================== 审核队列（管理员） ====================

    /**
     * 审核队列（分页）。
     *
     * @param status 状态过滤：0待处理 1已驳回 2已删除内容；传 null 表示全部
     */
    public PageResult<ReportAdminVO> listAdmin(Integer status, int page, int size) {
        // 倒序按 create_time + id：create_time 是秒精度，同秒插入的多条必须有次级键，
        // 否则翻页时 MySQL 对并列行的返回顺序不做保证（会出现重复行 / 漏行）
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .eq(status != null, Report::getStatus, status)
                .orderByDesc(Report::getCreateTime)
                .orderByDesc(Report::getId);

        IPage<Report> result = reportMapper.selectPage(new Page<>(page, size), wrapper);
        List<Report> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), List.of());
        }
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), assemble(rows));
    }

    /** 待处理举报数（管理端角标） */
    public long pendingCount() {
        return reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                .eq(Report::getStatus, Report.STATUS_PENDING));
    }

    // ==================== 处理动作（管理员） ====================

    /**
     * 处理举报。
     *
     * <p>并发安全：状态流转用<b>条件更新</b>（{@code WHERE id=? AND status=0}）"认领"，
     * 影响行数为 0 即说明已被处理过（重复提交或另一个管理员抢先），直接返回 40910。
     * 不依赖"先读出来判断 status"—— 两个请求同时读到 0 时会双双通过。
     *
     * <p>{@code delete_target} 的顺序是"先认领状态 → 再删内容"：
     * 万一删内容失败（目标已被作者自己删掉 → 404），事务回滚把举报退回待处理，
     * 管理员看到的是"目标不存在"而不是一条被误标成已删除的举报。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportHandleVO handle(Long reportId, ReportHandleRequest request, Long adminId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }

        String action = request.getAction() == null ? "" : request.getAction().trim();
        String note = normalizeText(request.getNote());

        if (ACTION_DISMISS.equals(action)) {
            int updated = closeReport(reportId, Report.STATUS_DISMISSED, adminId, note);
            if (updated == 0) {
                throw new BusinessException(ErrorCode.REPORT_ALREADY_HANDLED);
            }
            log.info("驳回举报 | reportId={} | adminId={}", reportId, adminId);
            return new ReportHandleVO(reportId, Report.STATUS_DISMISSED, 0);
        }

        if (ACTION_DELETE_TARGET.equals(action)) {
            int updated = closeReport(reportId, Report.STATUS_DELETED_TARGET, adminId, note);
            if (updated == 0) {
                throw new BusinessException(ErrorCode.REPORT_ALREADY_HANDLED);
            }
            deleteTarget(report, adminId);
            int closedSiblings = closeSiblings(report, adminId);
            log.info("删除被举报内容 | reportId={} | adminId={} | targetType={} | targetId={} | closedSiblings={}",
                    reportId, adminId, report.getTargetType(), report.getTargetId(), closedSiblings);
            return new ReportHandleVO(reportId, Report.STATUS_DELETED_TARGET, closedSiblings);
        }

        throw new BusinessException(ErrorCode.PARAM_ERROR, "处理动作只能是 dismiss 或 delete_target");
    }

    // ==================== 私有工具 ====================

    /**
     * 校验目标存在性并返回其作者 ID。
     *
     * <p>只接受"当前对外可见"的目标：帖子要求 {@code status=1}；已被删/被封的帖子
     * 对用户本就不可见，此时报 40402 比接受一条针对幽灵内容的举报更诚实。
     */
    private Long requireTargetAuthor(Integer targetType, Long targetId) {
        if (targetType == Report.TARGET_POST) {
            Post post = postMapper.selectById(targetId);
            if (post == null || post.getStatus() == null || post.getStatus() != Post.STATUS_PUBLISHED) {
                throw new BusinessException(ErrorCode.POST_NOT_FOUND);
            }
            return post.getAuthorId();
        }
        if (targetType == Report.TARGET_COMMENT) {
            Comment comment = commentMapper.selectById(targetId);
            if (comment == null) {
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            }
            return comment.getAuthorId();
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "被举报对象类型不合法");
    }

    /** 条件更新：只有"仍待处理"的行才会被写上处理痕迹（原子认领） */
    private int closeReport(Long reportId, int status, Long adminId, String note) {
        return reportMapper.update(null, new LambdaUpdateWrapper<Report>()
                .eq(Report::getId, reportId)
                .eq(Report::getStatus, Report.STATUS_PENDING)
                .set(Report::getStatus, status)
                .set(Report::getHandledBy, adminId)
                .set(Report::getHandledAt, LocalDateTime.now())
                .set(Report::getHandleNote, note));
    }

    /** 删除被举报目标 —— 复用现有删除路径，保证副作用与"作者/管理员直接删"完全一致 */
    private void deleteTarget(Report report, Long adminId) {
        if (report.getTargetType() == Report.TARGET_POST) {
            postService.delete(report.getTargetId(), adminId, true);
        } else if (report.getTargetType() == Report.TARGET_COMMENT) {
            commentService.delete(report.getTargetId(), adminId, true);
        } else {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "被举报对象类型不合法");
        }
    }

    /** 关闭同目标的其它待处理举报（不包含本次这条），返回关闭条数 */
    private int closeSiblings(Report report, Long adminId) {
        return reportMapper.update(null, new LambdaUpdateWrapper<Report>()
                .eq(Report::getTargetType, report.getTargetType())
                .eq(Report::getTargetId, report.getTargetId())
                .eq(Report::getStatus, Report.STATUS_PENDING)
                .ne(Report::getId, report.getId())
                .set(Report::getStatus, Report.STATUS_DELETED_TARGET)
                .set(Report::getHandledBy, adminId)
                .set(Report::getHandledAt, LocalDateTime.now())
                .set(Report::getHandleNote, SIBLING_CLOSE_NOTE));
    }

    /**
     * 把库里的举报行拼成队列视图。
     *
     * <p>固定 5 次查询（目标两条、次数两条、昵称一条）覆盖整页，不随行数增长 ——
     * 逐行查目标/查昵称会变成 3N 次查询，一页 20 条就是 60 次往返。
     */
    private List<ReportAdminVO> assemble(List<Report> rows) {
        List<Long> postIds = rows.stream()
                .filter(r -> r.getTargetType() == Report.TARGET_POST)
                .map(Report::getTargetId).distinct().toList();
        List<Long> commentIds = rows.stream()
                .filter(r -> r.getTargetType() == Report.TARGET_COMMENT)
                .map(Report::getTargetId).distinct().toList();

        // selectBatchIds 自动带 deleted = 0（实体标了 @TableLogic），
        // 所以"已被删除的目标"在这里查不到 —— 正好就是 targetExists=false 的判据
        Map<Long, Post> posts = postIds.isEmpty() ? Map.of()
                : postMapper.selectBatchIds(postIds).stream()
                        .collect(Collectors.toMap(Post::getId, p -> p));
        Map<Long, Comment> comments = commentIds.isEmpty() ? Map.of()
                : commentMapper.selectBatchIds(commentIds).stream()
                        .collect(Collectors.toMap(Comment::getId, c -> c));

        Map<Long, Integer> postCounts = countByTargets(Report.TARGET_POST, postIds);
        Map<Long, Integer> commentCounts = countByTargets(Report.TARGET_COMMENT, commentIds);

        Set<Long> userIds = new HashSet<>();
        rows.forEach(r -> {
            userIds.add(r.getReporterId());
            userIds.add(r.getHandledBy());
        });
        posts.values().forEach(p -> userIds.add(p.getAuthorId()));
        comments.values().forEach(c -> userIds.add(c.getAuthorId()));
        userIds.remove(null);
        Map<Long, String> nicknames = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u.getNickname() == null ? "" : u.getNickname()));

        List<ReportAdminVO> list = new ArrayList<>(rows.size());
        for (Report r : rows) {
            ReportAdminVO vo = new ReportAdminVO();
            vo.setId(r.getId());
            vo.setTargetType(r.getTargetType());
            vo.setTargetId(r.getTargetId());
            vo.setStatus(r.getStatus());
            vo.setReason(r.getReason());
            vo.setDetail(r.getDetail());
            vo.setCreateTime(r.getCreateTime());
            vo.setReporterId(r.getReporterId());
            vo.setReporterNickname(nicknames.get(r.getReporterId()));
            vo.setHandledBy(r.getHandledBy());
            vo.setHandledByName(nicknames.get(r.getHandledBy()));
            vo.setHandledAt(r.getHandledAt());
            vo.setHandleNote(r.getHandleNote());

            if (r.getTargetType() == Report.TARGET_POST) {
                Post post = posts.get(r.getTargetId());
                vo.setTargetExists(post != null);
                if (post != null) {
                    vo.setTargetTitle(post.getTitle());
                    vo.setTargetExcerpt(post.getSummary());
                    vo.setTargetAuthorId(post.getAuthorId());
                    vo.setTargetAuthorNickname(nicknames.get(post.getAuthorId()));
                }
                vo.setReportedCount(postCounts.getOrDefault(r.getTargetId(), 0));
            } else {
                Comment comment = comments.get(r.getTargetId());
                vo.setTargetExists(comment != null);
                if (comment != null) {
                    vo.setTargetPostId(comment.getPostId());
                    vo.setTargetExcerpt(excerpt(comment.getContent()));
                    vo.setTargetAuthorId(comment.getAuthorId());
                    vo.setTargetAuthorNickname(nicknames.get(comment.getAuthorId()));
                }
                vo.setReportedCount(commentCounts.getOrDefault(r.getTargetId(), 0));
            }
            list.add(vo);
        }
        return list;
    }

    /** 一批目标的举报次数（{@code List<Map>} 的键为 SQL 里的列标签） */
    private Map<Long, Integer> countByTargets(int targetType, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : reportMapper.countByTargets(targetType, ids)) {
            Object targetId = row.get("target_id");
            Object cnt = row.get("cnt");
            if (targetId instanceof Number id && cnt instanceof Number c) {
                counts.put(id.longValue(), c.intValue());
            }
        }
        return counts;
    }

    /** 评论摘要：压平换行后截断，避免多行评论把队列表格拉变形 */
    private String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= COMMENT_EXCERPT_LENGTH
                ? flat
                : flat.substring(0, COMMENT_EXCERPT_LENGTH) + "…";
    }

    /** 去空白；空串统一落成 null，避免库里出现"看起来填了但其实是空格"的备注 */
    private String normalizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}