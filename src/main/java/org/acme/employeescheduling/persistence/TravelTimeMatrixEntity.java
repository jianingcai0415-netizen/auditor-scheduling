package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code travel_time_matrix} table（两坐标点间驾车耗时缓存）.
 *
 * <p><b>缓存的身份是坐标对，不是站点对。</b>两点之间的驾车耗时只取决于这两点本身，
 * 因此把坐标作为主键就让缓存与 {@code locations} 的行身份彻底解耦：重命名站点不影响它，
 * 改坐标只影响该站点的那些对，删站点也不会连带删除仍可能复用的数据。V21 之前这张表用
 * {@code from_location_id}/{@code to_location_id} 加两个 {@code ON DELETE CASCADE} 外键，
 * 结果是站点表任何改动都会让整份缓存作废、必须重新消耗高德配额（30 站点 870 对）。</p>
 *
 * <p>每对存<b>单行</b>（按 {@link org.acme.employeescheduling.utils.TravelMatrix.PairKey}
 * 的规范序），读写时对称展开，因此 n 个站点共 n×(n−1)/2 行。坐标在写入前量化到 5 位小数
 * （≈1.1 m），使同一站点按同一坐标重建后仍能命中。</p>
 *
 * <p>{@code computed_at} 为 epoch 毫秒的字符串（沿用本项目所有时间列的 TEXT 惯例）；
 * 新鲜度按行判定，不再有"整份矩阵一起校验"的元数据行。</p>
 */
@Entity
@Table(name = "travel_time_matrix")
public class TravelTimeMatrixEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "from_lat", nullable = false)
    public double fromLat;

    @Column(name = "from_lon", nullable = false)
    public double fromLon;

    @Column(name = "to_lat", nullable = false)
    public double toLat;

    @Column(name = "to_lon", nullable = false)
    public double toLon;

    @Column(name = "minutes", nullable = false)
    public int minutes;

    @Column(name = "distance_meters")
    public Integer distanceMeters;

    @Column(name = "computed_at", nullable = false)
    public String computedAt;

    public TravelTimeMatrixEntity() {
    }

    public TravelTimeMatrixEntity(double fromLat, double fromLon, double toLat, double toLon,
            int minutes, Integer distanceMeters, String computedAt) {
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.toLat = toLat;
        this.toLon = toLon;
        this.minutes = minutes;
        this.distanceMeters = distanceMeters;
        this.computedAt = computedAt;
        normalizeOrder();
    }

    /**
     * @brief 把 {@code (from, to)} 调整为规范序（按 (lat, lon) 字典序），使同一对无论以哪个方向
     *        写入都落在同一行。
     *
     * <p>这个不变量必须是所有写入路径共同遵守的：缓存按坐标对查表，而查表前会把键规范化为
     * {@code from <= to}。若某条写入路径存成反向，查表就找不到它，随后会把这对当"缺失"再算一遍
     * 并插入第二行 —— V21 迁移最初就犯了这个错，435 对变成了 679 行（244 对两个方向各存一份）。
     * 因此本方法放在实体上，由 {@link TravelMatrixStore#save} 无条件调用，而不是依赖调用方自觉。</p>
     *
     * <p>判定规则必须与 {@code TravelMatrix.PairKey.of} 保持一致。</p>
     */
    public final void normalizeOrder() {
        boolean reversed = fromLat > toLat || (fromLat == toLat && fromLon > toLon);
        if (!reversed) {
            return;
        }
        double lat = fromLat;
        double lon = fromLon;
        fromLat = toLat;
        fromLon = toLon;
        toLat = lat;
        toLon = lon;
    }
}
