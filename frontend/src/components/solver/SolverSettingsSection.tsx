import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Card, Col, Form, Modal, OverlayTrigger, Row, Spinner, Table, Tooltip } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk, faPen, faCircleQuestion, faMicrochip, faCalendarWeek, faSliders, faMoon, faToggleOn } from '@fortawesome/free-solid-svg-icons'
import type { IconDefinition } from '@fortawesome/fontawesome-svg-core'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { structuresApi, type Structure } from '../../api/structures'
import { solverSettingsApi, type SolverSettings } from '../../api/solverSettings'
import './SolverSettingsSection.css'

type RowData = { structure: Structure; settings: SolverSettings }

/** Detailed explanations (English fallback) shown in the "?" tooltip beside each setting.
 *  Translations in all six languages are seeded as `solver.help.<field>` keys. */
const HELP_EN: Record<string, string> = {
  max_solve_seconds: "Maximum solving time (5–600 s): after the limit the best solution found is returned.",
  unimproved_seconds: "Stops solving if there is no improvement for this many seconds; 0 disables early stopping.",
  diminished_window_seconds: "Observation window (seconds) for diminishing-returns stop: compares the current improvement rate with that of N seconds earlier; 0 disables it.",
  diminished_ratio_pct: "The solver stops when the improvement rate falls below this percentage of the initial rate (1–100); active only when the window is > 0.",
  context_days: "Days adjacent to the window whose already-assigned shifts are loaded as locked context (overlap, rest, weekly hours and consecutive-day constraints see them but do not change them); 0 = window only.",
  minimum_rest_hours: "Minimum rest hours between the end of one shift and the start of the next for the same operator (hard constraint).",
  max_shifts_per_day: "Maximum number of shifts for the same operator on the same day (1–5).",
  max_weekly_hours: "Weekly hours cap per operator; 0 disables the limit.",
  min_weekly_shifts: "Minimum weekly shifts per operator; 0 disables the minimum.",
  max_weekly_shifts: "Maximum weekly shifts per operator; 0 disables the maximum.",
  max_consecutive_days: "Maximum consecutive working days per operator; 0 disables the limit.",
  min_days_off_per_week: "Minimum days off per operator in a week (0–7).",
  desired_date_weight: "How much to reward shifts on the operator's preferred dates; higher = the solver satisfies them more (0–10).",
  undesired_date_weight: "How much to penalize shifts on the operator's unwanted dates; higher = the solver avoids them more (0–10).",
  balance_weight: "How much fair workload distribution among operators matters (by hours or by shift count, see the dedicated option); higher = more even workloads (0–10).",
  optional_skill_weight: "How much to reward operators who also have the shift's optional skills, beyond the mandatory ones (0–10).",
  same_location_weight: "How much to reward location continuity: the same operators at the same location on nearby days (0–10).",
  night_balance_weight: "How much fair distribution of night shifts among operators matters (night range set by the night start/end hours) (0–10).",
  unassigned_weight: "Penalty for each shift left uncovered; higher = the solver covers more shifts even at the expense of soft weights (1–100).",
  weekly_shift_weight: "How much respecting the configured weekly min/max shifts matters; 0 disables the penalty (0–10).",
  days_off_weight: "How much respecting the configured minimum weekly days off matters; 0 disables the penalty (0–10).",
  consecutive_days_weight: "How much respecting the configured maximum consecutive working days matters; 0 disables the penalty (0–10).",
  night_start_hour: "Start hour of the night range (0–23), used to identify and balance night shifts.",
  night_end_hour: "End hour of the night range (0–23), used to identify and balance night shifts.",
  balance_by_hours: "When on, workload balancing considers total hours worked; otherwise it considers the number of shifts.",
  allow_unassigned: "When on, the solver may leave shifts uncovered (with a penalty) instead of always forcing coverage.",
  stop_when_feasible: "When on, the solver stops as soon as all hard constraints are satisfied, without further optimizing the soft weights (faster).",
}

/** "?" icon with a detailed localized tooltip beside the setting name. */
function HelpIcon({ helpKey }: { helpKey: string }) {
  const { t } = useTranslation()
  const text = t('solver.help.' + helpKey, HELP_EN[helpKey] ?? '')
  if (!text) return null
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip id={`help-${helpKey}`} className="solver-help-tooltip">{text}</Tooltip>}>
      <span className="ms-1 text-primary" style={{ cursor: 'help' }} role="button" tabIndex={0} aria-label={text}>
        <FontAwesomeIcon icon={faCircleQuestion} />
      </span>
    </OverlayTrigger>
  )
}
type NumberField = keyof Pick<SolverSettings,'max_solve_seconds'|'unimproved_seconds'|'diminished_window_seconds'|'diminished_ratio_pct'|'context_days'|'minimum_rest_hours'|'max_shifts_per_day'|'desired_date_weight'|'undesired_date_weight'|'balance_weight'|'optional_skill_weight'|'max_weekly_hours'|'min_weekly_shifts'|'max_weekly_shifts'|'max_consecutive_days'|'min_days_off_per_week'|'unassigned_weight'|'same_location_weight'|'night_balance_weight'|'night_start_hour'|'night_end_hour'|'weekly_shift_weight'|'days_off_weight'|'consecutive_days_weight'>

const groups: { title:string; icon:IconDefinition; accent:string; fields:[NumberField,string,string,number,number][] }[] = [
  {title:'Processing',icon:faMicrochip,accent:'#6f42c1',fields:[
    ['max_solve_seconds','Maximum duration (seconds)','5–600 seconds.',5,600],
    ['unimproved_seconds','Stop without improvement','0 disables early stopping.',0,600],
    ['diminished_window_seconds','Diminishing-returns stop: window (s)','0 disables it. Compares the current improvement with that of N seconds earlier and stops when the rate falls below the threshold.',0,600],
    ['diminished_ratio_pct','Diminishing-returns stop: threshold (%)','1–100: percentage of the initial improvement rate below which to stop. Used only with window > 0.',1,100],
    ['context_days','Context days (window edges)','0–7: shifts already assigned on days adjacent to the window, seen (locked) by the overlap, rest, weekly-hours and consecutive-day constraints. 0 = window only.',0,7],
  ]},{title:'Daily and weekly rules',icon:faCalendarWeek,accent:'#0d6efd',fields:[
    ['minimum_rest_hours','Minimum rest (hours)','Minimum hours between two shifts.',0,24],
    ['max_shifts_per_day','Maximum daily shifts','From 1 to 5.',1,5],
    ['max_weekly_hours','Maximum weekly hours','0 disables the limit.',0,168],
    ['min_weekly_shifts','Minimum weekly shifts','0 disables the minimum.',0,21],
    ['max_weekly_shifts','Maximum weekly shifts','0 disables the maximum.',0,21],
    ['max_consecutive_days','Maximum consecutive days','0 disables the limit.',0,31],
    ['min_days_off_per_week','Minimum days off per week','From 0 to 7.',0,7],
  ]},{title:'Optimization weights',icon:faSliders,accent:'#fd7e14',fields:[
    ['desired_date_weight','Desired dates','0–10.',0,10],
    ['undesired_date_weight','Undesired dates','0–10.',0,10],
    ['balance_weight','Workload balance','0–10.',0,10],
    ['optional_skill_weight','Optional skills','0–10.',0,10],
    ['same_location_weight','Location continuity','0–10.',0,10],
    ['night_balance_weight','Night shift balance','0–10.',0,10],
    ['unassigned_weight','Unassigned shift penalty','1–100.',1,100],
    ['weekly_shift_weight','Weekly shifts (min/max)','0–10. Weight for respecting the weekly min/max shifts.',0,10],
    ['days_off_weight','Minimum weekly days off','0–10. Weight for respecting the minimum weekly days off.',0,10],
    ['consecutive_days_weight','Maximum consecutive days','0–10. Weight for respecting the maximum consecutive days.',0,10],
  ]},{title:'Night range',icon:faMoon,accent:'#6610f2',fields:[
    ['night_start_hour','Night start hour','0–23.',0,23],['night_end_hour','Night end hour','0–23.',0,23],
  ]},
]

/** [min,max] bounds for every numeric field, derived from groups: single source for clamping and validation. */
const FIELD_BOUNDS = Object.fromEntries(
  groups.flatMap(g => g.fields.map(([k, , , mn, mx]) => [k, [mn, mx]] as const))
) as Record<NumberField, [number, number]>

/** Effective maximum for a field given the current form: `unimproved_seconds` is dynamically
 *  limited to `max_solve_seconds` (as required by the backend); other fields use the static maximum. */
function effectiveMax(key: NumberField, form: SolverSettings): number {
  const max = FIELD_BOUNDS[key][1]
  if (key === 'unimproved_seconds') return Math.min(max, form.max_solve_seconds)
  return max
}

/** Returns the i18n key for the first cross-field error, or `null` when the form is valid.
 *  Mirrors the reciprocal rules in SolverSettingsResource.java that would otherwise yield an opaque 400. */
function crossFieldError(form: SolverSettings): string | null {
  if (form.unimproved_seconds > form.max_solve_seconds) return 'solver.err.unimproved'
  if (form.max_weekly_shifts > 0 && form.min_weekly_shifts > form.max_weekly_shifts) return 'solver.err.weekly'
  return null
}

type FocusField = NumberField | 'balance_by_hours' | 'allow_unassigned' | 'stop_when_feasible'

/** Timefold constraint name (asConstraint) → Solver Settings field that controls it.
 *  Fixed hard constraints without a setting (e.g. Overlapping shift) are omitted: only the modal opens. */
const CONSTRAINT_FIELD: Record<string, FocusField> = {
  'At least 10 hours between 2 shifts': 'minimum_rest_hours',
  'Max one shift per day': 'max_shifts_per_day',
  'Maximum weekly hours': 'max_weekly_hours',
  'Weekly shift range': 'min_weekly_shifts',
  'Minimum weekly shifts (empty week)': 'min_weekly_shifts',
  'Maximum consecutive days': 'max_consecutive_days',
  'Minimum days off per week': 'min_days_off_per_week',
  'Desired day for employee': 'desired_date_weight',
  'Undesired day for employee': 'undesired_date_weight',
  'Optional skill match': 'optional_skill_weight',
  'Same location continuity': 'same_location_weight',
  'Balance employee shift assignments': 'balance_weight',
  'Balance employee hours': 'balance_weight',
  'Balance night shifts': 'night_balance_weight',
  'Unassigned shift penalty': 'unassigned_weight',
  'Unassigned shift forbidden': 'allow_unassigned',
}

/** Group title (English fallback) → i18n key suffix `solver.group.<key>`. */
const GROUP_KEY: Record<string, string> = {
  'Processing': 'processing',
  'Daily and weekly rules': 'dailyWeekly',
  'Optimization weights': 'weights',
  'Night range': 'night',
}

/** Highlight style for the field reached by clicking a constraint. */
const FOCUS_STYLE: React.CSSProperties = { outline: '3px solid #dc3545', outlineOffset: 4, borderRadius: 4 }

function SettingsModal({row,focusField,onClose,onSaved}:{row:RowData|null;focusField?:FocusField|null;onClose:()=>void;onSaved:()=>void}){
  const {t}=useTranslation()
  const [form,setForm]=useState<SolverSettings|null>(null);const [saving,setSaving]=useState(false)
  const focusRef=useRef<HTMLDivElement|null>(null)
  useEffect(()=>setForm(row?{...row.settings}:null),[row])
  // Scroll to the clicked constraint's field as soon as the modal is rendered.
  useEffect(()=>{
    if(!row||!focusField)return
    const timer=setTimeout(()=>focusRef.current?.scrollIntoView({behavior:'smooth',block:'center'}),250)
    return()=>clearTimeout(timer)
  },[row,focusField])
  if(!row||!form)return null
  const number=(key:NumberField)=>(e:React.ChangeEvent<HTMLInputElement>)=>setForm(f=>f&&({...f,[key]:Number(e.target.value)}))
  // On blur, clamp the value to [min, effectiveMax], preventing out-of-range values from reaching the backend.
  const clamp=(key:NumberField)=>()=>setForm(f=>{if(!f)return f;const mn=FIELD_BOUNDS[key][0];const mx=effectiveMax(key,f);const v=Number(f[key]);return{...f,[key]:isNaN(v)?mn:Math.min(Math.max(v,mn),mx)}})
  const bool=(key:'balance_by_hours'|'allow_unassigned'|'stop_when_feasible')=>(e:React.ChangeEvent<HTMLInputElement>)=>setForm(f=>f&&({...f,[key]:e.target.checked}))
  const err=crossFieldError(form)
  async function save(){if(crossFieldError(form!))return;setSaving(true);try{await solverSettingsApi.save(row!.structure.id,form!);toast.success(t('toast.solverSettingsSaved','Solver settings saved.'));onSaved();onClose()}catch{toast.error(t('toast.errorSave','Error while saving.'))}finally{setSaving(false)}}
  return <Modal show onHide={onClose} size="xl" centered scrollable><Modal.Header closeButton><Modal.Title>{t('config.menu.solverSettings','Solver Settings')} — {row.structure.name}</Modal.Title></Modal.Header><Modal.Body>
    {groups.map(group=><Card className="mb-3 shadow-sm" key={group.title}><Card.Header className="bg-light d-flex align-items-center gap-2 fw-semibold"><FontAwesomeIcon icon={group.icon} style={{color:group.accent}}/>{t('solver.group.'+(GROUP_KEY[group.title]??''), group.title)}</Card.Header><Card.Body><Row className="g-3">{group.fields.map(([key,label,hint,min])=><Col md={6} lg={4} key={key}><Form.Group ref={key===focusField?focusRef:undefined} style={key===focusField?FOCUS_STYLE:undefined}><Form.Label className="fw-semibold">{t('solver.label.'+key, label)}<HelpIcon helpKey={key}/></Form.Label><Form.Control type="number" min={min} max={effectiveMax(key,form)} value={form[key] as number} onChange={number(key)} onBlur={clamp(key)} isInvalid={(key==='unimproved_seconds'&&err==='solver.err.unimproved')||((key==='min_weekly_shifts'||key==='max_weekly_shifts')&&err==='solver.err.weekly')}/><Form.Text>{t('solver.hint.'+key, hint)}</Form.Text></Form.Group></Col>)}</Row></Card.Body></Card>)}
    <Card className="mb-2 shadow-sm"><Card.Header className="bg-light d-flex align-items-center gap-2 fw-semibold"><FontAwesomeIcon icon={faToggleOn} style={{color:'#198754'}}/>{t('solver.group.options','Options')}</Card.Header><Card.Body><Row className="g-3"><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='balance_by_hours'?focusRef:undefined} style={focusField==='balance_by_hours'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.balance_by_hours','Balance workload by hours (instead of by number of shifts)')} checked={form.balance_by_hours} onChange={bool('balance_by_hours')}/><HelpIcon helpKey="balance_by_hours"/></div></Col><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='allow_unassigned'?focusRef:undefined} style={focusField==='allow_unassigned'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.allow_unassigned','Allow unassigned shifts')} checked={form.allow_unassigned} onChange={bool('allow_unassigned')}/><HelpIcon helpKey="allow_unassigned"/></div></Col><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='stop_when_feasible'?focusRef:undefined} style={focusField==='stop_when_feasible'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.stop_when_feasible','Stop at the first feasible solution')} checked={form.stop_when_feasible} onChange={bool('stop_when_feasible')}/><HelpIcon helpKey="stop_when_feasible"/></div></Col></Row></Card.Body></Card>
  </Modal.Body><Modal.Footer>{err&&<span className="text-danger small me-auto">{err==='solver.err.unimproved'?t('solver.err.unimproved','The unimproved stop cannot exceed the maximum duration.'):t('solver.err.weekly','Minimum weekly shifts cannot exceed the maximum.')}</span>}<Button variant="secondary" onClick={onClose}>{t('btn.cancel','Cancel')}</Button><Button onClick={save} disabled={saving||!!err}>{saving?<Spinner size="sm"/>:<><FontAwesomeIcon icon={faFloppyDisk} className="me-1"/>{t('btn.save','Save')}</>}</Button></Modal.Footer></Modal>
}

export default function SolverSettingsSection(){
  const {t}=useTranslation();const [rows,setRows]=useState<RowData[]>([]);const [loading,setLoading]=useState(true);const [editing,setEditing]=useState<RowData|null>(null)
  const [focusField,setFocusField]=useState<FocusField|null>(null)
  const [searchParams,setSearchParams]=useSearchParams()
  const load=useCallback(async()=>{setLoading(true);try{const structures=await structuresApi.list();const settings=await Promise.all(structures.map(s=>solverSettingsApi.get(s.id)));setRows(structures.map((s,i)=>({structure:s,settings:settings[i]})))}catch{toast.error(t('toast.errorLoad','Error while loading.'))}finally{setLoading(false)}},[t])
  useEffect(()=>{load()},[load])
  // Deep link from Solve Result: ?structureId= opens the company modal, ?constraint= highlights the field.
  useEffect(()=>{
    if(loading||rows.length===0)return
    const sid=Number(searchParams.get('structureId'));if(!sid)return
    const row=rows.find(r=>r.structure.id===sid);if(!row)return
    const constraint=searchParams.get('constraint')
    setFocusField(constraint?CONSTRAINT_FIELD[constraint]??null:null)
    setEditing(row)
    // Clean the URL so closing/reopening the section does not reopen the modal.
    setSearchParams(prev=>{const q=new URLSearchParams(prev);q.delete('structureId');q.delete('constraint');return q},{replace:true})
  },[loading,rows,searchParams,setSearchParams])
  if(loading)return <div className="text-center py-5"><Spinner/></div>
  return <div><h5 className="mb-3">{t('config.menu.solverSettings','Solver Settings')}</h5><Table bordered hover responsive size="sm" className="align-middle" style={{maxWidth:1100}}><thead className="table-dark"><tr><th style={{width:55}}>ID</th><th>{t('col.company','Company')}</th><th>{t('solver.col.duration','Duration')}</th><th>{t('solver.col.minRest','Minimum rest')}</th><th>{t('solver.col.maxPerDay','Max shifts/day')}</th><th>{t('solver.col.balance','Balance')}</th><th style={{width:90}}>{t('col.actions','Actions')}</th></tr></thead><tbody>{rows.map(row=><tr key={row.structure.id}><td className="text-muted">{row.structure.id}</td><td className="fw-semibold">{row.structure.name}</td><td>{row.settings.max_solve_seconds}s</td><td>{row.settings.minimum_rest_hours}h</td><td>{row.settings.max_shifts_per_day}</td><td>{row.settings.balance_by_hours?t('solver.balance.hours','Hours'):t('solver.balance.shifts','Shifts')}</td><td><Button variant="link" size="sm" className="p-0" title={t('btn.edit','Edit')} onClick={()=>{setFocusField(null);setEditing(row)}}><FontAwesomeIcon icon={faPen}/></Button></td></tr>)}</tbody></Table><SettingsModal row={editing} focusField={focusField} onClose={()=>{setEditing(null);setFocusField(null)}} onSaved={load}/></div>
}
