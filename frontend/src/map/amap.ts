/**
 * @file amap.ts
 * @brief 高德地图 JS API 2.0 的加载器。
 *
 * @details
 * **密钥不编进前端包**：从 `/map-config` 运行时读取，因此换 Key 只需要改配置并重启后端，
 * 不需要重新构建前端。取值来源见 application.properties 的 `app.map.*`：
 * 环境变量 `AMAP_JS_KEY` / `AMAP_JS_SECURITY_CODE`，或 jar 同级的
 * `config/application.properties`。
 *
 * 申请方式：高德开放平台 → 应用管理 → 创建应用 → 添加 Key，服务平台选「Web端(JS API)」，
 * 并取同一应用下的「安全密钥」。务必在控制台配置 Referer 白名单——浏览器端总能看到 Key，
 * 授权范围只能靠白名单约束。
 *
 * 安全密钥必须在 SDK 脚本执行前写入 `window._AMapSecurityConfig`，否则底图与打点都会失败；
 * 改到 onload 里设置就晚了。
 */

interface AmapConfig {
  configured: boolean
  jsKey: string
  securityCode: string
}

declare global {
  interface Window {
    // 高德 JS API 没有官方 TypeScript 类型包，这里只是挂一个全局对象；
    // 真写一套伪类型反而会掩盖 SDK 的真实形状，所以刻意保留 any。
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    AMap?: any
    _AMapSecurityConfig?: { securityJsCode: string }
  }
}

/** @brief 配置缺失时抛出的错误码，页面据此显示申请引导而不是"加载失败"。 */
export const AMAP_NOT_CONFIGURED = 'AMAP_NOT_CONFIGURED'

/**
 * @brief 底图的审图号（自然资源部地图技术审查中心核发）。
 *
 * @details 中国地图合规要求：公开展示地图必须标注审图号。高德 JS API 默认会在右下角
 * 渲染 `© AutoNavi - GS(...)` 版权条，但该元素可能被自定义样式或容器裁切遮挡，
 * 因此页面底部另作一次显式标注。**换底图供应商或底图版本升级时须同步更新此值**
 * （高德官网当前标注为 GS(2025)5996号）。
 */
export const AMAP_APPROVAL_NO = 'GS(2025)5996'

const CONFIG_URL = '/map-config'

let configPromise: Promise<AmapConfig> | null = null
let loading: Promise<void> | null = null

/** @brief 读取并缓存运行时配置（并发调用共享同一个请求）。 */
export function getAmapConfig(): Promise<AmapConfig> {
  if (!configPromise) {
    configPromise = fetch(CONFIG_URL, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error('AMAP_CONFIG_UNAVAILABLE')
        }
        return response.json() as Promise<AmapConfig>
      })
      .catch((error) => {
        // 失败不缓存，否则一次网络抖动会让页面永久停在错误态。
        configPromise = null
        throw error
      })
  }
  return configPromise
}

/** @brief 按需加载 JS API（只加载一次，并发调用共享同一个 Promise）。 */
export function loadAmap(): Promise<void> {
  if (window.AMap) {
    return Promise.resolve()
  }
  if (loading) {
    return loading
  }
  loading = (async () => {
    const config = await getAmapConfig()
    if (!config.configured) {
      throw new Error(AMAP_NOT_CONFIGURED)
    }
    await new Promise<void>((resolve, reject) => {
      window._AMapSecurityConfig = { securityJsCode: config.securityCode }
      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(config.jsKey)}`
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => reject(new Error('AMAP_LOAD_FAILED'))
      document.head.appendChild(script)
    })
  })().catch((error) => {
    loading = null
    throw error
  })
  return loading
}
