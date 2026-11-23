package org.acme.employeescheduling.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.acme.employeescheduling.utils.TravelMatrix.CoordKey;
import org.acme.employeescheduling.utils.TravelMatrix.PairKey;
import org.junit.jupiter.api.Test;

/**
 * 坐标键的不变量。
 *
 * <p>整套「按坐标对缓存」的设计建立在两件事上，两者一旦破了都会静默退化成缓存永不命中、
 * 每次都重新消耗高德配额：</p>
 * <ol>
 *   <li>写入与读回得到<b>同一个键</b>——值经过 {@code 量化 → 落库为 REAL → 读回 → 再量化}
 *       必须稳定；</li>
 *   <li>无序性——{@code (A,B)} 与 {@code (B,A)} 必须是同一个键，否则每对会存两行，
 *       且 UNIQUE 约束会被绕过。</li>
 * </ol>
 *
 * <p>这是纯计算测试，不需要 Quarkus 或数据库。</p>
 */
class TravelMatrixCoordinateKeyTest {

    /** 真实数据里的坐标形态（4 位小数）与边界值。 */
    private static final double[][] POINTS = {
            { 39.9042, 116.4074 },
            { 31.2304, 121.4737 },
            { 23.1291, 113.2644 },
            { 22.5431, 114.0579 },
            { 45.8038, 126.5350 },
            { -33.86880, 151.20930 },
            { 0.0, 0.0 },
            { 89.99999, 179.99999 },
            { -89.99999, -179.99999 },
    };

    @Test
    void quantizedPointSurvivesTheStorageRoundTrip() {
        for (double[] point : POINTS) {
            CoordKey original = CoordKey.of(point[0], point[1]);
            // 落库写的是量化后的十进制值，读回来再量化一次必须落到同一个整数键上。
            CoordKey reread = CoordKey.of(original.latValue(), original.lonValue());
            assertEquals(original, reread,
                    () -> "坐标 (" + point[0] + ", " + point[1] + ") 落库读回后键变了");
        }
    }

    @Test
    void aPairIsOrderIndependent() {
        CoordKey beijing = CoordKey.of(39.9042, 116.4074);
        CoordKey shanghai = CoordKey.of(31.2304, 121.4737);
        assertEquals(PairKey.of(beijing, shanghai), PairKey.of(shanghai, beijing),
                "(A,B) 与 (B,A) 必须是同一个键，否则每对会存两行");
    }

    @Test
    void distinctPointsProduceDistinctKeys() {
        CoordKey beijing = CoordKey.of(39.9042, 116.4074);
        CoordKey shanghai = CoordKey.of(31.2304, 121.4737);
        assertNotEquals(beijing, shanghai);
        // 1e-5 度（≈1.1 m）是量化精度：差一个量化步长就必须区分开，
        // 否则两个不同的站点会共用一份耗时。
        assertNotEquals(CoordKey.of(39.90420, 116.40740), CoordKey.of(39.90421, 116.40740));
    }

    @Test
    void subMillimetreJitterDoesNotChangeTheKey() {
        // 同一地点重新地理编码可能产生末位抖动，量化后必须仍命中同一份缓存。
        assertEquals(CoordKey.of(39.904200001, 116.407400002), CoordKey.of(39.9042, 116.4074));
    }
}
