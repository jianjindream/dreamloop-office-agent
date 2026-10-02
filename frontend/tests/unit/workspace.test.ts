import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

describe('workspace preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    setActivePinia(createPinia())
  })

  it('persists and applies the chosen theme', () => {
    const store = useWorkspaceStore()
    expect(store.theme).toBe('light')
    store.toggleTheme()
    expect(store.theme).toBe('dark')
    expect(localStorage.getItem('dreamloop-theme')).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('toggles workspace panels independently', () => {
    const store = useWorkspaceStore()
    store.toggleSidebar()
    store.toggleContext()
    expect(store.sidebarCollapsed).toBe(true)
    expect(store.contextOpen).toBe(false)
    expect(store.knowledgeEnabled).toBe(true)
  })
})
