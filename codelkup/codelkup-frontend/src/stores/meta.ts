import { defineStore } from 'pinia'
import { ref } from 'vue'
import { boardApi, tagApi } from '@/api/post'
import type { BoardVO, TagVO } from '@/types/api'

/**
 * 板块与标签。
 *
 * 都是低频变更的字典数据，**只在首次需要时加载一次**并常驻内存 ——
 * 板块列表几乎每个页面都要用（发布页选板块、首页左侧导航、筛选器），
 * 每次进页面都请求纯属浪费。
 */
export const useMetaStore = defineStore('meta', () => {
  const boards = ref<BoardVO[]>([])
  const hotTags = ref<TagVO[]>([])
  const boardsLoaded = ref(false)
  const tagsLoaded = ref(false)

  let boardPromise: Promise<BoardVO[]> | null = null
  let tagPromise: Promise<TagVO[]> | null = null

  /** 加载板块（去重：并发调用只发一次请求） */
  async function loadBoards(force = false): Promise<BoardVO[]> {
    if (boardsLoaded.value && !force) return boards.value
    boardPromise = boardPromise ?? boardApi.list()
    try {
      boards.value = await boardPromise
      boardsLoaded.value = true
      return boards.value
    } finally {
      boardPromise = null
    }
  }

  async function loadHotTags(force = false): Promise<TagVO[]> {
    if (tagsLoaded.value && !force) return hotTags.value
    tagPromise = tagPromise ?? tagApi.hot()
    try {
      hotTags.value = await tagPromise
      tagsLoaded.value = true
      return hotTags.value
    } finally {
      tagPromise = null
    }
  }

  /** 按 slug 找板块（路由里用的是 slug） */
  function boardBySlug(slug: string): BoardVO | undefined {
    return boards.value.find((b) => b.slug === slug)
  }

  return { boards, hotTags, boardsLoaded, tagsLoaded, loadBoards, loadHotTags, boardBySlug }
})
