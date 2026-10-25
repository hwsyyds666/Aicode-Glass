<p align="center">
  <img src="docs/icon.webp" width="112" alt="AiCode" />
  <h1 align="center">AiCode</h1>
  <p align="center">
    Universal AI Coding Agent for Android · Linux dev environment · Local & remote SSH
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

> **Fork Notice**: This is a fork of the original project [jieapi/AiCode](https://github.com/jieapi/aicode),
> adding **Liquid Glass UI (PrismalAGSL refraction / chromatic dispersion / gradient glass)**,
> **AI todo display position preferences**, **floating glass title bar**, and other enhancements,
> plus container channel and performance fixes. This fork also adds **ChatGPT multi-account aggregation**
> (batch import, tier-sorted single-group selector, session affinity, free/paid quota routing),
> **notification-bar task progress**, **maximized Anthropic context caching** (4-breakpoint rolling cache),
> and **smooth chat-list scrolling**. All credit to the original author; this fork
> is released under the same [GPL-3.0](LICENSE) license.
>
> Original project: https://github.com/jieapi/aicode

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <a href="https://github.com/hwsyyds666/Aicode-Glass/releases"><img src="https://img.shields.io/github/v/release/hwsyyds666/Aicode-Glass?display_name=tag&include_prereleases" alt="Latest Release" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="AiCode home - AI chat interface with code generation and Markdown rendering" width="270"/></td>
      <td align="center"><img src="docs/screenshots/git.png" alt="AiCode Git integration - visual commit history and branch management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Home · AI Chat</td>
      <td align="center">Git · Commit History</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/container.png" alt="AiCode container settings - container image management" width="270"/></td>
      <td align="center"><img src="docs/screenshots/models.png" alt="AiCode model list - multi-provider model management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Container · Image Manager</td>
      <td align="center">Models · Provider List</td>
    </tr>
  </table>
</p>

---

## Overview

AiCode is a universal AI coding agent that runs on Android, packing a full Linux development environment into your phone: it bundles an Alpine Linux container and terminal, and the AI agent can read and write files, run shell commands and run build tools, so writing, debugging and building all happen on-device. A remote SSH server can also serve as the execution backend, turning your phone into a mobile workstation for remote projects.

There is nothing to set up beforehand: install the app, configure a model under AI Vendors, and you can start coding — no computer, no environment to build yourself. AiCode ships no models and locks you into no vendor: it supports the OpenAI / Anthropic / Gemini protocols plus custom providers, with models, keys and endpoints all configured by you.

## Sponsors

| Icon | Description |
|------|-------------|
| <img src="https://www.rainyun.com/favicon.ico" width="24" alt="RainYun" /> | **[RainYun](https://www.rainyun.com/logins_)** — Sponsor of this project's server; a Chinese cloud provider specializing in VPS and game hosting (one-click Minecraft and other game servers), plus bare-metal machines and object storage; discounts for new users |
| <img src="https://ai.onyxaxis.org/api/site/logo" width="24" alt="Axis AI" /> | **[Axis AI](https://ai.onyxaxis.org/register?invite=AICODE)** — A free non-profit AI platform offering frontier models; register with invite code `AICODE` for 7 days of Go benefits |

## Advertisement

| Icon | Description |
|------|-------------|
| <img src="https://opencode.ai/favicon-96x96-v3.png" width="24" alt="OpenCode" /> | **[OpenCode Go](https://opencode.ai/go?ref=8Q5GA5B1NY)** — Low-cost subscription with generous limits and reliable access to the most capable open-source models |
| <img src="https://www.qiniu.com/favicon.ico" width="24" alt="Qiniu" /> | **[Qiniu Cloud AI](https://s.qiniu.com/vUryau)** — New users get 3,000,000 free tokens on sign-up (valid for 2 years), covering 50+ popular models |

## Features

### AI Agent

- **AI Agent** — Built-in tools for file read/write/edit, shell execution, background terminal, code & web search, image recognition, todo lists, and more; streaming output with live Markdown rendering and automatic context compression
- **Notification task progress** — While the AI runs a task, a persistent notification shows the task title; expand it to follow the current step in real time (preparing → thinking → generating → running a tool). Multiple parallel tasks show "N tasks running"; it auto-dismisses on completion
- **Maximized context caching** — Anthropic uses all 4 `cache_control` breakpoints (system prompt + tool definitions + original user question + a rolling breakpoint). The ever-growing assistant + tool_result history inside tool loops flows through the rolling breakpoint — each step writes a fresh cache and the next step reads it, so only the last tool_result is recomputed on long tasks
- **Parallel subagents** — Enable a session's built-in Explore, Research, Worker and Reviewer agents; workers have scoped writes, cannot nest agents, and return work for the main agent to review
- **Three run modes** — BUILD for normal development, PLAN to draft and execute a tracked plan under the same tool permissions, and ASK for read-only questions
- **Checkpoints & Undo** — Snapshots are recorded before the agent edits code; one-tap rollback of code, chat history, or both
- **Skills & Auto Memory** — Global/project-level skills and long-term memory let the AI reuse experience and project conventions across sessions
- **MCP Protocol** — Connect to local (stdio) or remote (HTTP) MCP servers to dynamically extend AI tool capabilities
- **Tool permissions & custom prompts** — Per-tool authorization rules; system prompts can be overridden by the user and survive app upgrades

### Models & Providers

- **Model-agnostic** — No bundled models and no vendor lock-in: models, keys and endpoints are yours to configure
- **Protocol support** — Compatible with the OpenAI / Anthropic / Gemini protocols, with presets for several official providers and support for custom ones; model lists and pricing are customizable
- **Multiple keys** — Configure several keys per provider with sequential or round-robin rotation; reasoning effort is adjustable

### Development Environment

- **Built-in Terminal & Container** — A local Linux container built on Termux and PRoot with a built-in Alpine image; supports custom rootfs images and host directory mounts; multi-tab terminals that can stay alive in the background
- **Remote SSH Mode** — Use a remote SSH server as the execution backend: commands, files and terminal all act on the remote project
- **File tree & code editor** — An indented file tree that opens files in a full-screen editor with syntax highlighting for mainstream languages and Markdown preview; `file:line` links in AI replies jump straight to that line, in both local and remote SSH workspaces
- **Git Integration** — Visual management of status, branches, commit history, diffs and tags, plus staging/discarding changes, sign-off and credentials
- **Workspace Sync** — SFTP / FTP synchronization with a built-in FTP server for desktop file management

### Experience

- **Tablet & large screen** — Layout adapts to window width: a persistent sidebar on wide screens with code or terminal beside the chat, falling back to a single pane when narrow
- **Token stats** — Usage and cost estimates per provider and model, with a drill-down into individual calls
- **Appearance & language** — Light/dark themes, preset color schemes, Material You colors, custom background images, and a bilingual (Chinese/English) UI
- **Network proxy** — Configure a global proxy and per-provider proxies separately
- **Backup & Restore** — Encrypted export/import of provider configs, chat history and workspace files; device-bound GitHub/OpenAI OAuth tokens are excluded and must be reauthorized after restore

### Official Accounts

- **ChatGPT multi-account aggregation** — Sign in with ChatGPT to access the official Codex Responses API. Supports batch import aligned with cockpit-tools export formats (sub2api, refresh-token-only, inline JSON arrays). Tokens are encrypted on-device with Android Keystore. The model selector aggregates all accounts into a single group sorted by subscription tier; session affinity binds a session to one account by default and soft-unbinds on credential failure. Quota routing sends free models to free accounts and paid-only models to paid accounts — accounts that are quota-paused, offline or disabled are never silently switched. The model catalog is filtered only by `visibility` (aligned with the official client) and silently refreshed with a 30-minute TTL when the selector opens. Local token totals are for reference only; OpenAI's usage page is the authoritative view.
- **GitHub** — Connect with Device Flow, browse and clone repositories, and confirm each pull-request creation. The app never pushes a branch on your behalf.

## Getting Started

| Item | Description |
|------|-------------|
| System requirements | Android 8.0+ (API 26), arm64-v8a / x86_64 |
| Download | [GitHub Releases](https://github.com/hwsyyds666/Aicode-Glass/releases/latest): pick `armsolo` for real devices, `x86solo` for emulators, `universal` for both |
| Quick start | Settings → AI Vendors to add a model → Container & Images to pick local or SSH → new session and chat |
| Changelog | [Releases](https://github.com/hwsyyds666/Aicode-Glass/releases) (all versions & notes) |
| User guide | [Online docs](https://aicode.murk.top): quick start, user manual and advanced guides (same content as the in-app docs) |

## Star

If AiCode is helpful to you, give it a [Star](https://github.com/hwsyyds666/Aicode-Glass) — it helps more developers discover the project.

## Star History

<a href="https://www.star-history.com/?repos=jieapi%2Faicode&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
 </picture>
</a>

## Feedback & Contribution

- **QQ group**: join the [AiCode QQ group](https://qm.qq.com/q/ByvqODJdIs) (group number: 1107110698) to share tips and feedback
- **Bug reports**: open an [Issue](https://github.com/hwsyyds666/Aicode-Glass/issues) with reproduction steps, device model and OS version
- **Feature requests**: discuss your ideas in [Issues](https://github.com/hwsyyds666/Aicode-Glass/issues)
- **Contributing**: submit a [Pull Request](https://github.com/hwsyyds666/Aicode-Glass/pulls)

## Acknowledgements

- **[jieapi/AiCode](https://github.com/jieapi/aicode)** — Original project, the foundation of this fork
- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference
- [PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) — AGSL liquid glass effect library

## License

This project is licensed under [GPL-3.0](LICENSE).
