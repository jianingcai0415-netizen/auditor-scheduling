package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * @brief 位置分的标定：跨城之间必须还能分出远近。
 *
 * @details 2026-10-27 复核发现原实现把基准写死 240 分钟（4 小时）。国内跨城转场动辄
 *          1800~2800 分钟，全部越过 240 直接扣满 0 分，30% 的位置权重退化成
 *          「同城 / 不同城」二值——实测乌鲁木齐场景 45/45 人、另一场景 308/309 人都是 0.0，
 *          把位置权重调到 100% 时全场总分 0（只剩转场分钟兜底排序）。
 *          现在基准走配置，默认 2880 分钟，覆盖实测最远的 2777 分钟。
 */
class LocationScoreTest {

    private static final int DEFAULT_BASELINE = 2880;

    @Test
    void sameCityScoresFullMarks() {
        assertEquals(100.0, SchedulePlanResource.locationScore(0, DEFAULT_BASELINE), 0.0001);
    }

    @Test
    void nearerCityScoresHigherThanFartherCity() {
        // 实测驾车耗时：北京→乌鲁木齐 1769、南京 2310、深圳 2777。
        double beijing = SchedulePlanResource.locationScore(1769, DEFAULT_BASELINE);
        double nanjing = SchedulePlanResource.locationScore(2310, DEFAULT_BASELINE);
        double shenzhen = SchedulePlanResource.locationScore(2777, DEFAULT_BASELINE);
        assertEquals(true, beijing > nanjing, "北京应高于南京");
        assertEquals(true, nanjing > shenzhen, "南京应高于深圳");
        assertEquals(true, shenzhen > 0.0, "最远的城市不该被压到 0");
    }

    @Test
    void baselineIsWhereScoreReachesZero() {
        assertEquals(0.0, SchedulePlanResource.locationScore(DEFAULT_BASELINE, DEFAULT_BASELINE), 0.0001);
        assertEquals(0.0, SchedulePlanResource.locationScore(DEFAULT_BASELINE + 500, DEFAULT_BASELINE), 0.0001);
        // 半程 = 50 分
        assertEquals(50.0, SchedulePlanResource.locationScore(DEFAULT_BASELINE / 2, DEFAULT_BASELINE), 0.0001);
    }

    /** 旧标定的回归护栏：同样的 1769 分钟，旧基准下是 0 分——这正是被修掉的问题。 */
    @Test
    void oldFourHourBaselineFlattenedEveryIntercityCandidate() {
        assertEquals(0.0, SchedulePlanResource.locationScore(1769, 240), 0.0001);
        assertEquals(0.0, SchedulePlanResource.locationScore(2777, 240), 0.0001);
    }
}
