import { Component, Input, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DiagnosticsService } from '../services/diagnostics.service';

@Component({
  selector: 'app-diagnostics',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="diagnostics-container">
      <!-- Tab Bar -->
      <div class="diag-tabs">
        <button class="diag-tab" [class.active]="activeTab === 'health'" (click)="activeTab = 'health'">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          Health
        </button>
        <button *ngIf="!publicMode" class="diag-tab" [class.active]="activeTab === 'sip'" (click)="activeTab = 'sip'; loadSip()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72"></path></svg>
          SIP / NAT
        </button>
        <button *ngIf="!publicMode" class="diag-tab" [class.active]="activeTab === 'logs'" (click)="activeTab = 'logs'; loadLogServices()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
          Logs
        </button>
        <div class="diag-tabs-spacer"></div>
        <button class="diag-refresh-btn" (click)="refresh()" [disabled]="loading">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" [class.spin]="loading"><path d="M23 4v6h-6"></path><path d="M1 20v-6h6"></path><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>
          Refresh
        </button>
      </div>

      <!-- Health Tab -->
      <div *ngIf="activeTab === 'health'" class="diag-panel">
        <div *ngIf="loading && !healthData" class="diag-loading">Loading health data...</div>

        <div *ngIf="healthData">
          <div *ngIf="publicMode" class="diag-card-detail" style="margin-bottom: 12px;">
            Public view: detailed SIP diagnostics and logs require login.
          </div>
          <!-- Overall Status Banner -->
          <div class="diag-overall" [class.up]="healthData.overall === 'UP'" [class.degraded]="healthData.overall !== 'UP'">
            <div class="diag-overall-dot"></div>
            {{ healthData.overall === 'UP' ? 'All Systems Operational' : 'System Degraded' }}
          </div>

          <!-- Infrastructure -->
          <div class="diag-section-title">Infrastructure</div>
          <div class="diag-cards">
            <div *ngFor="let item of infraItems" class="diag-card" [class.up]="item.status === 'UP'" [class.down]="item.status !== 'UP'">
              <div class="diag-card-header">
                <div class="diag-card-dot"></div>
                <span class="diag-card-name">{{ item.name }}</span>
              </div>
              <div class="diag-card-detail">{{ item.endpoint || '' }}</div>
              <div class="diag-card-status">{{ item.status }}</div>
            </div>
          </div>

          <!-- Services -->
          <div class="diag-section-title" style="margin-top: 24px;">Services</div>
          <div class="diag-cards">
            <div *ngFor="let svc of serviceItems" class="diag-card" [class.up]="svc.status === 'UP'" [class.down]="svc.status !== 'UP'">
              <div class="diag-card-header">
                <div class="diag-card-dot"></div>
                <span class="diag-card-name" [class.diag-card-link]="!publicMode"
                      [title]="publicMode ? '' : 'Click to view logs'"
                      (click)="viewServiceLogs(svc.name)">{{ svc.name }}</span>
              </div>
              <div class="diag-card-components" *ngIf="svc.components">
                <span *ngFor="let comp of svc.components" class="diag-comp-badge" [class.comp-up]="comp.status === 'UP'" [class.comp-down]="comp.status !== 'UP'">
                  {{ comp.name }}: {{ comp.status }}
                </span>
              </div>
              <div class="diag-card-detail" *ngIf="svc.uptime">Uptime: {{ svc.uptime }}</div>
              <div class="diag-card-status">{{ svc.status }}</div>

              <div class="diag-card-actions" *ngIf="!publicMode">
                <button class="diag-action-btn restart" (click)="controlService(svc.name, 'restart')"
                        [disabled]="!!actionInProgress[svc.name]">Restart</button>
                <button class="diag-action-btn stop" (click)="controlService(svc.name, 'stop')"
                        [disabled]="!!actionInProgress[svc.name]">Stop</button>
                <button class="diag-action-btn start" (click)="controlService(svc.name, 'start')"
                        [disabled]="!!actionInProgress[svc.name]">Start</button>
                <span class="diag-action-progress" *ngIf="actionInProgress[svc.name]">{{ actionInProgress[svc.name] }}…</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- SIP / NAT Tab -->
      <div *ngIf="activeTab === 'sip'" class="diag-panel">
        <div *ngIf="loading && !sipData" class="diag-loading">Loading SIP diagnostics...</div>

        <div *ngIf="sipData">
          <!-- Gateway Status -->
          <div class="diag-section-title">Gateway Status (Telnyx)</div>
          <div class="diag-kv-grid" *ngIf="sipData.gatewayStatus?.parsed">
            <div *ngFor="let kv of objectEntries(sipData.gatewayStatus.parsed)" class="diag-kv-row">
              <span class="diag-kv-key">{{ kv[0] }}</span>
              <span class="diag-kv-val" [class.val-highlight]="isHighlightKey(kv[0])">{{ kv[1] }}</span>
            </div>
          </div>
          <div *ngIf="sipData.gatewayStatus?.error" class="diag-error">{{ sipData.gatewayStatus.error }}</div>

          <!-- External Profile -->
          <div class="diag-section-title" style="margin-top: 24px;">External Profile</div>
          <div class="diag-kv-grid" *ngIf="sipData.externalProfile?.parsed">
            <div *ngFor="let kv of objectEntries(sipData.externalProfile.parsed)" class="diag-kv-row">
              <span class="diag-kv-key">{{ kv[0] }}</span>
              <span class="diag-kv-val" [class.val-highlight]="isHighlightKey(kv[0])">{{ kv[1] }}</span>
            </div>
          </div>
          <div *ngIf="sipData.externalProfile?.error" class="diag-error">{{ sipData.externalProfile.error }}</div>

          <!-- Internal Profile -->
          <div class="diag-section-title" style="margin-top: 24px;">Internal Profile</div>
          <div class="diag-kv-grid" *ngIf="sipData.internalProfile?.parsed">
            <div *ngFor="let kv of objectEntries(sipData.internalProfile.parsed)" class="diag-kv-row">
              <span class="diag-kv-key">{{ kv[0] }}</span>
              <span class="diag-kv-val">{{ kv[1] }}</span>
            </div>
          </div>

          <!-- Sofia Status -->
          <div class="diag-section-title" style="margin-top: 24px;">Sofia Profiles</div>
          <div *ngIf="sipData.sofiaStatus?.profiles?.length > 0" class="diag-table-wrap">
            <table class="diag-table">
              <thead><tr><th>Name</th><th>Type</th><th>Data</th><th>State</th></tr></thead>
              <tbody>
                <tr *ngFor="let p of sipData.sofiaStatus.profiles">
                  <td>{{ p.name }}</td><td>{{ p.type }}</td><td>{{ p.data }}</td>
                  <td><span class="diag-state-badge" [class.running]="p.state === 'RUNNING'">{{ p.state }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- Logs Tab -->
      <div *ngIf="activeTab === 'logs'" class="diag-panel">
        <div class="diag-log-controls">
          <select [(ngModel)]="selectedLogService" (change)="onLogParamsChange()" class="diag-select">
            <option value="">-- Select Service --</option>
            <option *ngFor="let f of logFiles" [value]="f.service">{{ f.service }} ({{ formatBytes(f.sizeBytes) }})</option>
          </select>
          <select [(ngModel)]="selectedLogLevel" (change)="onLogParamsChange()" class="diag-select">
            <option value="">{{ liveMode ? 'All levels' : 'ERROR + WARN' }}</option>
            <option value="ERROR">ERROR only</option>
            <option value="WARN">WARN only</option>
            <option value="INFO">INFO</option>
            <option value="DEBUG">DEBUG</option>
          </select>
          <button class="diag-live-btn" [class.on]="liveMode" (click)="toggleLive()" [disabled]="!selectedLogService">
            <span class="diag-live-dot" *ngIf="liveMode"></span>
            {{ liveMode ? 'Live' : 'Go Live' }}
          </button>
        </div>

        <div *ngIf="logEntries.length > 0" class="diag-log-viewer">
          <div *ngFor="let line of logEntries" class="diag-log-line"
               [class.log-error]="line.includes('ERROR')"
               [class.log-warn]="line.includes('WARN')"
               [class.log-info]="line.includes('INFO')">{{ line }}</div>
        </div>
        <div *ngIf="logEntries.length === 0 && selectedLogService" class="diag-empty">
          No matching log entries found.
        </div>
        <div *ngIf="!selectedLogService" class="diag-empty">
          Select a service to view its logs.
        </div>
      </div>
    </div>
  `,
  styles: [`
    .diagnostics-container { min-height: 400px; }

    .diag-tabs {
      display: flex; align-items: center; gap: 4px;
      border-bottom: 1px solid #E2E8F0;
      padding-bottom: 0; margin-bottom: 20px;
    }
    .diag-tab {
      display: flex; align-items: center; gap: 6px;
      padding: 10px 16px; border: none; background: transparent;
      color: #64748B; font-size: 13px; font-weight: 500;
      cursor: pointer; border-bottom: 2px solid transparent;
      transition: all 0.15s;
    }
    .diag-tab:hover { color: #1E293B; }
    .diag-tab.active { color: #4F46E5; border-bottom-color: #4F46E5; }
    .diag-tabs-spacer { flex: 1; }
    .diag-refresh-btn {
      display: flex; align-items: center; gap: 6px;
      padding: 6px 14px; border: 1px solid #E2E8F0;
      background: #F8FAFC; color: #475569;
      font-size: 12px; border-radius: 6px; cursor: pointer;
      transition: all 0.15s;
    }
    .diag-refresh-btn:hover { background: #F1F5F9; }
    .diag-refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .spin { animation: spin 1s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

    .diag-panel { animation: fadeIn 0.2s ease; }
    @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }

    .diag-loading { color: #64748B; text-align: center; padding: 40px; font-size: 14px; }
    .diag-empty { color: #94A3B8; text-align: center; padding: 40px; font-size: 13px; }
    .diag-error { color: #DC2626; font-size: 13px; padding: 8px 12px; background: rgba(220,38,38,0.08); border-radius: 6px; margin-top: 8px; }

    .diag-overall {
      display: flex; align-items: center; gap: 10px;
      padding: 14px 18px; border-radius: 8px; font-size: 14px; font-weight: 600;
      margin-bottom: 24px;
    }
    .diag-overall.up { background: rgba(16,185,129,0.08); color: #059669; border: 1px solid rgba(16,185,129,0.2); }
    .diag-overall.degraded { background: rgba(245,158,11,0.08); color: #D97706; border: 1px solid rgba(245,158,11,0.2); }
    .diag-overall-dot {
      width: 10px; height: 10px; border-radius: 50%;
    }
    .diag-overall.up .diag-overall-dot { background: #10B981; box-shadow: 0 0 8px rgba(16,185,129,0.5); }
    .diag-overall.degraded .diag-overall-dot { background: #F59E0B; box-shadow: 0 0 8px rgba(245,158,11,0.5); }

    .diag-section-title {
      font-size: 12px; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.08em; color: #64748B;
      margin-bottom: 12px;
    }

    .diag-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
    .diag-card {
      background: #FFFFFF; border: 1px solid #E2E8F0;
      border-radius: 8px; padding: 14px 16px; position: relative; overflow: hidden;
      box-shadow: 0 1px 2px rgba(15,23,42,0.04);
    }
    .diag-card.up { border-left: 3px solid #10B981; }
    .diag-card.down { border-left: 3px solid #EF4444; }
    .diag-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
    .diag-card-dot { width: 8px; height: 8px; border-radius: 50%; }
    .diag-card.up .diag-card-dot { background: #10B981; }
    .diag-card.down .diag-card-dot { background: #EF4444; }
    .diag-card-name { font-size: 13px; font-weight: 600; color: #1E293B; }
    .diag-card-link { cursor: pointer; }
    .diag-card-link:hover { color: #4F46E5; text-decoration: underline; }
    .diag-card-detail { font-size: 11px; color: #64748B; margin-top: 4px; }

    .diag-card-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 12px; }
    .diag-action-btn {
      font-size: 11px; font-weight: 600; padding: 4px 10px;
      border-radius: 5px; border: 1px solid transparent; cursor: pointer;
      transition: all 0.15s;
    }
    .diag-action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .diag-action-btn.restart { background: rgba(79,70,229,0.1); color: #4F46E5; border-color: rgba(79,70,229,0.2); }
    .diag-action-btn.restart:hover:not(:disabled) { background: rgba(79,70,229,0.18); }
    .diag-action-btn.stop { background: rgba(239,68,68,0.1); color: #DC2626; border-color: rgba(239,68,68,0.2); }
    .diag-action-btn.stop:hover:not(:disabled) { background: rgba(239,68,68,0.18); }
    .diag-action-btn.start { background: rgba(16,185,129,0.1); color: #059669; border-color: rgba(16,185,129,0.2); }
    .diag-action-btn.start:hover:not(:disabled) { background: rgba(16,185,129,0.18); }
    .diag-action-progress { font-size: 11px; color: #64748B; font-style: italic; }
    .diag-card-status {
      position: absolute; top: 14px; right: 16px;
      font-size: 11px; font-weight: 600;
    }
    .diag-card.up .diag-card-status { color: #059669; }
    .diag-card.down .diag-card-status { color: #DC2626; }

    .diag-card-components { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
    .diag-comp-badge {
      font-size: 10px; padding: 2px 8px; border-radius: 4px; font-weight: 500;
    }
    .comp-up { background: rgba(16,185,129,0.12); color: #059669; }
    .comp-down { background: rgba(239,68,68,0.12); color: #DC2626; }

    .diag-kv-grid {
      background: #FFFFFF; border: 1px solid #E2E8F0;
      border-radius: 8px; overflow: hidden;
    }
    .diag-kv-row {
      display: flex; padding: 8px 16px;
      border-bottom: 1px solid #F1F5F9;
    }
    .diag-kv-row:last-child { border-bottom: none; }
    .diag-kv-key {
      width: 200px; flex-shrink: 0;
      font-size: 12px; color: #64748B; font-weight: 500;
    }
    .diag-kv-val { font-size: 12px; color: #1E293B; font-family: 'SF Mono', monospace; }
    .val-highlight { color: #4F46E5; font-weight: 600; }

    .diag-table-wrap {
      background: #FFFFFF; border: 1px solid #E2E8F0;
      border-radius: 8px; overflow: hidden;
    }
    .diag-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .diag-table th {
      text-align: left; padding: 10px 16px;
      font-weight: 600; color: #64748B;
      text-transform: uppercase; font-size: 11px; letter-spacing: 0.05em;
      border-bottom: 1px solid #E2E8F0;
    }
    .diag-table td { padding: 10px 16px; color: #334155; }
    .diag-table tr:not(:last-child) td { border-bottom: 1px solid #F1F5F9; }
    .diag-state-badge {
      padding: 2px 10px; border-radius: 4px; font-size: 11px; font-weight: 600;
      background: rgba(239,68,68,0.12); color: #DC2626;
    }
    .diag-state-badge.running { background: rgba(16,185,129,0.12); color: #059669; }

    .diag-log-controls { display: flex; gap: 10px; margin-bottom: 16px; }
    .diag-select {
      padding: 8px 12px; background: #FFFFFF;
      border: 1px solid #E2E8F0; border-radius: 6px;
      color: #334155; font-size: 13px;
      cursor: pointer; min-width: 200px;
    }
    .diag-select option { background: #FFFFFF; color: #1E293B; }

    .diag-live-btn {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 8px 14px; font-size: 13px; font-weight: 600;
      border-radius: 6px; cursor: pointer;
      border: 1px solid #E2E8F0; background: #F8FAFC; color: #475569;
      transition: all 0.15s;
    }
    .diag-live-btn:hover:not(:disabled) { background: #F1F5F9; }
    .diag-live-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .diag-live-btn.on {
      background: rgba(16,185,129,0.12); color: #059669; border-color: rgba(16,185,129,0.3);
    }
    .diag-live-dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: #10B981; box-shadow: 0 0 6px rgba(16,185,129,0.7);
      animation: pulse 1.2s ease-in-out infinite;
    }
    @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }

    .diag-log-viewer {
      background: #0d0d14; border: 1px solid rgba(255,255,255,0.06);
      border-radius: 8px; padding: 12px; max-height: 500px; overflow-y: auto;
      font-family: 'SF Mono', 'Fira Code', monospace; font-size: 11px;
    }
    .diag-log-line {
      padding: 2px 0; color: rgba(255,255,255,0.6);
      white-space: pre-wrap; word-break: break-all;
      line-height: 1.5;
    }
    .log-error { color: #f87171; }
    .log-warn { color: #fbbf24; }
    .log-info { color: #60a5fa; }
  `]
})
export class DiagnosticsComponent implements OnInit, OnDestroy {
  @Input() publicMode = false;
  activeTab = 'health';
  loading = false;

  healthData: any = null;
  infraItems: any[] = [];
  serviceItems: any[] = [];

  sipData: any = null;

  logFiles: any[] = [];
  logEntries: string[] = [];
  selectedLogService = '';
  selectedLogLevel = '';

  actionInProgress: Record<string, string> = {};

  liveMode = false;
  private eventSource: EventSource | null = null;
  private readonly MAX_LIVE_LINES = 500;

  private refreshTimer: any;

  constructor(private diagnostics: DiagnosticsService) {}

  ngOnInit() {
    if (this.publicMode) {
      this.activeTab = 'health';
    }
    this.loadHealth();
    this.refreshTimer = setInterval(() => {
      if (this.activeTab === 'health') this.loadHealth();
    }, 10000);
  }

  ngOnDestroy() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    this.stopStream();
  }

  refresh() {
    if (this.activeTab === 'health') this.loadHealth();
    else if (!this.publicMode && this.activeTab === 'sip') this.loadSip();
    else if (!this.publicMode && this.activeTab === 'logs') {
      if (this.liveMode && this.selectedLogService) {
        this.startStream();
      } else {
        this.loadLogs();
      }
    }
  }

  loadHealth() {
    this.loading = true;
    const request$ = this.publicMode
      ? this.diagnostics.getPublicHealth()
      : this.diagnostics.getHealth();
    request$.subscribe({
      next: (data) => {
        this.healthData = data;
        this.infraItems = this.parseInfra(data.infrastructure || {});
        this.serviceItems = this.parseServices(data.services || {});
        this.loading = false;
      },
      error: (err) => {
        console.error('Health check failed', err);
        this.loading = false;
      }
    });
  }

  loadSip() {
    this.loading = true;
    this.diagnostics.getSipDiagnostics().subscribe({
      next: (data) => {
        this.sipData = data;
        this.loading = false;
      },
      error: (err) => {
        console.error('SIP diagnostics failed', err);
        this.loading = false;
      }
    });
  }

  loadLogServices() {
    this.diagnostics.getLogServices().subscribe({
      next: (files) => this.logFiles = files || [],
      error: (err) => console.error('Failed to load log services', err)
    });
  }

  loadLogs() {
    if (!this.selectedLogService) return;
    this.loading = true;
    this.diagnostics.getLogs(this.selectedLogService, this.selectedLogLevel, 200).subscribe({
      next: (data) => {
        this.logEntries = data.entries || [];
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load logs', err);
        this.loading = false;
      }
    });
  }

  onLogParamsChange() {
    if (this.liveMode) {
      if (this.selectedLogService) {
        this.startStream();
      } else {
        this.stopStream();
      }
    } else {
      this.loadLogs();
    }
  }

  toggleLive() {
    if (!this.selectedLogService) return;
    this.liveMode = !this.liveMode;
    if (this.liveMode) {
      this.startStream();
    } else {
      this.stopStream();
    }
  }

  private startStream() {
    this.stopStream();
    this.logEntries = [];
    const url = this.diagnostics.getLogStreamUrl(this.selectedLogService, this.selectedLogLevel);
    const es = new EventSource(url);
    this.eventSource = es;

    es.onmessage = (event) => {
      this.logEntries = [...this.logEntries, event.data].slice(-this.MAX_LIVE_LINES);
    };
    es.addEventListener('error', () => {
      // Browser auto-reconnects on transient errors; nothing else needed here.
    });
  }

  private stopStream() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  controlService(name: string, action: 'stop' | 'start' | 'restart') {
    if (this.publicMode || this.actionInProgress[name]) return;
    this.actionInProgress[name] = action === 'stop' ? 'Stopping' : action === 'start' ? 'Starting' : 'Restarting';

    const req$ = action === 'stop'
      ? this.diagnostics.stopService(name)
      : action === 'start'
        ? this.diagnostics.startService(name)
        : this.diagnostics.restartService(name);

    const settleDelay = action === 'stop' ? 2500 : 9000;

    req$.subscribe({
      next: () => {
        setTimeout(() => {
          delete this.actionInProgress[name];
          this.loadHealth();
        }, settleDelay);
      },
      error: (err) => {
        console.error(`${action} failed for ${name}`, err);
        delete this.actionInProgress[name];
        this.loadHealth();
      }
    });
  }

  viewServiceLogs(name: string) {
    if (this.publicMode) return;
    this.selectedLogService = name;
    this.activeTab = 'logs';
    this.loadLogServices();
    if (this.liveMode) {
      this.startStream();
    } else {
      this.selectedLogLevel = 'INFO';
      this.loadLogs();
    }
  }

  objectEntries(obj: any): [string, string][] {
    return obj ? Object.entries(obj) as [string, string][] : [];
  }

  isHighlightKey(key: string): boolean {
    const highlights = ['Ext-SIP-IP', 'Ext-RTP-IP', 'Status', 'State', 'Calls-In', 'Calls-Out'];
    return highlights.some(h => key.includes(h));
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  private parseInfra(infra: any): any[] {
    return Object.entries(infra).map(([name, val]: [string, any]) => ({
      name: this.formatInfraName(name),
      status: typeof val === 'string' ? val : (val?.status || 'UNKNOWN'),
      endpoint: typeof val === 'string' ? '' : (val?.endpoint || '')
    }));
  }

  private parseServices(services: any): any[] {
    return Object.entries(services).map(([name, val]: [string, any]) => {
      const components = val?.components
        ? Object.entries(val.components).map(([cname, cval]: [string, any]) => ({
            name: cname,
            status: cval?.status || 'UNKNOWN'
          }))
        : null;
      return {
        name,
        status: typeof val === 'string' ? val : (val?.status || 'DOWN'),
        uptime: typeof val === 'string' ? null : (val?.uptime || null),
        components
      };
    });
  }

  private formatInfraName(key: string): string {
    const map: Record<string, string> = {
      'postgres': 'PostgreSQL',
      'redis': 'Redis',
      'kafka': 'Kafka',
      'freeswitch-esl': 'FreeSWITCH ESL'
    };
    return map[key] || key;
  }
}
