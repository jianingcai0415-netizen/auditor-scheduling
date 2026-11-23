package org.acme.employeescheduling.dto;

import java.util.List;

/**
 * @brief 审核员分布地图的响应结构（按城市聚合 + 每个城市的人员明细）。
 *
 * <p><b>工作口径</b>：{@code scope} 决定"有班"的判定窗口，字段统一叫 {@code inScope}
 * （而不是 onDuty）——因为选了「今日有班」之后，"有"并不等于"此刻正在岗"，
 * 沿用 onDuty 会让前端文案和语义对不上：</p>
 * <ul>
 *   <li>{@code now}（默认）：班次覆盖当前时刻 {@code start <= now <= end}</li>
 *   <li>{@code today}：班次与今天有交集，即今天有排班（含还没开始的）</li>
 *   <li>{@code next7}：班次与「现在起 7 天内」有交集</li>
 * </ul>
 *
 * <p>判定始终基于 {@code shifts}，<b>不用</b> {@code employees.operational_status} /
 * {@code busy_until}：这两个字段在当前数据里从未被维护（530 人全是 AVAILABLE、
 * busy_until 全空），用它们会让所有人显示为"空闲"，比没有这个功能更误导。</p>
 *
 * <p><b>隐私</b>：地图上的点位是<b>城市</b>（地点表里活跃且有坐标的行），不是个人位置。
 * 个人只在选中城市后于侧栏展示，不下发到地图点位，符合批量点位数据的最小化原则。</p>
 */
public final class MapOverviewDtos {

    private MapOverviewDtos() {
    }

    /**
     * @param locationId 城市对应的地点 id
     * @param auditors 该城市的在职审核员数。**按常驻视图** = 常驻该城市的总人数；
     *                 **按现场视图** = 此刻在该城市干活的人数（两种口径下都等于圆里的数字）
     * @param inScope 其中符合当前口径的人数
     * @param people 该城市的审核员明细（符合口径者排在前面）
     */
    public record City(int locationId, String name, double latitude, double longitude,
            int auditors, int inScope, List<Person> people) {
    }

    /**
     * @param name 展示名（first_name + last_name 拼接）
     * @param inScope 是否符合当前口径（有覆盖该窗口的班次）
     * @param shiftStart / shiftEnd / orderCode / stage 仅在 {@code inScope} 时有值
     */
    public record Person(Integer id, String code, String name, String email, boolean inScope,
            String shiftStart, String shiftEnd, String orderCode, String stage,
            /**
             * 当前班次的任务地点。按常驻视图打点时，与本字段不一致说明这趟是跨城出差
             * ——前端据此提示，避免"在南京却在上海干活"的误解。
             */
            String taskCity,
            /**
             * 常驻城市名。按现场视图打点时，点位是任务地点，这里补上"他从哪来"，
             * 否则用户看不出这个人是本地还是飞过来的。
             */
            String homeCity) {
    }

    /**
     * @param generatedAt 快照时间（yyyy-MM-dd HH:mm:ss），前端据此显示"数据时间"
     * @param scope 本次统计口径：now / today / next7
     * @param group 本次分组口径：home（按常驻地打点）/ onsite（按班次所在地打点）
     * @param unassigned 没有归属到任何有坐标城市的审核员
     */
    public record Overview(String generatedAt, String scope, String group, int totalAuditors, int totalInScope,
            int totalCities, List<City> cities, List<Person> unassigned) {
    }
}
