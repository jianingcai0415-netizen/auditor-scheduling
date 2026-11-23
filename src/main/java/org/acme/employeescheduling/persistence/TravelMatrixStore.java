package org.acme.employeescheduling.persistence;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;

/**
 * @brief 「两坐标点间驾车耗时」缓存的持久化读写。
 *
 * <p>目的：把高德 {@code /v3/distance} 的结果落库，使重启后<b>无需再消耗高德配额</b>
 * 即可恢复（个人开发者 Key 并发 QPS 仅 3-4，且有日配额上限）。</p>
 *
 * <p><b>与旧实现的关键差别：不再整份覆盖。</b>表按坐标对索引（见
 * {@link TravelTimeMatrixEntity}），因此缓存与 {@code locations} 的行身份无关。
 * 旧实现每次保存都先 {@code deleteAll()} 再重写，配合站点表外键，导致任何站点改动
 * 都会让整份缓存失效。现在只做<b>逐对 upsert</b>，其余条目原样保留——新增一个站点
 * 只写它的 n−1 对，而不是重算并重写全部 n×(n−1)/2 对。</p>
 *
 * <p><b>过期清理</b>：按 {@code computed_at} 的年龄删除。这不会误删仍在使用的条目——
 * 需要用的条目会在超过 TTL（24 小时）后被重新计算并写入新的时间戳，所以能老到保留期
 * 的只可能是长期无人使用的坐标对。</p>
 *
 * <p><b>{@code @Unremovable} 是必需的</b>：唯一调用方是静态工具类 {@code TravelMatrix}
 * （求解器约束是静态方法，无法注入 CDI bean），因此本类只有程序化 {@code Arc} 查找、
 * 没有注入点，会被 Quarkus 构建期当作「未使用 bean」移除，导致矩阵永远不落库。</p>
 */
@ApplicationScoped
@Unremovable
public class TravelMatrixStore {

    /** @brief 读入全部缓存行。典型的表很小（n 个站点 n×(n−1)/2 行，30 站点 = 435 行）。 */
    public List<TravelTimeMatrixEntity> loadAll() {
        return TravelTimeMatrixEntity.listAll();
    }

    /**
     * @brief 增量写入本次新算出的对，并按保留期清理长期未使用的条目。
     *
     * <p>使用<b>独立事务</b>：既不被调用方事务回滚，也绝不延长调用方事务
     * （求解请求的事务不应被几百行写入拖住）。</p>
     *
     * @param rows 本次新计算的坐标对（规范序单行，坐标已量化）
     * @param retentionCutoffMillis 早于此时间的条目视为长期未用并删除
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void save(List<TravelTimeMatrixEntity> rows, long retentionCutoffMillis) {
        for (TravelTimeMatrixEntity row : rows) {
            // 防御性重排：无论调用方怎么构造这行（含无参构造 + 逐个赋值），都必须落在规范序上，
            // 否则下面按精确坐标做 upsert 时会找不到反向已存在的那一行，从而插出重复行。
            row.normalizeOrder();
            // 同一坐标对可能已有旧值（TTL 过期重算，或坐标未变而重新启用）：先删后插即为 upsert。
            TravelTimeMatrixEntity.delete("fromLat = ?1 and fromLon = ?2 and toLat = ?3 and toLon = ?4",
                    row.fromLat, row.fromLon, row.toLat, row.toLon);
        }
        if (!rows.isEmpty()) {
            TravelTimeMatrixEntity.persist(rows);
        }
        // epoch 毫秒的十进制字符串在 2286 年之前固定为 13 位，故此处字符串比较等价于数值比较。
        TravelTimeMatrixEntity.delete("computedAt < ?1", String.valueOf(retentionCutoffMillis));
    }

    /** @brief 便捷取值：容器内取 bean；容器未就绪时返回 null（调用方按无缓存处理）。 */
    public static TravelMatrixStore instance() {
        try {
            return Arc.container().instance(TravelMatrixStore.class).get();
        } catch (Throwable containerNotReady) {
            return null;
        }
    }
}
