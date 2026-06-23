/**
 * Toast / Modal 工具 —— 替代 naive-ui 的 useMessage / useDialog
 *
 * 封装 uni.showToast / uni.showModal，统一 API：
 * - success(title)
 * - warn(title)
 * - error(title)
 * - confirm({title, content}) → Promise<boolean>
 */

export function success(title: string) {
  uni.showToast({ title, icon: 'success', duration: 2000 })
}

export function warn(title: string) {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}

export function error(title: string) {
  uni.showToast({ title, icon: 'error', duration: 3000 })
}

export interface ConfirmOptions {
  title?: string
  content: string
  confirmText?: string
  cancelText?: string
}

export function confirm(opts: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: opts.title || '确认',
      content: opts.content,
      confirmText: opts.confirmText || '确定',
      cancelText: opts.cancelText || '取消',
      success: (res) => {
        resolve(res.confirm)
      },
      fail: () => {
        resolve(false)
      },
    })
  })
}

export function loading(title = '加载中...') {
  uni.showLoading({ title, mask: true })
  return () => uni.hideLoading()
}
