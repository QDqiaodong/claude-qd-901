<template>
  <div class="hs-page">
    <div class="hs-head">
      <div>
        <h2>管网隔离方案 · 故障关断闭环</h2>
        <div class="hs-sub">
          选定故障管段后按「管段—阀门井」连通拓扑试算关断序列、可保供路径与受影响换热站；人工调整后必须重新证明故障段已与运行管网隔开。
        </div>
      </div>
      <div class="hs-head-ops">
        <el-button size="small" plain @click="reload">刷新</el-button>
        <el-button size="small" type="primary" @click="openTrial">新建隔离试算</el-button>
      </div>
    </div>

    <div class="iso-body">
      <!-- 左：方案列表 -->
      <aside class="iso-list">
        <div class="iso-list-title">方案台账</div>
        <div v-for="p in plans" :key="p.id" class="iso-card"
             :class="{ active: current && current.id === p.id }" @click="pick(p.id)">
          <div class="iso-card-top">
            <span class="iso-no">{{ p.planNo }}</span>
            <el-tag size="small" :type="statusType(p.planStatus)" effect="dark">{{ p.planStatus }}</el-tag>
          </div>
          <div class="iso-card-fault">
            <el-tag size="small" :type="p.planType === 'RESTORE' ? 'warning' : 'danger'" effect="plain">
              {{ p.planType === 'RESTORE' ? '复供' : '隔离' }}
            </el-tag>
            <b>{{ p.faultSegCode }}</b>
            <span class="iso-desc">{{ p.faultSegmentDesc }}</span>
          </div>
          <div class="iso-card-meta">
            <span class="proof-dot" :class="p.proofPassed ? 'ok' : 'bad'">
              {{ p.proofPassed ? '✓ 证明通过' : '✕ 证明未通过' }}
            </span>
          </div>
          <div v-if="p.liveConsistent === false" class="iso-card-warn">实况与方案不一致</div>
        </div>
        <el-empty v-if="!plans.length" description="暂无方案，请新建隔离试算" :image-size="70" />
      </aside>

      <!-- 右：方案详情 -->
      <section class="iso-detail" v-if="current">
        <div class="iso-detail-head">
          <div>
            <div class="iso-title-row">
              <h3>{{ current.planNo }}</h3>
              <el-tag :type="statusType(current.planStatus)" effect="dark">{{ current.planStatus }}</el-tag>
              <el-tag v-if="current.strategy === 'EXPAND'" type="warning" effect="plain">扩大停暖确保安全</el-tag>
              <el-tag v-else-if="current.strategy === 'WAIT_ALT'" type="info" effect="plain">拒绝启用·等待替代路径</el-tag>
            </div>
            <div class="iso-fault-line">
              故障管段 <b>{{ current.faultSegCode }}</b>（{{ current.faultSegmentDesc }}）
              <span v-if="current.faultReason">｜原因：{{ current.faultReason }}</span>
            </div>
          </div>
          <div class="iso-head-actions">
            <el-button v-if="canReTrial" size="small" @click="openAdjust">人工调整 / 重新试算</el-button>
            <el-button v-if="current.planStatus === '草稿' && current.proofPassed"
                       size="small" type="primary" @click="doEnable">启用并下发现场</el-button>
            <el-button v-if="current.planStatus === '草稿' && !current.proofPassed"
                       size="small" type="warning" @click="openAdjust">调整以通过证明</el-button>
            <el-button v-if="current.planStatus === '已隔离' && current.planType === 'ISOLATE'"
                       size="small" type="success" @click="doRestore">故障修复·发起复供</el-button>
          </div>
        </div>

        <el-alert :title="current.proofDetail" :type="current.proofPassed ? 'success' : 'error'"
                  :closable="false" show-icon class="iso-alert" />
        <el-alert v-if="current.liveConsistent !== null"
                  :title="current.liveConsistentNote"
                  :type="current.liveConsistent ? 'success' : 'warning'"
                  :closable="false" show-icon class="iso-alert" />

        <el-tabs v-model="tab" class="iso-tabs">
          <!-- ============ 试算结论 ============ -->
          <el-tab-pane label="① 拓扑试算结论" name="trial">
            <div class="trial-grid">
              <div class="trial-col">
                <div class="panel-h">
                  阀门取舍（计划关闭 / 原本关闭 / 必须开启）
                  <el-button size="small" text type="primary" @click="openAdjust">人工调整</el-button>
                </div>
                <el-table :data="current.valves" border size="small" max-height="280">
                  <el-table-column prop="wellNo" label="井号" width="80" />
                  <el-table-column prop="nodeNo" label="节点" width="80" />
                  <el-table-column label="所属" width="80">
                    <template #default="{ row }">PS-{{ String(row.segmentId).padStart(2, '0') }}</template>
                  </el-table-column>
                  <el-table-column label="分类" width="104">
                    <template #default="{ row }">
                      <el-tag size="small" :type="catType(row.category)" effect="light">
                        {{ catLabel(row.category) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="原态→目标" width="110">
                    <template #default="{ row }">
                      <span :class="stateClass(row.baselineState)">{{ row.baselineState }}</span>
                      <span class="arrow">→</span>
                      <b :class="stateClass(row.targetState)">{{ row.targetState }}</b>
                    </template>
                  </el-table-column>
                  <el-table-column label="现场" width="78">
                    <template #default="{ row }">
                      <el-tooltip v-if="row.operable === false" content="现场不可操作（阀杆抱死 / 检修）" placement="top">
                        <el-tag size="small" type="danger">不可操作</el-tag>
                      </el-tooltip>
                      <el-tooltip v-if="row.egressExit" content="安全撤离通道口" placement="top">
                        <el-tag size="small" type="warning">撤离口</el-tag>
                      </el-tooltip>
                      <span v-if="row.operable !== false && !row.egressExit" class="muted">—</span>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="note-line" v-if="current.egressNote">🛟 {{ current.egressNote }}</div>
              </div>

              <div class="trial-col">
                <div class="panel-h">可保供路径（环网多路径，仍有路径不停暖）</div>
                <el-table :data="current.supplyPaths" border size="small" max-height="170">
                  <el-table-column prop="stationCode" label="换热站" width="86" />
                  <el-table-column label="路径（源→站）" min-width="220">
                    <template #default="{ row }">
                      <div class="path-line">
                        <el-tag size="small" type="primary" effect="plain">{{ row.sourceName }}</el-tag>
                        <template v-for="(n, i) in row.nodes" :key="i">
                          <span class="path-arrow">→</span><span class="path-node">{{ n }}</span>
                        </template>
                      </div>
                      <div v-if="row.alternative" class="alt-line">
                        绕行替代路径，须先开启：<b>{{ (row.mustOpenValves || []).join('、') }}</b>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>

                <div class="panel-h mt">两种策略的后果</div>
                <div class="consequence">
                  <div class="cons-item">
                    <el-tag size="small" type="warning">扩大停暖确保安全</el-tag>
                    <span>{{ current.expandConsequence || current.failureNote }}</span>
                  </div>
                  <div class="cons-item">
                    <el-tag size="small" type="info">拒绝启用等待替代路径</el-tag>
                    <span>{{ current.waitAltConsequence }}</span>
                  </div>
                  <div v-if="current.inoperableValves && current.inoperableValves.length" class="cons-item">
                    <el-tag size="small" type="danger">现场不可操作阀</el-tag>
                    <span>{{ current.inoperableValves.join('、') }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="panel-h mt">受影响换热站与居民供热（按当前阀门状态可达性实时判定）</div>
            <el-table :data="current.affectedStations" border size="small">
              <el-table-column prop="stationCode" label="编号" width="86" />
              <el-table-column prop="stationName" label="换热站" min-width="150" />
              <el-table-column prop="feedWellNo" label="接入井" width="86" />
              <el-table-column label="影响判定" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="impactType(row.impactType)" effect="dark">
                    {{ row.impactType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="householdCount" label="正常供热户数" width="110" align="right" />
              <el-table-column label="供热面积 ㎡" width="120" align="right">
                <template #default="{ row }">{{ fmt(row.totalAreaM2) }}</template>
              </el-table-column>
              <el-table-column prop="note" label="说明" min-width="200" />
            </el-table>
            <div v-if="current.restoreProofDetail" class="note-line restore-note">
              ♻️ {{ current.restoreProofDetail }}
            </div>
          </el-tab-pane>

          <!-- ============ 现场执行 ============ -->
          <el-tab-pane label="② 现场关断序列" name="steps">
            <el-timeline>
              <el-timeline-item v-for="s in current.steps" :key="s.id"
                                :type="stepDot(s.stepStatus)" :timestamp="`第 ${s.stepNo} 步`" placement="top">
                <el-card shadow="never" class="step-card" :class="stepCardClass(s.stepStatus)">
                  <div class="step-main">
                    <div class="step-title">
                      <el-tag size="small" :type="actionType(s.actionType)" effect="plain">
                        {{ actionLabel(s.actionType) }}
                      </el-tag>
                      <b v-if="s.wellNo" class="step-well">{{ s.wellNo }}</b>
                      <el-tag v-if="s.valveCategory" size="small" :type="catType(s.valveCategory)">
                        {{ catLabel(s.valveCategory) }}
                      </el-tag>
                      <el-tag size="small" :type="statusDot(s.stepStatus)">{{ s.stepStatus }}</el-tag>
                      <el-tag v-if="s.egressRequired" size="small" type="warning">撤离前提</el-tag>
                    </div>
                    <div class="step-meta">
                      <span v-if="s.plannedState">计划到位：<b>{{ s.plannedState }}</b></span>
                      <span v-if="s.stateBefore">试算时：{{ s.stateBefore }}</span>
                      <span v-if="s.observedState">现场反馈：<b>{{ s.observedState }}</b></span>
                      <span v-if="s.completedBy">操作人：{{ s.completedBy }}</span>
                    </div>
                    <div v-if="s.remark" class="step-remark">{{ s.remark }}</div>
                    <el-alert v-if="s.deviationReason" :title="s.deviationReason" type="error"
                              :closable="false" show-icon class="step-dev" />
                  </div>
                  <div class="step-ops">
                    <el-button v-if="canConfirm(s)" size="small" type="primary"
                               @click="openConfirm(s)">现场确认</el-button>
                  </div>
                </el-card>
              </el-timeline-item>
            </el-timeline>

            <!-- 失败三收口 -->
            <div v-if="current.planStatus === '失败待处置'" class="resolve-box">
              <div class="resolve-title">中途失败 · 请选择安全收口方式</div>
              <div class="resolve-actions">
                <el-button type="warning" @click="resolve('EXPAND')">扩大隔离（重新试算，扩大停暖保安全）</el-button>
                <el-button type="primary" @click="resolve('ROLLBACK')">按安全顺序恢复已动阀门</el-button>
                <el-button @click="resolve('HOLD')">保持现场状态等待处置</el-button>
              </div>
              <div class="resolve-note">{{ current.failureNote }}</div>
            </div>
          </el-tab-pane>

          <!-- ============ 偏差与记录 ============ -->
          <el-tab-pane label="③ 偏差与完成记录" name="dev">
            <div class="panel-h">偏差记录（反馈不符 / 阀门被他人改变 / 前序未完成）</div>
            <ul v-if="current.deviations && current.deviations.length" class="dev-list">
              <li v-for="(d, i) in current.deviations" :key="i">{{ d }}</li>
            </ul>
            <el-empty v-else description="暂无偏差" :image-size="60" />
            <div class="panel-h mt">失败收口记录</div>
            <div v-if="current.failureOutcome" class="resolve-note">
              收口方式：<b>{{ resolveLabel(current.failureOutcome) }}</b>
              <span v-if="current.failureTime">｜{{ formatTime(current.failureTime) }}</span>
              <div>{{ current.failureNote }}</div>
            </div>
            <div v-else class="muted">无</div>
          </el-tab-pane>
        </el-tabs>
      </section>

      <el-empty v-else class="iso-empty" description="请选择或新建隔离方案" />
    </div>

    <!-- 新建试算弹窗 -->
    <el-dialog v-model="trialDlg" title="选定故障管段 · 拓扑试算" width="640px">
      <el-form label-width="110px" size="small">
        <el-form-item label="故障管段" required>
          <el-select v-model="form.faultSegmentId" filterable placeholder="选择故障管段" style="width: 100%">
            <el-option v-for="s in segments" :key="s.id"
                       :label="`${s.segCode}（${s.startWellNo}→${s.endWellNo} DN${s.diameterDn}）`"
                       :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障原因">
          <el-input v-model="form.faultReason" placeholder="如：架空补偿器泄漏 / 管段爆管" />
        </el-form-item>
        <el-form-item label="处置策略">
          <el-radio-group v-model="form.strategy">
            <el-radio label="EXPAND">扩大停暖确保安全</el-radio>
            <el-radio label="WAIT_ALT">拒绝启用·等待替代路径</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="试算人">
          <el-input v-model="form.createdBy" placeholder="调度员姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trialDlg = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submitTrial">开始拓扑试算</el-button>
      </template>
    </el-dialog>

    <!-- 人工调整弹窗 -->
    <el-dialog v-model="adjustDlg" title="人工调整阀门取舍 · 调整后重新证明" width="680px">
      <el-alert type="info" :closable="false" show-icon class="mb10"
                title="调整后系统将重新试算：故障段必须仍与运行管网隔开、不得切断撤离路径、仍有供热路径的换热站不得误判停暖。" />
      <el-form label-width="120px" size="small">
        <el-form-item label="额外计划关闭">
          <el-select v-model="form.extraCloseValveIds" multiple filterable collapse-tags
                     placeholder="选择强制关闭的阀" style="width: 100%">
            <el-option v-for="w in valveOptions" :key="'c'+w.id" :label="valveLabel(w)" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="必须开启">
          <el-select v-model="form.extraOpenValveIds" multiple filterable collapse-tags
                     placeholder="选择强制开启的环网保供阀" style="width: 100%">
            <el-option v-for="w in valveOptions" :key="'o'+w.id" :label="valveLabel(w)" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="从序列剔除">
          <el-select v-model="form.removedValveIds" multiple filterable collapse-tags
                     placeholder="选择不纳入本方案的阀" style="width: 100%">
            <el-option v-for="w in valveOptions" :key="'r'+w.id" :label="valveLabel(w)" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置策略">
          <el-radio-group v-model="form.strategy">
            <el-radio label="EXPAND">扩大停暖确保安全</el-radio>
            <el-radio label="WAIT_ALT">拒绝启用·等待替代路径</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDlg = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submitReTrial">重新试算并证明</el-button>
      </template>
    </el-dialog>

    <!-- 现场确认弹窗 -->
    <el-dialog v-model="confirmDlg" :title="confirmTitle" width="520px">
      <el-form label-width="96px" size="small">
        <el-form-item label="计划状态">
          <el-tag>{{ confirmContext.step && confirmContext.step.plannedState || '确认事项' }}</el-tag>
        </el-form-item>
        <el-form-item v-if="confirmContext.step && confirmContext.step.valveWellId" label="现场反馈状态">
          <el-radio-group v-model="confirmContext.observedState">
            <el-radio-button label="关闭">关闭</el-radio-button>
            <el-radio-button label="半开">半开</el-radio-button>
            <el-radio-button label="全开">全开</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="confirmContext.operator" placeholder="现场确认人" />
        </el-form-item>
        <el-form-item label="偏差说明">
          <el-input v-model="confirmContext.deviationReason" type="textarea" :rows="2"
                    placeholder="反馈与计划不符 / 阀门被他人改变时填写" />
        </el-form-item>
      </el-form>
      <el-alert v-if="confirmContext.step && confirmContext.step.egressRequired" type="warning"
                :closable="false" show-icon title="此为撤离前提步骤：必须确认井下作业人员全部撤离后方可继续关阀。" />
      <template #footer>
        <el-button @click="confirmDlg = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submitConfirm">提交现场确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isolationApi, pipeApi } from '../api'

const props = defineProps({ seg: { type: [Number, String], default: null } })

const plans = ref([])
const current = ref(null)
const segments = ref([])
const wells = ref([])
const tab = ref('trial')
const loading = ref(false)

const trialDlg = ref(false)
const adjustDlg = ref(false)
const confirmDlg = ref(false)
const form = ref(emptyForm())
const confirmContext = ref({ step: null, observedState: '', operator: '', deviationReason: '' })

function emptyForm() {
  return {
    faultSegmentId: null,
    faultReason: '',
    strategy: 'EXPAND',
    createdBy: '',
    extraCloseValveIds: [],
    extraOpenValveIds: [],
    removedValveIds: []
  }
}

const valveOptions = computed(() =>
  wells.value.map((w) => ({
    id: w.id,
    label: `${w.wellNo}（节点 ${w.nodeNo || w.wellNo}｜${w.openState}${w.operable === false ? '｜不可操作' : ''}）`
  })))

const canReTrial = computed(() =>
  current.value && ['草稿', '失败待处置'].includes(current.value.planStatus))

const confirmTitle = computed(() => {
  const s = confirmContext.value.step
  return s ? `第 ${s.stepNo} 步 · ${s.actionLabel}` : '现场确认'
})

// ---------------- 数据 ----------------
async function loadPlans(selectId) {
  plans.value = await isolationApi.list()
  if (selectId) {
    await pick(selectId)
  } else if (current.value) {
    await pick(current.value.id)
  }
}

async function pick(id) {
  try {
    current.value = await isolationApi.detail(id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reload() {
  await Promise.all([loadPlans(), loadBaseData()])
}

async function loadBaseData() {
  try {
    const [segs, ws] = await Promise.all([
      pipeApi.segments(null, 'with-valves'),
      pipeApi.valveWells(null, 'with-segment')
    ])
    segments.value = segs
    wells.value = ws
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 试算 ----------------
function openTrial() {
  form.value = emptyForm()
  trialDlg.value = true
}

async function submitTrial() {
  if (!form.value.faultSegmentId) {
    ElMessage.warning('请先选择故障管段')
    return
  }
  loading.value = true
  try {
    const plan = await isolationApi.trial({
      faultSegmentId: form.value.faultSegmentId,
      faultReason: form.value.faultReason,
      strategy: form.value.strategy,
      createdBy: form.value.createdBy
    })
    trialDlg.value = false
    ElMessage.success(plan.proofPassed ? '试算完成，隔离证明通过' : '试算完成，但隔离证明未通过，请人工调整')
    tab.value = 'trial'
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openAdjust() {
  form.value = {
    ...emptyForm(),
    faultSegmentId: current.value.faultSegmentId,
    faultReason: current.value.faultReason || '',
    strategy: current.value.strategy || 'EXPAND'
  }
  adjustDlg.value = true
}

async function submitReTrial() {
  loading.value = true
  try {
    const plan = await isolationApi.reTrial(current.value.id, {
      faultSegmentId: form.value.faultSegmentId,
      faultReason: form.value.faultReason,
      strategy: form.value.strategy,
      extraCloseValveIds: form.value.extraCloseValveIds,
      extraOpenValveIds: form.value.extraOpenValveIds,
      removedValveIds: form.value.removedValveIds,
      createdBy: form.value.createdBy || undefined
    })
    adjustDlg.value = false
    ElMessage.success(plan.proofPassed ? '重新试算完成，证明通过' : '重新试算完成，证明仍未通过')
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doEnable() {
  try {
    const { value: operator } = await ElMessageBox.prompt('请输入下发调度员', '启用并下发现场', {
      confirmButtonText: '启用',
      cancelButtonText: '取消',
      inputValue: form.value.createdBy || ''
    }).catch(() => ({ value: null }))
    if (operator === null) return
    const plan = await isolationApi.enable(current.value.id, operator || '')
    ElMessage.success('方案已启用，现场可按序列逐步确认')
    tab.value = 'steps'
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 现场确认 ----------------
function canConfirm(step) {
  if (!current.value) return false
  const planActive = ['已启用', '执行中', '复供试算'].includes(current.value.planStatus)
  return planActive && step.stepStatus === '待执行'
}

function openConfirm(step) {
  confirmContext.value = {
    step,
    observedState: step.plannedState || '',
    operator: '',
    deviationReason: ''
  }
  confirmDlg.value = true
}

async function submitConfirm() {
  loading.value = true
  try {
    const plan = await isolationApi.confirmStep(
      current.value.id,
      confirmContext.value.step.id,
      {
        observedState: confirmContext.value.observedState || null,
        operator: confirmContext.value.operator,
        deviationReason: confirmContext.value.deviationReason || null
      }
    )
    confirmDlg.value = false
    if (plan.planStatus === '失败待处置') {
      ElMessage.warning('该步触发偏差 / 暂停，方案已转入失败待处置，请选择收口方式')
    } else {
      ElMessage.success('现场确认已记录')
    }
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// ---------------- 失败收口 ----------------
async function resolve(outcome) {
  const labelMap = {
    EXPAND: '扩大隔离并按当前实况重新试算？',
    ROLLBACK: '按安全顺序逆序恢复已动阀门并撤回方案？',
    HOLD: '保持现场状态、冻结方案等待人工处置？'
  }
  try {
    await ElMessageBox.confirm(labelMap[outcome], '失败收口', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  loading.value = true
  try {
    const plan = await isolationApi.resolveFailure(current.value.id, {
      outcome,
      operator: confirmContext.value.operator || ''
    })
    ElMessage.success('收口完成')
    tab.value = outcome === 'EXPAND' ? 'steps' : 'dev'
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// ---------------- 复供 ----------------
async function doRestore() {
  try {
    const { value: operator } = await ElMessageBox.prompt(
      '复供将按【当前阀门实况】重新生成顺序；现场另行调整的阀保持不动，仍不可达的换热站留异常清单。请输入调度员',
      '故障修复·发起复供试算',
      { confirmButtonText: '生成复供序列', cancelButtonText: '取消', inputValue: form.value.createdBy || '' }
    ).catch(() => ({ value: null }))
    if (operator === null) return
    const plan = await isolationApi.restoreTrial(current.value.id, operator || '')
    ElMessage.success('复供序列已生成，请现场逐步确认')
    tab.value = 'steps'
    await loadPlans(plan.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 展示辅助 ----------------
function valveLabel(w) {
  return `${w.wellNo}（节点 ${w.nodeNo || w.wellNo}｜${w.openState}${w.operable === false ? '｜不可操作' : ''}）`
}
function statusType(s) {
  return ({
    草稿: 'info', 已启用: 'primary', 执行中: 'warning', 已隔离: 'success',
    失败待处置: 'danger', 已撤回: 'info', 复供试算: 'warning', 已复供: 'success'
  })[s] || 'info'
}
function catType(c) {
  return ({ PLANNED_CLOSE: 'danger', ALREADY_CLOSED: 'info', MUST_OPEN: 'success', 现场保留: 'warning' })[c] || ''
}
function catLabel(c) {
  return ({ PLANNED_CLOSE: '计划关闭', ALREADY_CLOSED: '原本关闭', MUST_OPEN: '必须开启', 现场保留: '现场保留' })[c] || c
}
function stateClass(s) {
  if (s === '关闭') return 'st-closed'
  if (s === '全开') return 'st-open'
  return 'st-half'
}
function impactType(t) {
  return ({ 新停暖: 'danger', 维持保供: 'success', 原本即停: 'info', 恢复保供: 'primary' })[t] || 'info'
}
function actionType(a) {
  return ({ CLOSE: 'danger', OPEN: 'success', CHECK_CLOSED: 'info', EVACUATE: 'warning', PROOF: 'primary' })[a] || ''
}
function actionLabel(a) {
  return ({
    CLOSE: '关闭阀门', OPEN: '开启阀门', CHECK_CLOSED: '核对原关阀',
    EVACUATE: '人员撤离确认', PROOF: '隔离 / 复供验证'
  })[a] || a
}
function stepDot(s) {
  return ({ 待执行: 'primary', 已完成: 'success', 已跳过: 'info', 已暂停: 'danger', 偏差挂起: 'danger' })[s] || 'info'
}
function statusDot(s) {
  return stepDot(s)
}
function stepCardClass(s) {
  return {
    'step-done': s === '已完成',
    'step-paused': ['已暂停', '偏差挂起'].includes(s),
    'step-skipped': s === '已跳过'
  }
}
function resolveLabel(o) {
  return ({ EXPAND: '扩大隔离', ROLLBACK: '安全顺序恢复', HOLD: '保持现场等待处置' })[o] || o
}
function fmt(v) {
  if (v === null || v === undefined) return '0'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function formatTime(t) {
  return t ? t.replace('T', ' ').substring(0, 16) : ''
}

function preselectFromRoute() {
  const seg = props.seg
    || new URLSearchParams(window.location.hash.split('?')[1] || '').get('seg')
  if (seg) {
    form.value = { ...emptyForm(), faultSegmentId: Number(seg) }
    trialDlg.value = true
  }
}

watch(() => props.seg, (v) => {
  if (v) preselectFromRoute()
})

onMounted(async () => {
  await loadBaseData()
  await loadPlans()
  // 从管网平面图「该管段故障·发起隔离」跳入时预选故障管段
  preselectFromRoute()
})
</script>

<style scoped>
.iso-body {
  flex: 1;
  display: flex;
  gap: 12px;
  min-height: 0;
}

.iso-list {
  width: 268px;
  flex: 0 0 268px;
  background: #fff;
  border: 1px solid var(--hs-line);
  border-radius: 10px;
  padding: 10px;
  overflow: auto;
}

.iso-list-title {
  font-weight: 700;
  color: var(--hs-primary-deep);
  margin-bottom: 8px;
}

.iso-card {
  border: 1px solid var(--hs-line);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.iso-card:hover {
  border-color: var(--hs-primary);
}

.iso-card.active {
  border-color: var(--hs-primary);
  box-shadow: 0 0 0 2px rgba(13, 71, 161, 0.12);
  background: #f6f9ff;
}

.iso-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.iso-no {
  font-weight: 700;
  font-size: 13px;
  color: var(--hs-primary-deep);
}

.iso-card-fault {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  flex-wrap: wrap;
}

.iso-desc {
  color: var(--hs-muted);
  font-size: 11px;
}

.iso-card-meta {
  margin-top: 6px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.proof-dot {
  font-size: 12px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 10px;
}

.proof-dot.ok {
  color: var(--hs-ok);
  background: #eafaf0;
}

.proof-dot.bad {
  color: var(--hs-danger);
  background: #fdecec;
}

.iso-card-warn {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hs-danger);
}

.iso-detail {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid var(--hs-line);
  border-radius: 10px;
  padding: 14px 16px;
  overflow: auto;
}

.iso-empty {
  flex: 1;
  background: #fff;
  border: 1px dashed var(--hs-line);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.iso-detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.iso-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.iso-title-row h3 {
  margin: 0;
  font-size: 17px;
  color: var(--hs-primary-deep);
}

.iso-fault-line {
  margin-top: 6px;
  font-size: 13px;
  color: #375073;
}

.iso-head-actions {
  display: flex;
  gap: 8px;
}

.iso-alert {
  margin-top: 10px;
}

.iso-tabs {
  margin-top: 6px;
}

.trial-grid {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 12px;
}

.trial-col {
  min-width: 0;
}

.panel-h {
  font-weight: 700;
  font-size: 13px;
  color: var(--hs-primary-deep);
  padding: 6px 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.panel-h.mt,
.mt {
  margin-top: 12px;
}

.arrow {
  margin: 0 4px;
  color: var(--hs-muted);
}

.st-closed {
  color: var(--hs-danger);
}

.st-open {
  color: var(--hs-ok);
}

.st-half {
  color: var(--hs-hot);
}

.path-line {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 12px;
}

.path-arrow {
  color: var(--hs-muted);
}

.path-node {
  background: #eef4ff;
  border-radius: 4px;
  padding: 0 4px;
}

.alt-line {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hs-hot);
}

.consequence {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
}

.cons-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  background: #f7f9fc;
  border-radius: 6px;
  padding: 6px 8px;
}

.note-line {
  margin-top: 8px;
  font-size: 12px;
  color: var(--hs-hot);
}

.restore-note {
  color: var(--hs-ok);
}

.muted {
  color: var(--hs-muted);
}

.step-card {
  margin-bottom: 2px;
}

.step-card :deep(.el-card__body) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
}

.step-main {
  flex: 1;
  min-width: 0;
}

.step-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.step-well {
  font-size: 15px;
  color: var(--hs-primary-deep);
}

.step-meta {
  margin-top: 6px;
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #375073;
  flex-wrap: wrap;
}

.step-remark {
  margin-top: 4px;
  font-size: 12px;
  color: var(--hs-muted);
}

.step-dev {
  margin-top: 6px;
}

.step-done {
  background: #f4fbf6;
}

.step-paused {
  background: #fdf3f3;
  border-color: #f3b3b3;
}

.step-skipped {
  opacity: 0.6;
}

.resolve-box {
  margin-top: 16px;
  border: 1px dashed var(--hs-danger);
  border-radius: 10px;
  padding: 14px;
  background: #fff8f8;
}

.resolve-title {
  font-weight: 700;
  color: var(--hs-danger);
  margin-bottom: 10px;
}

.resolve-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.resolve-note {
  margin-top: 10px;
  font-size: 12px;
  color: #375073;
}

.dev-list {
  font-size: 13px;
  line-height: 1.9;
  color: #375073;
  padding-left: 20px;
}

.mb10 {
  margin-bottom: 10px;
}
</style>
