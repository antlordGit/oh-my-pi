/**
 * 图片处理工具 —— 选图 + 转换为 base64
 *
 * 替代 H5/ChatView.vue 中的 FileReader.readAsDataURL + paste/drag 上传。
 * 小程序端仅支持通过按钮触发 uni.chooseImage。
 */

const ACCEPT_MIME: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
}

const MAX_IMAGES = 5
const MAX_BYTES = 5 * 1024 * 1024 // 5MB

export interface ImageItem {
  id: string
  localPath: string
  base64: string
  mimeType: string
  size: number
  name: string
}

function getMimeFromPath(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  return ACCEPT_MIME[ext] || 'image/png'
}

/**
 * 选择图片并转换为 base64
 * @param maxCount 最大可选数量，默认 5
 * @returns Promise<ImageItem[]> 选中的图片数组
 */
export async function chooseImages(maxCount = MAX_IMAGES): Promise<ImageItem[]> {
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count: maxCount,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: async (res) => {
        const paths = res.tempFilePaths
        const items: ImageItem[] = []
        const fsm = uni.getFileSystemManager()

        for (const path of paths) {
          // 获取文件信息
          const info = await new Promise<UniApp.GetFileInfoSuccessCallbackResult>(
            (reslove, rej) => {
              fsm.getFileInfo({
                filePath: path,
                success: (info) => reslove(info),
                fail: (err) => rej(new Error(err.errMsg)),
              })
            },
          )

          if (info.size > MAX_BYTES) {
            uni.showToast({ title: '图片不能超过 5MB', icon: 'none' })
            continue
          }

          // 读取 base64
          const base64 = await new Promise<string>((reslove, rej) => {
            fsm.readFile({
              filePath: path,
              encoding: 'base64',
              success: (data) => reslove(data.data as string),
              fail: (err) => rej(new Error(err.errMsg)),
            })
          })

          const mime = getMimeFromPath(path)
          const name = path.split('/').pop() || 'image'

          items.push({
            id: path,
            localPath: path,
            base64,
            mimeType: mime,
            size: info.size,
            name,
          })
        }

        resolve(items)
      },
      fail: (err) => {
        reject(new Error(err.errMsg || '选择图片失败'))
      },
    })
  })
}

/**
 * 删除图片（仅从数组移除，无实际文件操作）
 */
export function removeImage(items: ImageItem[], id: string): ImageItem[] {
  return items.filter((it) => it.id !== id)
}
