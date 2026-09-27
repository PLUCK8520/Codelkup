<script setup lang="ts">
/**
 * 举报弹窗（帖子 / 评论共用）。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>原因必选、说明可选</b>：原因决定审核员按什么口径看这条内容，
 *       没有原因的举报只是一句"我不喜欢"；说明是给"具体哪一句"留的补充位。</li>
 *   <li><b>每次打开都清空</b>：评论列表里每条评论共用一个弹窗实例，
 *       不清空的话"举报 A 时选的原因"会残留到"举报 B"，一次误提交就投诉错了人。</li>
 *   <li><b>40909 不当错误</b>：同一目标重复举报是幂等的（后端唯一索引保证），
 *       提示文案由 request.ts 统一弹（"已经举报过了"），这里只把弹窗关掉。</li>
 * </ol>
 *
 * <p>⚠️ 提交失败时弹窗**保持打开**（限流 42901、目标已被删 404xx）：
 * 用户需要看到失败原因后自己决定是否重试，直接关掉会让人以为举报成功了。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { REPORT_REASONS, reportApi } from '@/api/report'
import type { ReportReason, ReportTargetType } from '@/types/api'

const props = defineProps<{
  visible: boolean
  targetType: ReportTargetType
  targetId: number
  /** 弹窗里指代目标的话术，如「这篇帖子」「这条评论」 */
  targetLabel: string
}>()

const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

/** 与后端 ReportCreateRequest.detail 的 @Size(max=200) 一致 */
const DETAIL_MAX = 200

const reason = ref<ReportReason | ''>('')
const detail = ref('')
const submitting = ref(false)

const canSubmit = computed(() => reason.value !== '' && detail.value.length <= DETAIL_MAX)

watch(
  () => props.visible,
  (open) => {
    if (open) {
      reason.value = ''
      detail.value = ''
      submitting.value = false
    }
  },
)

function close() {
  emit('update:visible', false)
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    await reportApi.create({
      targetType: props.targetType,
      targetId: props.targetId,
      reason: reason.value as ReportReason,
      // 空说明不发送空串：后端把空串归一成 null，前端少传一个无意义字段
      detail: detail.value.trim() || undefined,
    })
    ElMessage.success('举报已提交，我们会尽快核实处理')
    close()
  } catch (e) {
    // 40909 = 已经举报过（幂等结果）；提示已由 request.ts 弹出，这里只关窗
    if ((e as { code?: number })?.code === 40909) {
      close()
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="`举报${targetLabel}`"
    width="440px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <div class="report-form">
      <p class="form-hint">请选择举报原因（必选）。我们只处理真实的违规内容。</p>

      <el-radio-group v-model="reason" class="reason-list">
        <label v-for="item in REPORT_REASONS" :key="item.value" class="reason-item">
          <el-radio :value="item.value">{{ item.label }}</el-radio>
          <span class="reason-hint">{{ item.hint }}</span>
        </label>
      </el-radio-group>

      <el-input
        v-model="detail"
        type="textarea"
        :rows="3"
        :maxlength="DETAIL_MAX"
        show-word-limit
        resize="none"
        placeholder="补充说明（可选）：具体是哪一句、发生了什么"
      />
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="danger" :loading="submitting" :disabled="!canSubmit" @click="submit">
        提交举报
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.report-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.form-hint {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
  line-height: 1.7;
}

.reason-list {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--space-2);
}

.reason-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.reason-item:hover {
  background: var(--color-muted);
  border-color: var(--color-border);
}

.reason-hint {
  font-size: var(--text-xs);
  color: var(--color-subtle-foreground);
}
</style>