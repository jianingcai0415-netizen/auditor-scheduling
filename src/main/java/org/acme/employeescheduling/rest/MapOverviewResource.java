package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.dto.MapOverviewDtos;
import org.acme.employeescheduling.dto.MapOverviewDtos.City;
import org.acme.employeescheduling.dto.MapOverviewDtos.Overview;
import org.acme.employeescheduling.dto.MapOverviewDtos.Person;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * @brief 审核员分布地图的数据源：一次返回「城市 → 审核员 → 工作状态」的完整快照。
 *
 * <p>刻意做成<b>单个聚合请求</b>而不是地图点位 + 每城一个请求：全量也就几百人、几十个城市，
 * 一次下发可以让点击城市时的侧栏零延迟，也避免地图打点时并发请求把连接池占满。</p>
 *
 * <p><b>时间口径由 {@code scope} 决定</b>（见 {@link Scope}）。默认 {@code now} 与改造前行为一致；
 * {@code today} / {@code next7} 解决的是"33 条未来班次被显示成空闲"的问题——
 * 那是"此刻没在岗"，但不是"今天没事做"。</p>
 *
 * <p><b>打点口径由 {@code group} 决定</b>（见 {@link Group}）：{@code home} 按常驻地归堆
 * （圈内数字 = 常驻人数，看人力储备），{@code onsite} 按班次所在地归堆
 * （圈内数字 = 此刻在该城干活的人数，看现场兵力）。两者不可混用，否则"38 人"会被读成
 * "38 个人此刻在深圳"。</p>
 */
@Path("/map-overview")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER" })
public class MapOverviewResource {

    /** 与 shifts 的 TEXT 时间列保持同一格式，字符串比较才等价于时间比较。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 统计口径。 */
    enum Scope {
        /** 班次覆盖当前时刻。 */
        NOW,
        /** 班次与今天有交集（今天有排班，含还没开始的）。 */
        TODAY,
        /** 班次与「现在起 7 天内」有交集。 */
        NEXT7;

        static Scope parse(String raw) {
            if (raw == null) {
                return NOW;
            }
            return switch (raw.trim().toLowerCase()) {
                case "today" -> TODAY;
                case "next7" -> NEXT7;
                // 未知取值回落到默认，而不是 400：地图是只读总览，参数写错不该让页面整体报错。
                default -> NOW;
            };
        }

        String wireName() {
            return name().toLowerCase();
        }
    }

    /**
     * @brief 打点分组口径。
     *
     * @details 同一个城市圈里的数字，在两种口径下含义不同，必须让用户能选：
     *          <ul>
     *            <li>{@link #HOME}：人按<b>常驻地</b>归堆——圈内数字 = 该城常驻人数，能看出人力储备在哪；</li>
     *            <li>{@link #ONSITE}：人按<b>当前班次所在地</b>归堆——圈内数字 = 此刻在该城干活的人数，
     *                能看出"现场兵力"在哪，代价是出差的人不会出现在他的常驻城市上。</li>
     *          </ul>
     *          默认 HOME，与改造前行为一致。
     */
    enum Group {
        HOME,
        ONSITE;

        static Group parse(String raw) {
            if (raw == null) {
                return HOME;
            }
            return switch (raw.trim().toLowerCase()) {
                case "onsite" -> ONSITE;
                // 与 Scope 一致：未知取值回落默认，地图是只读总览，参数写错不该整页报错。
                default -> HOME;
            };
        }

        String wireName() {
            return name().toLowerCase();
        }
    }

    @GET
    public Overview overview(@QueryParam("structureId") int structureId,
            @QueryParam("scope") String scopeParam,
            @QueryParam("group") String groupParam) {
        LocalDateTime now = LocalDateTime.now();
        String nowText = now.format(STAMP);
        Scope scope = Scope.parse(scopeParam);
        Group group = Group.parse(groupParam);
        String[] window = windowOf(scope, now);

        List<LocationEntity> cities = LocationEntity.<LocationEntity>find(
                "structureId = ?1 and active = true and latitude is not null and longitude is not null"
                        + " order by name",
                structureId).list();
        Map<Integer, LocationEntity> cityById = new LinkedHashMap<>();
        for (LocationEntity city : cities) {
            cityById.put(city.id, city);
        }

        Map<Integer, ShiftEntity> shiftsByEmployee = shiftsInWindow(window[0], window[1], nowText);
        List<EmployeeEntity> employees = EmployeeEntity.<EmployeeEntity>find(
                "structureId = ?1 and active = true order by id", structureId).list();
        // 任务地点可能落在"没有坐标/已停用"的地点上（历史数据），所以名称表要取全部地点，
        // 不能复用上面筛过经纬度的 cities，否则跨城提示会因为查不到名字而整行消失。
        Map<Integer, String> cityNames = new HashMap<>();
        for (LocationEntity location : LocationEntity.<LocationEntity>list("structureId = ?1", structureId)) {
            cityNames.put(location.id, location.name);
        }

        Map<Integer, List<Person>> peopleByCity = new LinkedHashMap<>();
        List<Person> unassigned = new ArrayList<>();
        int totalInScope = 0;
        for (EmployeeEntity employee : employees) {
            ShiftEntity shift = shiftsByEmployee.get(employee.id);
            Person person = toPerson(employee, shift, cityNames);
            if (shift != null) {
                totalInScope++;
            }
            if (group == Group.ONSITE) {
                // 现场视图只关心"此刻人在哪"：没在岗的人不参与打点，否则圈里会混进没来的常驻人。
                if (shift == null) {
                    continue;
                }
                if (cityById.containsKey(shift.locationId)) {
                    peopleByCity.computeIfAbsent(shift.locationId, ignored -> new ArrayList<>()).add(person);
                } else {
                    // 任务地点没坐标（停用/手填）→ 单独成桶，别让人凭空消失、总数对不上。
                    unassigned.add(person);
                }
                continue;
            }
            if (employee.currentLocationId != null && cityById.containsKey(employee.currentLocationId)) {
                peopleByCity.computeIfAbsent(employee.currentLocationId, ignored -> new ArrayList<>())
                        .add(person);
            } else {
                // 没有归属城市（含 current_location_id 指向已停用/无坐标地点）——单独成桶，
                // 否则总数会对不上，用户会以为数据丢了。
                unassigned.add(person);
            }
        }

        Comparator<Person> inScopeFirst = Comparator.comparing(Person::inScope).reversed()
                .thenComparing(Person::name, Comparator.nullsLast(String::compareTo));
        // 现场视图只回有人的城市：30 个空圈会让地图看起来像"满城都是人"。
        List<LocationEntity> rows = group == Group.ONSITE
                ? cities.stream().filter(city -> peopleByCity.containsKey(city.id)).toList()
                : cities;
        List<City> cityRows = new ArrayList<>(rows.size());
        for (LocationEntity city : rows) {
            List<Person> people = peopleByCity.getOrDefault(city.id, List.of());
            List<Person> sorted = new ArrayList<>(people);
            sorted.sort(inScopeFirst);
            int inScope = 0;
            for (Person person : sorted) {
                if (person.inScope()) {
                    inScope++;
                }
            }
            // HOME：sorted.size() 是常驻人数；ONSITE：只装了在岗的人，所以它等于现场人数。
            cityRows.add(new City(city.id, city.name, city.latitude, city.longitude,
                    sorted.size(), inScope, sorted));
        }
        // 符合口径的人多的城市排在前面（前端按此顺序画，也便于侧栏列表默认有序）。
        cityRows.sort(Comparator.comparingInt(City::inScope).reversed()
                .thenComparing(Comparator.comparingInt(City::auditors).reversed())
                .thenComparing(City::name, Comparator.nullsLast(String::compareTo)));
        unassigned.sort(inScopeFirst);

        return new Overview(nowText, scope.wireName(), group.wireName(), employees.size(), totalInScope,
                cityRows.size(), cityRows, unassigned);
    }

    /**
     * @brief 把口径展开成时间窗 {@code [from, to]}（含端点，字符串可比较）。
     *
     * @details {@code now} 退化为一个点：{@code from == to == now}，等价于"覆盖当前时刻"。
     */
    private static String[] windowOf(Scope scope, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return switch (scope) {
            case NOW -> new String[] { now.format(STAMP), now.format(STAMP) };
            case TODAY -> new String[] {
                    today.atStartOfDay().format(STAMP),
                    today.atTime(23, 59, 59).format(STAMP) };
            case NEXT7 -> new String[] {
                    now.format(STAMP),
                    now.plusDays(7).format(STAMP) };
        };
    }

    /**
     * @brief 与时间窗 {@code [from, to]} 有交集的班次，按 employeeId 取"最具代表性"的一条。
     *
     * @details 用 {@code start_time <= to and end_time >= from} 判断区间相交：
     * 列是 {@code yyyy-MM-dd HH:mm:ss} 定宽文本，字典序即时间序。
     *
     * <p>同一人可能有多条命中（例如今天上下午各一班）。展示取哪一条：
     * 优先"此刻正在进行"的那条，否则取最早的一条——这样侧栏显示的
     * 班次时间不会因为排序偶然性而跳来跳去。</p>
     */
    private static Map<Integer, ShiftEntity> shiftsInWindow(String from, String to, String nowText) {
        Map<Integer, ShiftEntity> byEmployee = new HashMap<>();
        List<ShiftEntity> hits = ShiftEntity.<ShiftEntity>find(
                "employeeId is not null and startTime <= ?1 and endTime >= ?2 order by startTime",
                to, from).list();
        for (ShiftEntity shift : hits) {
            ShiftEntity current = byEmployee.get(shift.employeeId);
            if (current == null) {
                byEmployee.put(shift.employeeId, shift);
                continue;
            }
            // 已有一条排在前面（开始更早）。若新来的这条正覆盖此刻而旧的不是，则替换。
            if (!covers(shift, nowText) || covers(current, nowText)) {
                continue;
            }
            byEmployee.put(shift.employeeId, shift);
        }
        return byEmployee;
    }

    private static boolean covers(ShiftEntity shift, String moment) {
        return shift.startTime != null && shift.endTime != null
                && shift.startTime.compareTo(moment) <= 0
                && shift.endTime.compareTo(moment) >= 0;
    }

    private static Person toPerson(EmployeeEntity employee, ShiftEntity shift, Map<Integer, String> cityNames) {
        String name = ((employee.firstName == null ? "" : employee.firstName) + " "
                + (employee.lastName == null ? "" : employee.lastName)).trim();
        // 任务地点取班次的 location_id：和打点用的常驻城市可能不是同一个（跨城出差）。
        String taskCity = shift == null || shift.locationId <= 0 ? null : cityNames.get(shift.locationId);
        String homeCity = employee.currentLocationId == null ? null : cityNames.get(employee.currentLocationId);
        return new Person(employee.id, employee.code, name.isEmpty() ? employee.code : name,
                employee.email, shift != null,
                shift == null ? null : shift.startTime,
                shift == null ? null : shift.endTime,
                shift == null ? null : shift.auditOrderCode,
                shift == null ? null : shift.auditStage,
                taskCity, homeCity);
    }
}
