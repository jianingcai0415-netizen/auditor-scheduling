package org.acme.employeescheduling.rest;

import java.util.Optional;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @file MapConfigResource.java
 * @brief 向 SPA 下发高德 JS API 的运行时配置。
 *
 * @details
 * 密钥刻意不编进前端源码：那样每换一个 Key 都要重新构建前端，对桌面部署是灾难。
 * 这里从 Quarkus 配置读取，取值优先级（由 Quarkus 本身决定）：
 *   1. 命令行 `-Dapp.map.amap-js-key=...`
 *   2. 环境变量 `AMAP_JS_KEY` / `AMAP_JS_SECURITY_CODE`
 *   3. 外部文件 `config/application.properties`（与 jar 同级，改完重启即生效）
 *
 * 这两项对浏览器本来就是可见的（Key 会出现在 SDK 的 script URL 里），因此不构成
 * 机密泄露；真正的防护是控制台的 Referer 白名单。接口仍要求登录，避免被无关的
 * 网络探测直接抓走。
 */
@Path("/map-config")
@RolesAllowed({ "ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER" })
public class MapConfigResource {

    /**
     * 用 Optional 而不是 `String defaultValue = ""`：SmallRye 把空字符串当作 null 处理，
     * 直接注入 String 会在应用启动阶段抛 ConfigurationException——即"没配置"这个正常状态
     * 反而让整个应用起不来。
     */
    @ConfigProperty(name = "app.map.amap-js-key")
    Optional<String> jsKey;

    @ConfigProperty(name = "app.map.amap-js-security-code")
    Optional<String> securityCode;

    public record MapConfig(boolean configured, String jsKey, String securityCode) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public MapConfig get() {
        String key = jsKey.map(String::trim).orElse("");
        String code = securityCode.map(String::trim).orElse("");
        // 两者缺一不可：JS API 2.0 没有安全密钥时底图和打点都会失败。
        boolean configured = !key.isEmpty() && !code.isEmpty();
        return new MapConfig(configured, key, code);
    }
}
