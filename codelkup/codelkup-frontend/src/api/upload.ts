import { request } from '@/utils/request'

/** 上传接口 —— 对应后端 UploadController */
export const uploadApi = {
  /**
   * 上传正文配图，返回可直接写进 Markdown 的 URL。
   *
   * 单独走 request 而不是复用 `upload()` helper：这里要覆盖超时时间。
   * 全局 15s 对"手机拍的大图 + 弱网"偏紧，而上传失败重来一次的成本很高
   * （用户得重新选图）。
   */
  image: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return request<{ url: string }>({
      method: 'POST',
      url: '/uploads/images',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 30000,
    })
  },
}