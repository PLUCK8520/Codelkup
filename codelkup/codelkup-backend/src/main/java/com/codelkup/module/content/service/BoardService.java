package com.codelkup.module.content.service;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.entity.Board;
import com.codelkup.module.content.mapper.BoardMapper;
import com.codelkup.module.content.vo.BoardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 板块服务。
 *
 * <p>板块是十级的字典数据，读多写极少，S2 阶段不做缓存（直查即可，MySQL 完全够）。
 * S5 压测时若成为瓶颈再加 {@code @Cacheable}，不提前优化。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardMapper boardMapper;

    /** 全部正常板块（按 sort_order 升序） */
    public List<BoardVO> listEnabled() {
        return boardMapper.selectEnabled().stream().map(this::toVO).toList();
    }

    /**
     * 校验板块存在且可用，返回实体。
     *
     * <p>发帖时调用。抛 40404 而非 40001：板块不存在属于"资源不存在"，
     * 与"参数格式错误"语义不同，前端可据此做不同提示。
     */
    public Board requireEnabledBoard(Long boardId) {
        if (boardId == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "请选择板块");
        }
        Board board = boardMapper.selectById(boardId);
        if (board == null || (board.getStatus() != null && board.getStatus() != 0)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }
        return board;
    }

    /** 帖子数增减（发帖 +1、删帖 -1） */
    public void addPostCount(Long boardId, int delta) {
        if (boardId != null && delta != 0) {
            boardMapper.addPostCount(boardId, delta);
        }
    }

    private BoardVO toVO(Board board) {
        BoardVO vo = new BoardVO();
        BeanUtils.copyProperties(board, vo);
        return vo;
    }
}
