package com.codelkup.module.content.service;

import com.codelkup.module.content.mapper.PostMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 热度分任务的单元测试。
 *
 * <p>钉死三件事：开关能真关、权重按配置传给 SQL、失败<b>不清零也不抛异常</b>。
 *
 * <p>⚠️ 公式本身在 SQL 里（{@link PostMapper#recalcScores}），这里刻意不重写一遍 ——
 * 否则测试只是把同一段逻辑抄第二遍，改错时两边一起错，测了等于没测。
 * 公式的正确性由端到端脚本 verify-v03-score.py 从接口侧验证。
 */
class ScoreServiceTest {

    private PostMapper postMapper;
    private SimpleMeterRegistry registry;
    private ScoreService service;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        registry = new SimpleMeterRegistry();
        service = new ScoreService(postMapper, registry);
        // @PostConstruct 在单测里不会自动执行（没有 Spring 容器），手动调一次
        service.registerMetrics();
    }

    @Test
    void recalcOnce_passesConfiguredWeightsToSql() {
        when(postMapper.recalcScores(anyInt(), anyInt(), anyInt(), anyDouble())).thenReturn(10000);

        int affected = service.recalcOnce();

        assertEquals(10000, affected);
        // 字段初始化值 = application.yml 里的默认口径（2/3/4/0.02）：
        // 保证"脱离 Spring 容器时用的也是文档里的那一组参数"
        verify(postMapper).recalcScores(2, 3, 4, 0.02);
    }

    @Test
    void recalcOnce_failureReturnsMinusOne_andSwallowsException() {
        when(postMapper.recalcScores(anyInt(), anyInt(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("db is down"));

        // 定时任务抛异常会污染调度线程、也让"重算失败"这件事无人知晓；
        // 这里要求：吞掉异常但用 -1 把失败信号交出去（调用方/日志据此判断）
        assertEquals(-1, service.recalcOnce());
    }

    @Test
    void recalcScheduled_doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.recalcScheduled();

        verifyNoInteractions(postMapper);
    }

    @Test
    void recalcScheduled_runsWhenEnabled() {
        when(postMapper.recalcScores(anyInt(), anyInt(), anyInt(), anyDouble())).thenReturn(7);

        service.recalcScheduled();

        verify(postMapper).recalcScores(anyInt(), anyInt(), anyInt(), anyDouble());
    }

    // ==================== M3-10 指标 ====================

    @Test
    void recalcOnce_recordsTimerAndLastAffectedRows() {
        when(postMapper.recalcScores(anyInt(), anyInt(), anyInt(), anyDouble())).thenReturn(10000);

        // 未跑过时 Gauge 应为 -1（与"跑过且 0 行"区分开）
        assertEquals(-1, registry.get("codelkup.score.last_affected_rows").gauge().value());

        service.recalcOnce();

        assertEquals(10000, registry.get("codelkup.score.last_affected_rows").gauge().value());
        assertEquals(1, registry.get("codelkup.score.rebuild").timer().count(),
                "每次重算都应落一次计时样本");
    }

    @Test
    void recalcOnce_failureCountsFailedMeter() {
        when(postMapper.recalcScores(anyInt(), anyInt(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("db is down"));

        service.recalcOnce();

        // 失败必须有独立的计数器：只靠日志（生产是 INFO 级、且没人盯）发现不了"一直在失败"
        assertEquals(1, registry.get("codelkup.score.rebuild.failed").counter().count());
    }
}