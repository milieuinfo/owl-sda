import { defineConfig } from 'vitepress'

// When deployed to GitHub Pages the site is served from /<repo-name>/.
// Set VITEPRESS_BASE in the environment to override (e.g. '/' for a custom domain).
const base = process.env.VITEPRESS_BASE ?? '/OWL-SDA/'

export default defineConfig({
  base,
  title: 'OWL-SDA',
  description: 'OWL Synthetic Data AI-Agent — generate synthetic RDF instance data from OWL ontologies using Large Language Models',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'Configuration', link: '/guide/configuration' },
      { text: 'Examples', link: '/guide/examples' },
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/guide/introduction' },
          { text: 'Installation', link: '/guide/installation' },
          { text: 'Quick Start', link: '/guide/quickstart' },
        ]
      },
      {
        text: 'Configuration',
        items: [
          { text: 'Configuration Reference', link: '/guide/configuration' },
          { text: 'Reasoner', link: '/guide/reasoner' },
          { text: 'External Ontologies', link: '/guide/external-ontologies' },
          { text: 'Benchmarking', link: '/guide/benchmarking' },
        ]
      },
      {
        text: 'Advanced',
        items: [
          { text: 'Architecture', link: '/guide/architecture' },
          { text: 'Examples', link: '/guide/examples' },
        ]
      }
    ],
    socialLinks: [],
    footer: {
      message: 'Released under the MIT License.',
    }
  }
})
