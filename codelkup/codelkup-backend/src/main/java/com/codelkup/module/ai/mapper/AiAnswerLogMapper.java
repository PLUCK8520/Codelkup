package com.codelkup.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codelkup.module.ai.entity.AiAnswerLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 问答日志 Mapper（只有写入，没有查询接口 —— 复盘用 SQL 直查即可）。
 *
 * @author 刘
 */
@Mapper
public interface AiAnswerLogMapper extends BaseMapper<AiAnswerLog> {
}