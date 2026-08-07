import { execSync } from 'node:child_process'
import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

const ORG = 'milieuinfo'
const REPO = 'owl-sda'

// VitePress resolves the "last updated" timestamp by running `git log` per page.
// The Jenkins node container (node:20-alpine) has no git, so only enable the
// feature when git is actually available (local dev / GitHub Actions).
function gitAvailable() {
  try {
    execSync('git --version', { stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

function normalizeBasePath(value) {
  if (!value || value.trim() === '') {
    return '/'
  }
  const withLeadingSlash = value.startsWith('/') ? value : `/${value}`
  return withLeadingSlash.endsWith('/') ? withLeadingSlash : `${withLeadingSlash}/`
}

// Single source of truth for the deployed base path. CI sets DOCS_BASE explicitly;
// GitHub Actions falls back to the repository name; local builds use the repo name
// so `docs:preview` matches what GitHub Pages serves.
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

  return `/${REPO}/`
}

export default withMermaid(defineConfig({
  title: 'OWL-SDA',
  description:
    'OWL Synthetic Data AI-Agent — generate synthetic RDF instance data from OWL ontologies using Large Language Models',
  base: detectBasePath(),
  cleanUrls: true,
  lastUpdated: gitAvailable(),

  // paper/ and poster/ hold publication sources, not pages.
  srcExclude: ['**/README.md', 'paper/**', 'poster/**'],

  markdown: {
    languageAlias: {
      mmd: 'mermaid',
      ttl: 'turtle'
    }
  },

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'CLI', link: '/guide/cli' },
      { text: 'Configuration', link: '/guide/configuration' },
      { text: 'Examples', link: '/guide/examples' }
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/guide/introduction' },
          { text: 'Installation', link: '/guide/installation' },
          { text: 'Quick Start', link: '/guide/quickstart' },
          { text: 'License', link: '/guide/license' }
        ]
      },
      {
        text: 'Running OWL-SDA',
        items: [
          { text: 'CLI Reference', link: '/guide/cli' },
          { text: 'Configuration Reference', link: '/guide/configuration' },
          { text: 'Reasoner', link: '/guide/reasoner' },
          { text: 'External Ontologies', link: '/guide/external-ontologies' }
        ]
      },
      {
        text: 'Observing a run',
        items: [
          { text: 'Web UI', link: '/guide/web-ui' },
          { text: 'Benchmarking', link: '/guide/benchmarking' }
        ]
      },
      {
        text: 'Reference',
        items: [
          { text: 'Architecture', link: '/guide/architecture' },
          { text: 'Examples', link: '/guide/examples' }
        ]
      }
    ],
    socialLinks: [
      { icon: 'github', link: `https://github.com/${ORG}/${REPO}` }
    ],
    search: {
      provider: 'local'
    },
    editLink: {
      pattern: `https://github.com/${ORG}/${REPO}/edit/main/docs/:path`,
      text: 'Edit this page on GitHub'
    },
    footer: {
      message: 'Released under the GNU General Public License v3.0.'
    }
  }
}))
