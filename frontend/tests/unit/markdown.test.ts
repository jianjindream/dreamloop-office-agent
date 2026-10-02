import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '@/utils/markdown'

describe('Markdown rendering', () => {
  it('renders ordinary Markdown', async () => {
    const html = await renderMarkdown('# 标题\n\n**重点**')
    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>重点</strong>')
  })

  it('does not render active HTML or unsafe links', async () => {
    const html = await renderMarkdown('<script>alert(1)</script>\n\n[危险](javascript:alert(1))')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('href="javascript:')
  })
})
