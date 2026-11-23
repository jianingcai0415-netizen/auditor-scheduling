package org.acme.employeescheduling.config;

import org.acme.employeescheduling.utils.TravelMatrix;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * @brief 启动时在后台预热「站点间驾车耗时」矩阵。
 *
 * <p>历史缺陷：矩阵在首次候选评估时才惰性加载/重算，重启（或缓存 24 小时过期）后的
 * 头几个候选查询里，所有转场都拿不到值、按 120 分钟兜底，榜单被"压平"成能力排序——
 * 与用户随后看到的稳定榜单不一致（2026-10-15 实测复现）。</p>
 *
 * <p>预热放在独立虚拟线程里执行（不阻塞启动、不占请求线程）：内部先尝试从持久化库
 * 恢复（不消耗高德配额，通常毫秒级完成），必要时才调用高德重算（约十几秒）。</p>
 */
@ApplicationScoped
public class TravelMatrixWarmup {

    void warmOnStart(@Observes StartupEvent event) {
        Thread warmup = new Thread(() -> {
            var requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                TravelMatrix.ensure();
            } catch (Throwable warmupFailed) {
                // ensure() 内部已降级处理；下一批候选请求仍会按节流重试库恢复。
            } finally {
                requestContext.terminate();
            }
        }, "travel-matrix-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }
}
