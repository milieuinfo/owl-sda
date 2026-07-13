import { defineConfig } from 'vitepress'

function normalizeBasePath(value) {
  if (!value || value.trim() === '') {
    return '/'
  }
  const withLeadingSlash = value.startsWith('/') ? value : `/${value}`
  return withLeadingSlash.endsWith('/') ? withLeadingSlash : `${withLeadingSlash}/`
}

function detectBasePath() {
  if (process.env.DOCS_BASE) {
    return normalizeBasePath(process.env.DOCS_BASE)
  }

  if (process.env.GITHUB_ACTIONS === 'true' && process.env.GITHUB_REPOSITORY) {
    const repositoryName = process.env.GITHUB_REPOSITORY.split('/')[1]
    if (repositoryName) {
      return normalizeBasePath(repositoryName)
    }
  }

  return '/'
}

const base = detectBasePath()

export default defineConfig({
  base,
  title: 'OWL-SDA',
  description: 'OWL Synthetic Data AI-Agent — generate synthetic RDF instance data from OWL ontologies using Large Language Models',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'Configuration', link: '/guide/configuration' },
      { text: 'Examples', link: '/guide/examples' },
      { text: 'License', link: '/guide/license' },
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/guide/introduction' },
          { text: 'Installation', link: '/guide/installation' },
          { text: 'Quick Start', link: '/guide/quickstart' },
          { text: 'License', link: '/guide/license' },
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
      message: 'Released under the GNU General Public License v3.0.',
    }
  }
})
