package org.acme.employeescheduling.utils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.TravelMatrixStore;
import org.acme.employeescheduling.persistence.TravelTimeMatrixEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

/**
 * 站点间「驾车耗时」矩阵：增量预计算 + 内存缓存 + 降级。
 *
 * <p>求解器约束是纯静态方法（无法注入 CDI bean），因此矩阵以静态方式持有：
 * REST 请求线程在候选评分 / 求解提交前调用 {@link #ensure()} 补算缺失部分；
 * 求解器与候选评分此后都只读内存缓存。高德不可用、配额超限或坐标缺失时，
 * 一律降级回直线距离估算（同地 0 / 缺坐标 120 / 球面距离×1.25+20），保证离线也能排程。</p>
 *
 * <p><b>缓存的身份是坐标对，不是站点对（V21 起）。</b>两点之间的驾车耗时只取决于这两点，
 * 因此持久化缓存按量化坐标索引，与 {@code locations} 的行身份解耦：重命名站点不触发任何
 * 计算，改坐标只作废该站点的对，新增站点只补算它的 n−1 对。V21 之前缓存按
 * {@code from/to_location_id} 加外键索引，站点表任何改动都会让整份缓存作废、
 * 必须重新消耗高德配额（30 站点 870 对，而个人 Key 的 QPS 只有 3-4）。</p>
 *
 * <p>接口：高德「距离测量」{@code /v3/distance}（type=1 驾车，仅国内坐标，考虑路况）。
 * 每次调用以单个站点为 destination、其余缺失站点为 origins（分批至多 99 个），
 * 一次取回一批单向耗时。</p>
 *
 * <p><b>限流实测（2026-10，个人开发者 Web 服务 Key）</b>：并发 QPS 约 3-4 封顶，
 * 超出返回 {@code status=0, infocode=10021 (CUQPS_HAS_EXCEEDED_THE_LIMIT)}。
 * 现为<b>顺序请求 + 400ms 全局间隔（≈2.5 QPS）+ 每批次 3 次重试</b>，
 * 并输出完整日志（成功对数 / 失败批次数 / 耗时），部分失败时以 WARN 明示，不再静默。</p>
 */
public final class TravelMatrix {

    private static final Logger LOG = Logger.getLogger(TravelMatrix.class);

    private static final String AMAP_DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    /** 单条缓存记录的有效期：24 小时（毫秒）。 */
    private static final long TTL_MILLIS = 24L * 3600_000L;
    /** 计算失败后的退避重试间隔（毫秒）：避免每次请求都重试失败。 */
    private static final long RETRY_MILLIS = 5L * 60_000L;
    /** 读取持久化缓存的节流间隔（毫秒）：读库不消耗高德配额，故比计算退避更积极。 */
    private static final long RESTORE_RETRY_MILLIS = 10_000L;
    /** 长期未使用坐标对的保留期（毫秒）：超过即由 {@link TravelMatrixStore#save} 清理。 */
    private static final long RETENTION_MILLIS = 30L * 24 * 3600_000L;
    /** 高德 distance 接口单次 origins 上限（官方 100，留 1 个余量）。 */
    private static final int ORIGIN_BATCH = 99;
    private static final int API_TIMEOUT_SECONDS = 10;
    /** 相邻两次高德请求的启动间隔（毫秒）：全局限速 ≈2.5 QPS，低于实测并发上限。 */
    private static final long REQUEST_INTERVAL_MILLIS = 400L;
    /** 单个批次失败后的重试次数（不含首次）。10021 限流一般一次退避即可恢复。 */
    private static final int MAX_RETRIES = 2;
    /** 重试退避基数（毫秒）：第 n 次重试前睡 n × 基数。 */
    private static final long RETRY_BACKOFF_BASE_MILLIS = 500L;
    /** 坐标量化比例：5 位小数 ≈ 1.1 m。写入与查表必须共用本常量。 */
    private static final double COORD_SCALE = 100_000d;

    private static final Object LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(API_TIMEOUT_SECONDS)).build();

    /**
     * @brief 量化后的坐标点。用 {@code long}（放大 {@link #COORD_SCALE} 倍）而非 double 作键，
     *        避免浮点相等性参与 Map 查找。
     */
    public record CoordKey(long lat, long lon) {

        static CoordKey of(double latitude, double longitude) {
            return new CoordKey(Math.round(latitude * COORD_SCALE), Math.round(longitude * COORD_SCALE));
        }

        double latValue() {
            return lat / COORD_SCALE;
        }

        double lonValue() {
            return lon / COORD_SCALE;
        }
    }

    /**
     * @brief 无序坐标对，按 (lat, lon) 字典序规范化，使 (A,B) 与 (B,A) 是同一个键——
     *        缓存里每对只存一行。
     */
    public record PairKey(CoordKey from, CoordKey to) {

        static PairKey of(CoordKey a, CoordKey b) {
            int cmp = a.lat != b.lat ? Long.compare(a.lat, b.lat) : Long.compare(a.lon, b.lon);
            return cmp <= 0 ? new PairKey(a, b) : new PairKey(b, a);
        }
    }

    /** 内存矩阵：key = (fromId &lt;&lt; 32) | toId，值为驾车耗时（分钟）。空表示尚无可用值。 */
    private static volatile Map<Long, Integer> matrix = Map.of();
    /**
     * 矩阵的新鲜度基准：全部所需对都在时，为其中<b>最早</b>一条的时间戳；否则为 0（视为未就绪）。
     * 取最早一条是为了不把年轻条目"顺带延长"——只要有一条过期就整体进入刷新节奏，而刷新本身
     * 只补算过期的那部分。
     */
    private static volatile long computedAt = 0L;
    private static volatile long lastAttemptAt = 0L;
    private static volatile long lastRestoreAttemptAt = 0L;
    private static volatile String amapKey = null;

    private TravelMatrix() {
    }

    /** 读取缓存的驾车耗时（分钟）；未命中返回 null，由调用方降级。 */
    public static Integer get(int fromId, int toId) {
        if (fromId == toId) {
            return 0;
        }
        return matrix.get(pairKey(fromId, toId));
    }

    /** 是否已成功预计算过矩阵（用于诊断/日志，非必须）。 */
    public static boolean ready() {
        return !matrix.isEmpty();
    }

    /**
     * 确保矩阵就绪。需在 REST 请求线程（有 DB session）调用：内部会读站点坐标表。
     *
     * <p>顺序：内存缓存 → 持久化缓存（命中且未过期的对直接复用，<b>不调用高德</b>）
     * → 只对缺失或过期的坐标对调用高德补算。</p>
     *
     * <p>读取缓存在失败退避期内也会按节流重试（{@link #RESTORE_RETRY_MILLIS}）：
     * 它不消耗高德配额，因此不该被计算失败拖累——否则一次瞬时读失败会把所有候选评分
     * 压到 120 分钟的兜底转场上，持续数分钟（历史缺陷：重启后的「榜单被压平」窗口）。</p>
     */
    public static void ensure() {
        long now = System.currentTimeMillis();
        if (cacheValid(now)) {
            return;
        }
        // 快速退出：计算仍在退避期且最近才尝试过读缓存时，无需排队等锁——直接走降级路径。
        // 这保证重算进行中（持锁数秒）不会让并发的候选评估串行等待。
        if (!canRetry(now) && (now - lastRestoreAttemptAt) < RESTORE_RETRY_MILLIS) {
            return;
        }
        synchronized (LOCK) {
            now = System.currentTimeMillis();
            if (cacheValid(now)) {
                return;
            }
            CacheView view = null;
            if (now - lastRestoreAttemptAt >= RESTORE_RETRY_MILLIS) {
                lastRestoreAttemptAt = now;
                view = reloadFromStore(now);
                if (view != null && view.missing().isEmpty()) {
                    return;
                }
            }
            if (!canRetry(now)) {
                return;
            }
            lastAttemptAt = now;
            try {
                compute(view, now);
            } catch (Throwable t) {
                // 保持现有矩阵，调用方降级；lastAttemptAt 已更新，进入退避期。
                LOG.warnf(t, "高德转场矩阵计算异常，本次按直线距离降级");
            }
        }
    }

    /**
     * @brief 本次从持久化缓存加载的结果，供 {@link #compute} 补算后合并。
     *
     * @param minutesByPair 已有可用值（新鲜优先；过期条目用于在刷新完成前保持真实量级）
     * @param missing 没有任何可用值、或已过期的坐标对——这些需要调用高德
     */
    private record CacheView(Map<PairKey, Integer> minutesByPair, Set<PairKey> missing) {
    }

    /**
     * 从持久化缓存按坐标对装配内存矩阵，并算出还缺哪些对。
     *
     * <p>过期的条目不会立刻丢弃：它们仍以真实量级参与评分（远好于全员 120 分钟的兜底值），
     * 同时进入 {@code missing} 等待按退避节奏补算。这样「缓存刚过期」不会造成榜单被压平。</p>
     *
     * @return 装配结果；库不可用或读失败时返回 null（调用方按无缓存处理）
     */
    private static CacheView reloadFromStore(long now) {
        TravelMatrixStore store = TravelMatrixStore.instance();
        if (store == null) {
            return null;
        }
        try {
            List<LocationEntity> locations = activeLocationsWithCoords();
            if (locations.size() < 2) {
                matrix = Map.of();
                computedAt = 0L;
                return new CacheView(Map.of(), Set.of());
            }
            Map<PairKey, TravelTimeMatrixEntity> stored = new HashMap<>();
            for (TravelTimeMatrixEntity row : store.loadAll()) {
                stored.put(pairOf(row), row);
            }
            Set<PairKey> needed = allPairs(locations);
            Map<PairKey, Integer> fresh = new HashMap<>();
            Map<PairKey, Integer> stale = new HashMap<>();
            Set<PairKey> missing = new LinkedHashSet<>();
            long oldestFresh = Long.MAX_VALUE;
            for (PairKey pair : needed) {
                TravelTimeMatrixEntity row = stored.get(pair);
                if (row == null) {
                    missing.add(pair);
                    continue;
                }
                long computedMillis = parseEpochMillis(row.computedAt);
                if (computedMillis > 0L && (now - computedMillis) < TTL_MILLIS) {
                    fresh.put(pair, row.minutes);
                    oldestFresh = Math.min(oldestFresh, computedMillis);
                } else {
                    stale.put(pair, row.minutes);
                    missing.add(pair);
                }
            }
            // 新鲜优先；过期条目补齐新鲜条目未覆盖的对（保持真实量级，避免降级成 120 分钟平台）。
            Map<PairKey, Integer> usable = new HashMap<>(stale);
            usable.putAll(fresh);
            rebuildMatrix(locations, usable);
            boolean complete = missing.isEmpty() && !fresh.isEmpty();
            computedAt = complete ? oldestFresh : 0L;
            if (complete) {
                LOG.infof("转场矩阵已从数据库缓存恢复：%d 对（%d 个站点），无需调用高德",
                        fresh.size(), locations.size());
            } else {
                // 明细是刻意的：命中数为 0 而库中有行，说明键对不上（坐标量化或顺序规范化出了问题），
                // 那会静默退化成"每次全量重算"，烧掉高德配额却看不出异常。
                LOG.infof("转场缓存装配：库中 %d 行 / 需要 %d 对（%d 个站点），命中新鲜 %d、过期 %d，待补算 %d",
                        stored.size(), needed.size(), locations.size(), fresh.size(), stale.size(),
                        missing.size());
            }
            return new CacheView(usable, missing);
        } catch (Throwable readFailed) {
            LOG.warnf(readFailed, "读取转场缓存失败，本次将退化为整份重算（会消耗高德配额）");
            return null;
        }
    }

    /**
     * 主动失效：<b>只清内存视图，不再清库</b>。
     *
     * <p>V21 之前这里会 {@code store.clearAll()}——因为缓存按站点 id 索引，站点改了、旧值就再也
     * 不能用了。现在缓存按坐标索引，改坐标只是让"需要的键"变成另一个，旧条目自然不再被命中；
     * 删站点同理。于是站点表的任何改动都不再烧掉已算好的数据，这正是本次改造的目的。</p>
     */
    public static void invalidate() {
        synchronized (LOCK) {
            matrix = Map.of();
            computedAt = 0L;
            lastAttemptAt = 0L;
            lastRestoreAttemptAt = 0L;
        }
    }

    /**
     * 站点坐标 / 启用状态 / 站点列表变更后调用：立即清空内存视图，并在事务<b>提交后</b>再清一次。
     *
     * <p>只在提交后清一次是不够的（本次事务尚未生效，清了也白清）；只立即清则留有竞态：
     * 并发的排程请求可能在本次事务提交前就用旧坐标装配好内存矩阵。两段式清空消除该窗口，
     * 理由与 {@code DemoDataRepository.invalidateTranslationsAfterCommit} 一致。</p>
     */
    public static void invalidateAfterCommit() {
        invalidate();
        try {
            TransactionSynchronizationRegistry registry = Arc.container()
                    .instance(TransactionSynchronizationRegistry.class).get();
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        invalidate();
                    }
                }
            });
        } catch (Exception noTransactionOrContainerNotReady) {
            // 无活动事务（或 CDI 容器未就绪）：上面的 invalidate() 已经生效。
        }
    }

    private static boolean cacheValid(long now) {
        return !matrix.isEmpty() && computedAt > 0L && (now - computedAt) < TTL_MILLIS;
    }

    private static boolean canRetry(long now) {
        // 注意：不只允许「矩阵为空」时重算——旧值兜底后矩阵非空但未就绪，仍需按退避节奏刷新。
        return (now - lastAttemptAt) >= RETRY_MILLIS;
    }

    /** 旧公式降级：同地 0；缺坐标 120；否则球面距离×1.25+20（下限 20）。 */
    public static int fallback(int fromId, int toId) {
        if (fromId == toId) {
            return 0;
        }
        LocationEntity from = LocationEntity.findById(fromId);
        LocationEntity to = LocationEntity.findById(toId);
        if (from == null || to == null || from.latitude == null || from.longitude == null
                || to.latitude == null || to.longitude == null) {
            return 120;
        }
        double km = haversineKm(from.latitude, from.longitude, to.latitude, to.longitude);
        return Math.max(20, (int) Math.ceil(km * 1.25 + 20));
    }

    /**
     * 增量补算：只对 {@code view.missing} 里的坐标对调用高德，按目的地分组、每组分批（≤99）。
     * 顺序执行 + 全局限速 + 每批重试，规避并发 QPS 限制（infocode 10021）。
     * 部分失败时保留已取回的对（缺失对由调用方逐对降级），并以 WARN 明示失败批次数。
     */
    private static void compute(CacheView view, long now) {
        String key = resolveKey();
        if (key.isEmpty()) {
            LOG.warn("未在 .env 中找到 AMAP_API_KEY，转场耗时使用直线距离估算（配置后可获得真实驾车耗时）");
            return;
        }
        List<LocationEntity> locations = activeLocationsWithCoords();
        if (locations.size() < 2) {
            return;
        }
        Set<PairKey> missing = view == null ? allPairs(locations) : view.missing();
        if (missing.isEmpty()) {
            return;
        }
        Map<CoordKey, List<CoordKey>> originsByDestination = new LinkedHashMap<>();
        for (PairKey pair : missing) {
            originsByDestination.computeIfAbsent(pair.to(), ignored -> new ArrayList<>()).add(pair.from());
        }
        Map<PairKey, Integer> minutes = new HashMap<>();
        Map<PairKey, Integer> meters = new HashMap<>();
        int failedBatches = 0;
        int totalBatches = 0;
        long startedAt = System.currentTimeMillis();
        long nextAllowedStart = 0L;
        boolean interrupted = false;
        for (Map.Entry<CoordKey, List<CoordKey>> entry : originsByDestination.entrySet()) {
            if (interrupted) {
                break;
            }
            CoordKey destination = entry.getKey();
            List<CoordKey> origins = entry.getValue();
            for (int start = 0; start < origins.size(); start += ORIGIN_BATCH) {
                List<CoordKey> batch = origins.subList(start, Math.min(origins.size(), start + ORIGIN_BATCH));
                totalBatches++;
                if (!pace(nextAllowedStart)) {
                    interrupted = true;
                    break;
                }
                nextAllowedStart = System.currentTimeMillis() + REQUEST_INTERVAL_MILLIS;
                boolean ok = false;
                for (int attempt = 0; attempt <= MAX_RETRIES && !ok; attempt++) {
                    if (attempt > 0 && !backoff(attempt)) {
                        interrupted = true;
                        break;
                    }
                    ok = fetchBatch(key, destination, batch, minutes, meters);
                }
                if (!ok) {
                    failedBatches++;
                    LOG.debugf("高德批次失败：目的地 (%.5f, %.5f)，重试 %d 次后仍失败",
                            destination.latValue(), destination.lonValue(), MAX_RETRIES);
                }
            }
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (interrupted) {
            LOG.warn("转场矩阵补算被中断，保留已取回的部分");
        }
        if (minutes.isEmpty()) {
            LOG.warnf("高德转场矩阵本轮全部失败（%d/%d 个批次，%d 个站点），缺失对按直线距离降级；%d 分钟后自动重试",
                    failedBatches, totalBatches, locations.size(), RETRY_MILLIS / 60_000L);
            return;
        }
        if (failedBatches > 0) {
            LOG.warnf("高德转场矩阵部分完成：本次补算 %d/%d 对（%d/%d 个批次失败，缺失对按直线距离降级），耗时 %d ms",
                    minutes.size(), missing.size(), failedBatches, totalBatches, elapsed);
        } else {
            LOG.infof("高德转场矩阵补算完成：本次 %d 对（共 %d 个站点待用），耗时 %d ms",
                    minutes.size(), locations.size(), elapsed);
        }
        persist(minutes, meters, now);
        // 落库后以库为准重建内存矩阵与新鲜度基准（各行的 computed_at 不同，重量一遍最省心）。
        // 库不可用时退回用本轮结果直接装配，保证本次排程仍能用到真实数值。
        if (reloadFromStore(System.currentTimeMillis()) == null) {
            Map<PairKey, Integer> merged = new HashMap<>(view == null ? Map.of() : view.minutesByPair());
            merged.putAll(minutes);
            rebuildMatrix(locations, merged);
            computedAt = failedBatches == 0 ? now : 0L;
        }
    }

    /** 补算结果落库。落库失败只记录日志——内存矩阵已可用，不应因此影响本次排程。 */
    private static void persist(Map<PairKey, Integer> minutes, Map<PairKey, Integer> meters, long now) {
        TravelMatrixStore store = TravelMatrixStore.instance();
        if (store == null) {
            return;
        }
        String stamp = String.valueOf(now);
        List<TravelTimeMatrixEntity> rows = new ArrayList<>(minutes.size());
        for (Map.Entry<PairKey, Integer> entry : minutes.entrySet()) {
            PairKey pair = entry.getKey();
            rows.add(new TravelTimeMatrixEntity(pair.from().latValue(), pair.from().lonValue(),
                    pair.to().latValue(), pair.to().lonValue(), entry.getValue(),
                    meters.get(pair), stamp));
        }
        try {
            store.save(rows, now - RETENTION_MILLIS);
            LOG.debugf("转场矩阵已写入数据库：本次 %d 对", rows.size());
        } catch (Throwable persistFailed) {
            LOG.warnf(persistFailed, "转场缓存写入数据库失败，下次启动将重新调用高德");
        }
    }

    /** 按「站点 id 对」装配内存矩阵：坐标相同的不同站点得到同一个耗时（0）。 */
    private static void rebuildMatrix(List<LocationEntity> locations, Map<PairKey, Integer> minutesByPair) {
        Map<Long, Integer> built = new HashMap<>();
        for (LocationEntity from : locations) {
            CoordKey fromCoord = coordOf(from);
            for (LocationEntity to : locations) {
                if (from.id.equals(to.id)) {
                    continue;
                }
                CoordKey toCoord = coordOf(to);
                // 两个分支必须都是 Integer：写成 `? 0 : map.get(...)` 会让整个条件表达式按二元数值
                // 提升把 Integer 拆箱，只要该对尚未缓存就抛 NPE —— 恰好是"增量补算"第一次运行的情形，
                // 结果被上层 catch 当成"全量重算"，静默烧掉高德配额。
                Integer value = fromCoord.equals(toCoord)
                        ? Integer.valueOf(0)
                        : minutesByPair.get(PairKey.of(fromCoord, toCoord));
                if (value != null) {
                    built.put(pairKey(from.id, to.id), value);
                }
            }
        }
        matrix = built;
    }

    /**
     * 当前站点集合需要的全部坐标对（规范序、去重）。坐标完全相同的两个站点不产生对——
     * 它们之间耗时恒为 0，无需请求，也不该被当成"永远缺失"而反复补算。
     */
    private static Set<PairKey> allPairs(List<LocationEntity> locations) {
        Set<PairKey> pairs = new LinkedHashSet<>();
        for (LocationEntity from : locations) {
            CoordKey fromCoord = coordOf(from);
            for (LocationEntity to : locations) {
                if (from.id.equals(to.id)) {
                    continue;
                }
                CoordKey toCoord = coordOf(to);
                if (!fromCoord.equals(toCoord)) {
                    pairs.add(PairKey.of(fromCoord, toCoord));
                }
            }
        }
        return pairs;
    }

    private static CoordKey coordOf(LocationEntity location) {
        return CoordKey.of(location.latitude, location.longitude);
    }

    private static PairKey pairOf(TravelTimeMatrixEntity row) {
        return PairKey.of(CoordKey.of(row.fromLat, row.fromLon), CoordKey.of(row.toLat, row.toLon));
    }

    /** 全局限速：等到 nextAllowedStart 再放行；被中断返回 false。 */
    private static boolean pace(long nextAllowedStart) {
        long now = System.currentTimeMillis();
        while (nextAllowedStart > now) {
            try {
                Thread.sleep(nextAllowedStart - now);
            } catch (InterruptedException e) {
                return false;
            }
            now = System.currentTimeMillis();
        }
        return true;
    }

    /** 重试退避：睡 attempt × 基数毫秒；被中断返回 false。 */
    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_BASE_MILLIS * attempt);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** 一个批次的请求：destination 固定，origins 为该目的地待补算的点。结果按键写回两个 map。 */
    private static boolean fetchBatch(String key, CoordKey destination, List<CoordKey> origins,
            Map<PairKey, Integer> minutesOut, Map<PairKey, Integer> metersOut) {
        try {
            StringBuilder originsParam = new StringBuilder();
            for (CoordKey origin : origins) {
                if (originsParam.length() > 0) {
                    originsParam.append('|');
                }
                originsParam.append(origin.lonValue()).append(',').append(origin.latValue());
            }
            String query = "key=" + key
                    + "&origins=" + URLEncoder.encode(originsParam.toString(), StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(
                            destination.lonValue() + "," + destination.latValue(), StandardCharsets.UTF_8)
                    + "&type=1&output=JSON";
            HttpRequest request = HttpRequest.newBuilder(URI.create(AMAP_DISTANCE_URL + "?" + query))
                    .timeout(Duration.ofSeconds(API_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode root = MAPPER.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                // 10021=并发QPS超限（可重试）；10001/10002=Key 无效或服务未开通（重试无意义，由上层日志体现）
                LOG.debugf("高德返回失败：status=%s infocode=%s info=%s",
                        root.path("status").asText(), root.path("infocode").asText(),
                        root.path("info").asText());
                return false;
            }
            JsonNode results = root.path("results");
            for (int i = 0; i < origins.size() && i < results.size(); i++) {
                int seconds = results.get(i).path("duration").asInt(0);
                if (seconds <= 0) {
                    continue;
                }
                PairKey pair = PairKey.of(origins.get(i), destination);
                minutesOut.put(pair, Math.max(1, (int) Math.ceil(seconds / 60.0)));
                int distance = results.get(i).path("distance").asInt(0);
                if (distance > 0) {
                    metersOut.put(pair, distance);
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.debugf(t, "高德批次请求异常：目的地 (%.5f, %.5f)",
                    destination.latValue(), destination.lonValue());
            return false;
        }
    }

    private static List<LocationEntity> activeLocationsWithCoords() {
        List<LocationEntity> locations = new ArrayList<>();
        for (LocationEntity location : LocationEntity.<LocationEntity>listAll()) {
            if (location.active && location.latitude != null && location.longitude != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    private static String resolveKey() {
        if (amapKey == null) {
            synchronized (LOCK) {
                if (amapKey == null) {
                    amapKey = readAmapKeyFromEnvFile();
                }
            }
        }
        return amapKey;
    }

    /** 与 ResponsesCandidateProvider 一致：从工作目录的 .env 读取，忽略注释与空行。 */
    private static String readAmapKeyFromEnvFile() {
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                if ("AMAP_API_KEY".equals(trimmed.substring(0, separator).trim())) {
                    return trimmed.substring(separator + 1).trim();
                }
            }
        } catch (Exception ignored) {
            // 读取失败按无 Key 处理。
        }
        return "";
    }

    private static long parseEpochMillis(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable notANumber) {
            return 0L;
        }
    }

    /** key = (fromId &lt;&lt; 32) | toId。 */
    private static long pairKey(int fromId, int toId) {
        return ((long) fromId << 32) | (toId & 0xFFFFFFFFL);
    }

    /** 球面距离（公里），Haversine 公式。 */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1);
        double b = Math.toRadians(lat2);
        double dLat = b - a;
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(a) * Math.cos(b) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
