import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OWL-SDA',
  description: 'Documentation for the OWL-SDA project',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/usage' },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Usage', link: '/guide/usage' },
        ]
      }
    ]
  }
})

