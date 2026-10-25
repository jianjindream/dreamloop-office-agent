import { expect, test, type Page } from '@playwright/test'

const setupSessions = async (page: Page, state: { title: string; exists: boolean }) => {
  const timestamp = '2026-10-23T08:00:00Z'
  await page.route('**/api/auth/me', (route) => route.fulfill({ json: { user_id: 'same-user', workspace_id: 'same-workspace' } }))
  await page.route('**/api/status', (route) => route.fulfill({ json: { rag_loaded: false, rag_mode: 'mock', tools_count: 0, llm_model: 'mock', embedding_model: 'mock', is_mock: true, infrastructure: {} } }))
  await page.route('**/api/tools', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/documents', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/sessions**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/messages')) return route.fulfill({ json: [
      { id: 1, role: 'user', content: '请整理上线检查事项', created_at: timestamp },
      { id: 2, role: 'assistant', content: '# 服务端工作记录\n\n接口设计已完成。', created_at: timestamp },
    ] })
    if (route.request().method() === 'PATCH') { state.title = route.request().postDataJSON().title; return route.fulfill({ json: { ok: true } }) }
    if (route.request().method() === 'DELETE') { state.exists = false; return route.fulfill({ json: { ok: true } }) }
    return route.fulfill({ json: state.exists ? [{ id: 'server-session', title: state.title, created_at: timestamp, updated_at: timestamp, message_count: 2 }] : [] })
  })
}

test('server history works in a fresh browser context and supports rename and deletion', async ({ page, browser }) => {
  const state = { title: '服务端周报', exists: true }
  await setupSessions(page, state)
  await page.goto('/chat')
  await expect(page.getByText('接口设计已完成。', { exact: true })).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept('发布周报'))
  await page.getByRole('button', { name: '重命名会话' }).click()
  await expect(page.locator('.session-actions')).toContainText('发布周报')
  const context = await browser.newContext({ viewport: page.viewportSize() ?? undefined })
  try {
    const fresh = await context.newPage()
    await setupSessions(fresh, state)
    await fresh.goto('http://127.0.0.1:5175/chat')
    await expect(fresh.locator('.session-actions')).toContainText('发布周报')
    await expect(fresh.getByText('接口设计已完成。', { exact: true })).toBeVisible()
    fresh.once('dialog', (dialog) => dialog.accept())
    await fresh.getByRole('button', { name: '删除会话' }).click()
    await expect(fresh.locator('.session-actions')).toHaveCount(0)
    await fresh.reload()
    await expect(fresh.locator('.session-actions')).toHaveCount(0)
  } finally { await context.close() }
})

test('global search finds conversations by their server-side message content', async ({ page }) => {
  await setupSessions(page, { title: '服务端周报', exists: true })
  await page.goto('/chat')
  await expect(page.locator('.session-actions')).toBeVisible()
  await page.keyboard.press('Control+k')
  await page.getByRole('textbox', { name: '搜索页面、对话和文档' }).fill('上线检查')
  await expect(page.getByRole('button', { name: /服务端周报.*会话历史/ })).toBeVisible()
})
