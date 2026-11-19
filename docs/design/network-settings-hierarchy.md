# Network settings hierarchy · 2026-11-17

The two supplied screenshots were actually inspected. The network screenshot shows the previous provider radio with a selection check beside a failed Relay state, a second direct/proxy choice, ambient VPN metadata, a new-check action carrying a historical timestamp, two equal-weight automation switches and long footnotes. The playback screenshot shows a separate decoder failure; it is not evidence that the displayed proxy setting caused that failure. Neither screenshot contains an application version. Their network layout is consistent with the b309ed8-or-later UI, not proof of a particular installed build.

The connection page keeps Relay and custom address as one explicit provider selection. Selection styling is not a successful-connection receipt. The selected provider's authorization/status or saved address remains adjacent; failed or missing authorization stays visible. Existing Relay evidence details remain available. Default route is disclosed as the fallback for projects without their own choice, not another proxy provider or a reset-all action.

Starting a check and viewing a previous result are separate actions. Only historical results carry their old timestamp. Automatic checking and automatic application retain independent saved settings inside one progressive-disclosure section with a truthful summary. Source/category grouping and exact-target locks remain on the usage page. Ambient system VPN/proxy observations are separately labelled device reports, never proof of the route used by an app request.

No routing stores, automatic-check policy, credentials, source adapters or device-network settings change in this UI slice. Existing request/transport incompatibility and in-flight behavior remain authoritative. No local build, automated test or device-layout validation was performed.

## Route scope clarity · 2026-11-19

User feedback: “关于网络的 bug 网络与下载中心显示的值不太对吧为什么显示直连以及某个东西的网络配置为什么有个跟随默认？” The attached `1000122148.jpg` / `1000122149.jpg` show installed version 0.1.398, a direct route on the settings overview, and an explicit proxy selection for application updates. The current overview reads only `defaultMode`; update checks and new update downloads read the `UPDATES` category snapshot. This establishes a display-scope defect, not evidence that update traffic bypassed its selected proxy. Existing downloads intentionally retain their captured route.

- **NET-SCOPE-01 — Overview:** identify the global default explicitly and show the number of stored category/source overrides on a separate, short second line. Count configuration entries, including explicit choices equal to the default; do not present this count as active connections or verified routes.
- **NET-SCOPE-02 — Inheritance:** category menus say “使用全局设置”, show its current value and explain that it changes with the global setting. Explicit choices say “单独直连 / 单独代理”. Task menus identify their category or manga-source parent, label explicit choices “本次”, and explain that a newly created task captures its route. Inheritance clears an override; it is not another transport mode. Retained `SYSTEM` values stay explicitly labelled “系统线路”.
- **NET-SCOPE-03 — Authority:** retain explicit task → source → category → global precedence, proxy validation, Relay binding, automatic-optimization locks and existing-task snapshots. Global mode changes do not rewrite category/source choices. A configured direct route is not proof that a device VPN was bypassed.

This local correction reuses the current settings row, retained `CompactGlassPopover`, `MotionTextButton`, `MotionIconButton`, selection rows and press feedback. The metrics-system guidance supplies the distinction between configuration scope and observed connection data; the recovery-system guidance keeps task feedback tied to the captured task. It introduces no new component family or navigation. The first line answers which default is set, the second exposes exceptions, and details stay in the existing menu/help surface.

### Scoped design and acceptance

Source and diff inspection can confirm these decisions; all target-device results remain **unverified** under the project's user-validation rule. The user-facing acceptance path is Settings → Network and downloads → change the global default, then inspect the application-update menu with and without its individual choice. Existing downloads must retain their task route unless explicitly restarted with current settings.

| System | Design / acceptance boundary | Current evidence |
| --- | --- | --- |
| Color and surfaces | Keep selected, disabled and error treatments; text must identify scope without color. | Existing treatments retained; device unverified. |
| Shape and corners | Reuse the existing row/popover geometry. | No geometry change; device unverified. |
| Space and density | Global route and override count use the existing two-line overview slot; explanations stay disclosed. | Source layout reviewed; device unverified. |
| Typography and readability | Keep existing text roles and allow menu labels/details to wrap; explicit system label must remain readable. | Source reviewed; narrow screens and large type unverified. |
| Components and states | Inherit is selected only for a null override; explicit direct/proxy remain separate choices. | Selection handlers retained; device unverified. |
| Motion and transitions | Keep retained popup-to-help handoff and press feedback. | No lifecycle/animation changes; device unverified. |
| Icons and symbols | Use the link icon for inheritance, distinct from direct/proxy; retain accessible choice text. | Source reviewed; device unverified. |
| Images and media | This route-control correction contains no media changes. | Not involved. |
| Navigation and information architecture | Make global, category and task ownership visible without adding a settings layer. | Source ownership reviewed; device unverified. |
| Screen sizes and inputs | Preserve 44 dp choice targets and the existing font-scaled popover; review longer labels. | Source reviewed; touch, keyboard and enlarged type unverified. |
| Shadows, layers and materials | Keep the existing glass panel and background relationship. | No material changes; device unverified. |
| Content and wording | Show the default's scope, inheritance parent and explicit override; never imply measured transport success. | Copy and route call sites reviewed; user comprehension unverified. |
| Interaction and perceived performance | Retain immediate local selection/save feedback; no new probing or asynchronous work. | No execution changes; device unverified. |
| Data and monitoring | Count stored overrides and label `SYSTEM` truthfully; configuration is not a connection measurement. | Source reviewed; live device state unverified. |
| Task feedback and recovery | Explain new-request/new-task scope and preserved routes for existing tasks. | Updater and task route call sites reviewed; device unverified. |
