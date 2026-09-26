/**
 * 粘贴 / 选择图片 → 前端压缩 → 上传 → 在光标处插入 Markdown（M2-12 / ADR-4）。
 *
 * <h2>为什么压缩放在前端</h2>
 * ADR-4 定了口径：从后端压缩要引入支持 WebP 写出的 native 编码依赖
 * （JDK 自带的 ImageIO 不写 WebP），而浏览器 Canvas 天然就有 WebP 编码器。
 * 顺带的好处是省流量：3MB 的截图压完通常几百 KB，上传与加载都快一个数量级。
 * <b>但后端仍然是安全边界</b> —— 它可以被绕过（curl 直接传原图），
 * 所以扩展名白名单 / 魔数 / 尺寸 / 配额 / 限流一样都没省。
 *
 * <h2>两个容易踩的点</h2>
 * <ol>
 *   <li><b>不能无条件拦截 paste 事件</b>：用户粘贴纯文本时必须放行默认行为，
 *       否则"从别处复制一段代码贴进来"就废了。这里只在剪贴板里真的有图片文件时才
 *       拦截，并且只在图片类型受支持时才处理。</li>
 *   <li><b>压缩后的文件必须重命名</b>：后端按"文件名最后一段扩展名"判类型，
 *       Canvas 出来的是 WebP，如果沿用原文件名 {@code shot.png} 就会被魔数校验拒掉
 *       （这正是防伪造的必要代价）。</li>
 * </ol>
 *
 * @author 刘
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadApi } from '@/api/upload'

export interface UseImagePasteOptions {
  /** 把生成的 Markdown 片段插到编辑器光标处 */
  insert: (markdown: string) => void
  /** 长边上限（像素），默认 1600 —— 正文展示宽度远小于此，再大是浪费 */
  maxEdge?: number
  /** 压缩后仍超过该字节数则拒绝（与后端 5MB 上限对齐，提前拦省一次往返） */
  maxBytes?: number
}

/** 与后端 ImageStorageService 的扩展名白名单一致 */
const SUPPORTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/** 目标体积：正文配图 400KB 已足够清晰（截图类图片通常远低于此） */
const TARGET_BYTES = 400 * 1024

/**
 * 压缩档位（按顺序尝试，命中 {@link TARGET_BYTES} 就停）。
 *
 * 前两档保分辨率、只降质量（正常截图在第一档就结束）；
 * 后两档才动分辨率 —— 先把清晰度让到底，再动尺寸，
 * 这样"能压住就不缩图"，避免所有图都被无谓地缩成小图。
 */
function compressionSteps(maxEdge: number) {
  return [
    { edge: maxEdge, quality: 0.82 },
    { edge: maxEdge, quality: 0.7 },
    { edge: Math.round(maxEdge * 0.8), quality: 0.7 },
    { edge: Math.round(maxEdge * 0.64), quality: 0.65 },
  ]
}

export function useImagePaste(options: UseImagePasteOptions) {
  const maxEdge = options.maxEdge ?? 1600
  const maxBytes = options.maxBytes ?? 5 * 1024 * 1024

  /** 正在压缩/上传 —— 工具栏据此显示进度并防重复提交 */
  const uploading = ref(false)

  /**
   * 压缩并上传，成功后插入 Markdown。
   *
   * @returns 成功时的 URL；失败返回 null（错误提示已由调用链弹出）
   */
  async function uploadAndInsert(file: File): Promise<string | null> {
    if (!SUPPORTED_TYPES.includes(file.type)) {
      ElMessage.warning('仅支持 jpg / png / webp 图片')
      return null
    }

    uploading.value = true
    try {
      const compressed = await compress(file, maxEdge)
      if (compressed.size > maxBytes) {
        ElMessage.warning('图片过大，压缩后仍超过 5MB，请换一张或先裁剪')
        return null
      }

      const { url } = await uploadApi.image(compressed)
      options.insert(`![](${url})`)
      ElMessage.success(`图片已插入（${Math.round(compressed.size / 1024)} KB）`)
      return url
    } catch {
      // 业务失败（类型/尺寸/配额/限流）已由 request.ts 统一提示，这里不重复
      return null
    } finally {
      uploading.value = false
    }
  }

  /**
   * 粘贴处理器。只处理"剪贴板里有受支持图片"的情况，
   * 其余一律不拦截（复制粘贴文本是编辑器的基本能力）。
   */
  function onPaste(e: ClipboardEvent) {
    const items = e.clipboardData?.items
    if (!items) return

    const imageItem = Array.from(items).find(
      (item) => item.kind === 'file' && SUPPORTED_TYPES.includes(item.type),
    )
    if (!imageItem) return

    const file = imageItem.getAsFile()
    if (!file) return

    // 图片粘贴没有"默认行为"可言（浏览器会插入文件名或什么都不做），直接接管
    e.preventDefault()
    void uploadAndInsert(file)
  }

  return { uploading, onPaste, uploadAndInsert }
}

/**
 * 把图片压到长边 ≤ maxEdge 的 WebP，并尽量压到 {@link TARGET_BYTES} 以内。
 *
 * <h2>为什么要"自适应"而不是一个固定质量</h2>
 * 同样 1600px 宽，一张代码截图压完往往只有一两百 KB，而一张满是噪点的照片
 * 在 q0.82 下能到 1MB —— 固定质量要么让截图浪费体积，要么让照片堵在路上。
 * 所以按"目标体积"逐档退让（见 {@link compressionSteps}）。
 *
 * <p>全部档位都没压进目标时，返回**体积最小的那一档**而不是最后一档：
 * 极端情况下再缩反而更大（重采样后更难压），保留最小值才不会把优化做成劣化。
 * 此时体积仍远超目标也照样上传 —— 后端的 5MB 上限才是硬边界，
 * 前端压缩的目标是"通常足够小"，不是"不惜代价压到 400KB"。
 */
async function compress(file: File, maxEdge: number): Promise<File> {
  const bitmap = await createImageBitmap(file)
  try {
    let smallest: Rendered | null = null
    for (const step of compressionSteps(maxEdge)) {
      const rendered = await render(bitmap, step.edge, step.quality)
      if (!smallest || rendered.blob.size < smallest.blob.size) {
        smallest = rendered
      }
      if (rendered.blob.size <= TARGET_BYTES) {
        return toFile(rendered)
      }
    }
    return toFile(smallest!)
  } finally {
    bitmap.close?.()
  }
}

interface Rendered {
  blob: Blob
  width: number
  height: number
}

/** 按长边约束缩放并编码（优先 WebP，编码器不支持时退回 JPEG） */
async function render(bitmap: ImageBitmap, maxEdge: number, quality: number): Promise<Rendered> {
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    throw new Error('无法创建画布上下文')
  }
  ctx.drawImage(bitmap, 0, 0, width, height)

  const webp = await toBlob(canvas, 'image/webp', quality)
  const blob = webp ?? (await toBlob(canvas, 'image/jpeg', quality))
  if (!blob) {
    throw new Error('图片编码失败')
  }
  return { blob, width, height }
}

/**
 * 包成 File 时必须**重命名**：后端按文件名最后一段判类型 + 魔数复核，
 * 沿用 "shot.png" 会与实际 WebP 字节不符而被拒（这正是防伪造的必要代价）。
 */
function toFile(rendered: Rendered): File {
  const isWebp = rendered.blob.type === 'image/webp'
  const ext = isWebp ? 'webp' : 'jpg'
  return new File([rendered.blob], `pasted-${rendered.width}x${rendered.height}.${ext}`, {
    type: rendered.blob.type,
  })
}

function toBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality))
}