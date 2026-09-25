package com.codelkup.module.content.service;

import com.codelkup.module.content.entity.Tag;
import com.codelkup.module.content.mapper.TagMapper;
import com.codelkup.module.content.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 标签服务。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    /** 标签榜默认条数 */
    private static final int DEFAULT_HOT_LIMIT = 20;

    private final TagMapper tagMapper;

    /** 热门标签榜 */
    public List<TagVO> listHot(Integer limit) {
        int n = (limit == null || limit <= 0) ? DEFAULT_HOT_LIMIT : Math.min(limit, 50);
        return tagMapper.selectHot(n).stream().map(this::toVO).toList();
    }

    /**
     * 把标签名列表解析成标签实体列表，不存在的自动创建。
     *
     * <p>输入会先做<b>规范化与去重</b>：
     * <ul>
     *   <li>去首尾空白、剔除空串</li>
     *   <li>用 {@link LinkedHashSet} 去重（大小写不敏感地比），保留用户输入顺序</li>
     * </ul>
     * 一次批量 SELECT 已存在的 → 差集 INSERT 新增 → 再 SELECT 回来补齐 id。
     *
     * <p><b>并发安全</b>：两个请求同时创建同一个新标签时，
     * 后插入的会撞 {@code uk_name} 抛 {@link DuplicateKeyException}。
     * 此处<b>捕获后重新查询</b>而不是让请求失败 —— 标签创建是"期望成功"的幂等操作。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Tag> resolveTags(List<String> rawNames) {
        List<String> names = normalize(rawNames);
        if (names.isEmpty()) {
            return List.of();
        }

        List<Tag> existing = tagMapper.selectByNames(names);

        // 找出还没入库的（大小写不敏感比较，与 uk_name 的 unicode_ci 规则一致）
        List<String> existingLower = existing.stream()
                .map(t -> t.getName().toLowerCase())
                .toList();

        List<String> toCreate = names.stream()
                .filter(n -> !existingLower.contains(n.toLowerCase()))
                .toList();

        for (String name : toCreate) {
            Tag tag = new Tag();
            tag.setName(name);
            tag.setUseCount(0);
            try {
                tagMapper.insert(tag);
                existing.add(tag);
            } catch (DuplicateKeyException e) {
                // 并发下被别人抢先创建：回查拿 id，不让整笔发帖失败
                log.debug("标签并发创建撞唯一索引，回查复用 | name={}", name);
                Tag raced = tagMapper.selectByNames(List.of(name)).stream().findFirst().orElse(null);
                if (raced != null) {
                    existing.add(raced);
                }
            }
        }

        return existing;
    }

    /** 引用次数增减 */
    public void addUseCount(Long tagId, int delta) {
        if (tagId != null && delta != 0) {
            tagMapper.addUseCount(tagId, delta);
        }
    }

    /**
     * 规范化标签名列表。
     *
     * <p>去重用的是小写 compare（{@code LinkedHashSet} 的 equals 是大小写敏感的，
     * 所以要手动转小写做 key），但<b>保留首次出现时的原始大小写</b>用于展示。
     */
    private List<String> normalize(List<String> rawNames) {
        if (rawNames == null || rawNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seenLower = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String raw : rawNames) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > 20) {
                trimmed = trimmed.substring(0, 20);
            }
            if (seenLower.add(trimmed.toLowerCase())) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private TagVO toVO(Tag tag) {
        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setName(tag.getName());
        vo.setUseCount(tag.getUseCount());
        return vo;
    }
}
