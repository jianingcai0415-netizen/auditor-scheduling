package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.acme.employeescheduling.persistence.TravelMatrixStore;
import org.acme.employeescheduling.persistence.TravelTimeMatrixEntity;
import org.acme.employeescheduling.utils.TravelMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;

/**
 * 转场缓存的持久化不变量。
 *
 * <p>这几条都对应过真实故障或正在修的缺陷，破了都是<b>静默</b>劣化——不报错，只是配额被烧掉、
 * 或缓存再也命中不了：</p>
 * <ol>
 *   <li><b>invalidate 不得清库</b>：V21 之前它调 {@code clearAll()}，于是重命名一个地点就会
 *       清掉整份矩阵，逼迫重新消耗高德配额（30 站点 870 对）。</li>
 *   <li><b>同一坐标对只能一行</b>：缓存按坐标查表，写入必须 upsert。</li>
 *   <li><b>顺序必须规范化</b>：查表前把键规范成 {@code from <= to}，因此反向写入若是新增一行，
 *       查表就永远找不到它，该对会被当成"缺失"反复重算。V21 迁移最初正是如此，
 *       435 对变成了 679 行。</li>
 * </ol>
 *
 * <p>读库走原生 JDBC：Panache 的静态方法依赖字节码增强，而增强不改写方法引用，
 * 且测试类不在增强范围内。写库走 {@link TravelMatrixStore}（CDI bean，已被增强）。</p>
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class TravelMatrixCacheRetentionTest {

    private static final long RETENTION_MILLIS = 30L * 24 * 3600_000L;

    @Inject
    TravelMatrixStore store;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clearCache() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM travel_time_matrix");
        }
    }

    @Test
    void savingTheSamePairTwiceKeepsOneRowAndRefreshesTheValue() throws Exception {
        long now = System.currentTimeMillis();
        store.save(List.of(pair(39.9042, 116.4074, 31.2304, 121.4737, 900, now)), now - RETENTION_MILLIS);
        store.save(List.of(pair(39.9042, 116.4074, 31.2304, 121.4737, 875, now)), now - RETENTION_MILLIS);

        List<Row> rows = rows();
        assertEquals(1, rows.size(), "同一坐标对必须是 upsert，而不是追加一行");
        assertEquals(875, rows.get(0).minutes(), "重算后应保存新值");
    }

    @Test
    void reversedCoordinatesStillProduceASingleRow() throws Exception {
        long now = System.currentTimeMillis();
        store.save(List.of(pair(39.9042, 116.4074, 31.2304, 121.4737, 900, now)), now - RETENTION_MILLIS);
        // 反方向写入同一物理对：必须命中同一行。
        store.save(List.of(pair(31.2304, 121.4737, 39.9042, 116.4074, 910, now)), now - RETENTION_MILLIS);

        List<Row> rows = rows();
        assertEquals(1, rows.size(), "反向写入必须是同一个键（V21 迁移曾因此多出 244 行）");
        // 规范序取 (lat, lon) 字典序较小者：31.2304 < 39.9042，故 from 落上海、to 落北京。
        assertEquals(31.2304, rows.get(0).fromLat(), 1e-9, "必须落在规范序上（from <= to）");
        assertEquals(39.9042, rows.get(0).toLat(), 1e-9);
        assertEquals(910, rows.get(0).minutes());
    }

    @Test
    void invalidateKeepsThePersistedCache() throws Exception {
        long now = System.currentTimeMillis();
        store.save(List.of(
                pair(1.5, 1.5, 2.5, 2.5, 10, now),
                pair(1.5, 1.5, 3.5, 3.5, 20, now)), now - RETENTION_MILLIS);
        assertEquals(2, rows().size());

        TravelMatrix.invalidate();

        assertEquals(2, rows().size(),
                "invalidate 只应清内存视图；再清库会让每次地点变更都重新消耗高德配额");
    }

    @Test
    void entriesOlderThanTheRetentionWindowArePruned() throws Exception {
        long now = System.currentTimeMillis();
        long stale = now - RETENTION_MILLIS - 3600_000L;
        store.save(List.of(pair(1.5, 1.5, 2.5, 2.5, 10, stale)), stale - RETENTION_MILLIS);
        // 第二次保存触发按年龄清理：上一条已超出保留期。
        store.save(List.of(pair(3.5, 3.5, 4.5, 4.5, 20, now)), now - RETENTION_MILLIS);

        List<Row> remaining = rows();
        assertEquals(1, remaining.size(), "超过保留期的条目应被清理，使表长期有界");
        assertEquals(20, remaining.get(0).minutes());
    }

    private record Row(double fromLat, double fromLon, double toLat, double toLon, int minutes) {
    }

    private List<Row> rows() throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT from_lat, from_lon, to_lat, to_lon, minutes FROM travel_time_matrix")) {
            while (rs.next()) {
                rows.add(new Row(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getInt(5)));
            }
        }
        return rows;
    }

    private static TravelTimeMatrixEntity pair(double fromLat, double fromLon, double toLat, double toLon,
            int minutes, long computedAt) {
        return new TravelTimeMatrixEntity(fromLat, fromLon, toLat, toLon, minutes, null,
                String.valueOf(computedAt));
    }
}
