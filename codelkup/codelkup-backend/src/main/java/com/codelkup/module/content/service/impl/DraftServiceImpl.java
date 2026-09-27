package com.codelkup.module.content.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.dto.DraftSaveRequest;
import com.codelkup.module.content.entity.Draft;
import com.codelkup.module.content.mapper.DraftMapper;
import com.codelkup.module.content.service.DraftService;
import com.codelkup.module.content.util.MarkdownUtils;
import com.codelkup.module.content.vo.DraftSummaryVO;
import com.codelkup.module.content.vo.DraftVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 草稿箱实现。
 *
 * <h2>两个刻意的口径</h2>
 * <ol>
 *   <li><b>所有查询都带 user_id</b>：草稿只有"本人"这一个权限维度，
 *       不存在"公开草稿"。带 user_id 后即使 scope 被猜到（edit:123 是显而易见的），
 *       也读不到别人的草稿、删不掉别人的草稿 —— 水平越权的天然免疫。</li>
 *   <li><b>删除是物理删除、且幂等</b>：草稿没有审计价值，也不需要"回收站"；
 *       删除接口被重复调用（比如前端重试）不该报错。</li>
 * </ol>
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftServiceImpl implements DraftService {

    /**
     * 单用户草稿条数上限。
     *
     * <p>scope 唯一意味着"条数 = 不同的 scope 数"，正常使用是 1 条新建稿 +
     * 若干编辑稿（每个被编辑过的帖子一条）。设上限是为了兜住"脚本反复 PUT
     * 不同 edit:<id>"这类滥用：限流管的是速率（240 次/小时），挡不住"慢慢写
     * 几万条 5 万字的草稿"。上限触发时给的是可读提示，而不是静默丢弃。
     */
    private static final int MAX_DRAFTS_PER_USER = 100;

    /** 草稿箱列表的正文预览长度 */
    private static final int EXCERPT_LENGTH = 120;

    private final DraftMapper draftMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<DraftSummaryVO> listSummaries(Long userId) {
        List<Draft> rows = draftMapper.selectList(Wrappers.<Draft>lambdaQuery()
                .eq(Draft::getUserId, userId)
                .orderByDesc(Draft::getUpdateTime));
        return rows.stream().map(this::toSummary).toList();
    }

    @Override
    public DraftVO get(Long userId, String scope) {
        Draft row = find(userId, scope);
        return row == null ? null : toVO(row);
    }

    @Override
    public DraftVO save(Long userId, DraftSaveRequest request) {
        // 新建 scope 时先看条数上限；覆盖已有草稿不受上限影响
        // （否则"草稿箱满了"会连正在写的那份都保存不了）
        if (find(userId, request.getScope()) == null) {
            Long count = draftMapper.selectCount(Wrappers.<Draft>lambdaQuery()
                    .eq(Draft::getUserId, userId));
            if (count != null && count >= MAX_DRAFTS_PER_USER) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "草稿数量已达上限（" + MAX_DRAFTS_PER_USER + " 条），请先在草稿箱中清理");
            }
        }

        Draft entity = new Draft();
        entity.setUserId(userId);
        entity.setScope(request.getScope());
        entity.setTitle(request.getTitle() == null ? "" : request.getTitle());
        entity.setContent(request.getContent() == null ? "" : request.getContent());
        entity.setBoardId(request.getBoardId());
        entity.setType(request.getType() == null ? 0 : request.getType());
        entity.setTags(serializeTags(request.getTags()));

        draftMapper.upsert(entity);

        // 回读一次拿数据库真正写下的 update_time：
        // 响应里的时间必须是"库里那一行的",不能拿 application 侧 now() 冒充 ——
        // 前端要用它与本地草稿比新旧，两者差出一次网络往返的时间就够误判了。
        Draft saved = find(userId, request.getScope());
        if (saved == null) {
            // 第三轮审计 C5：另一台设备恰好在这个窗口里删掉同 scope 草稿时，
            // 原实现直接抛 DB_ERROR —— 把一次本应成功的自动保存变成 500。
            // "删了又存" = 用户想要的语义（正在编辑的就是要保存的内容），重写一次再读；
            // 仍然读不到才是真的数据库问题，那时才如实报错。
            draftMapper.upsert(entity);
            saved = find(userId, request.getScope());
        }
        if (saved == null) {
            throw new BusinessException(ErrorCode.DB_ERROR);
        }
        return toVO(saved);
    }

    @Override
    public void delete(Long userId, String scope) {
        draftMapper.delete(Wrappers.<Draft>lambdaQuery()
                .eq(Draft::getUserId, userId)
                .eq(Draft::getScope, scope));
    }

    // ==================== 私有工具 ====================

    private Draft find(Long userId, String scope) {
        return draftMapper.selectOne(Wrappers.<Draft>lambdaQuery()
                .eq(Draft::getUserId, userId)
                .eq(Draft::getScope, scope));
    }

    private DraftVO toVO(Draft d) {
        DraftVO vo = new DraftVO();
        vo.setScope(d.getScope());
        vo.setTitle(d.getTitle());
        vo.setContent(d.getContent());
        vo.setBoardId(d.getBoardId());
        vo.setType(d.getType());
        vo.setTags(parseTags(d.getTags()));
        vo.setUpdateTime(d.getUpdateTime());
        vo.setUpdateTimeEpoch(epochMillis(d.getUpdateTime()));
        return vo;
    }

    private DraftSummaryVO toSummary(Draft d) {
        DraftSummaryVO vo = new DraftSummaryVO();
        vo.setScope(d.getScope());
        vo.setTitle(d.getTitle());
        vo.setExcerpt(MarkdownUtils.extractPlainText(d.getContent(), EXCERPT_LENGTH));
        vo.setBoardId(d.getBoardId());
        vo.setType(d.getType());
        vo.setUpdateTime(d.getUpdateTime());
        vo.setUpdateTimeEpoch(epochMillis(d.getUpdateTime()));
        return vo;
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            // 标签序列化失败不该让整份草稿存不进去：正文才是草稿的主体
            log.warn("草稿标签序列化失败，本次不保存标签 | tags={} | err={}", tags, e.getMessage());
            return null;
        }
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 存了脏数据（人工改库等）时按"没有标签"处理，不让草稿箱整页报错
            log.warn("草稿标签解析失败，按空标签处理 | raw={} | err={}", json, e.getMessage());
            return List.of();
        }
    }

    /**
     * LocalDateTime → epoch 毫秒。
     *
     * <p>用 {@link ZoneId#systemDefault()}（服务进程所在时区）而不是写死 +08：
     * 这个 LocalDateTime 是数据库按服务端时钟写下的墙钟时间，
     * 只有拿服务端自己的时区解释才能得到正确的时间点。
     */
    private Long epochMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}