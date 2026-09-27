package com.codelkup.module.content.service;

import com.codelkup.module.content.dto.DraftSaveRequest;
import com.codelkup.module.content.vo.DraftSummaryVO;
import com.codelkup.module.content.vo.DraftVO;

import java.util.List;

/**
 * 服务端草稿箱（M4-15）。
 *
 * @author 刘
 */
public interface DraftService {

    /** 当前用户的全部草稿摘要，按更新时间倒序（不含正文） */
    List<DraftSummaryVO> listSummaries(Long userId);

    /** 取一条草稿（含正文）；不存在返回 {@code null} —— 编辑器首次进入是常态，不是错误 */
    DraftVO get(Long userId, String scope);

    /** 保存（upsert）；返回库中真实落下的记录（含服务端保存时间） */
    DraftVO save(Long userId, DraftSaveRequest request);

    /** 删除；幂等（不存在也当成功 —— "确保不存在"的语义） */
    void delete(Long userId, String scope);
}