import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-create-agent',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="card" style="margin-bottom: 24px;">
      <div class="card-header">
        <div>
          <div class="card-title">Provision New Agent</div>
          <div class="card-subtitle">Create a new agent account and assign skills</div>
        </div>
      </div>

      <div *ngIf="successMsg" class="alert alert-success" style="margin-bottom: 16px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
        {{ successMsg }}
      </div>
      <div *ngIf="errorMsg" class="alert alert-error" style="margin-bottom: 16px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
        {{ errorMsg }}
      </div>

      <form (ngSubmit)="onSubmit()" #f="ngForm" style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
        <div class="form-group">
          <label class="form-label">Agent ID</label>
          <input class="form-input" type="text" name="agentId" [(ngModel)]="form.agentId" required placeholder="e.g. AG_002">
        </div>
        <div class="form-group">
          <label class="form-label">Display Name</label>
          <input class="form-input" type="text" name="name" [(ngModel)]="form.name" required placeholder="e.g. Sarah Jenkins">
        </div>
        <div class="form-group">
          <label class="form-label">Username</label>
          <input class="form-input" type="text" name="username" [(ngModel)]="form.username" required placeholder="e.g. sarah.jenkins">
        </div>
        <div class="form-group">
          <label class="form-label">Password</label>
          <input class="form-input" type="password" name="password" [(ngModel)]="form.password" required placeholder="••••••••">
        </div>
        <div class="form-group" style="grid-column: 1 / -1;">
          <label class="form-label">Skills (comma separated)</label>
          <input class="form-input" type="text" name="skills" [(ngModel)]="skillsString" required placeholder="e.g. sales, support, billing">
        </div>
        
        <div style="grid-column: 1 / -1; display: flex; justify-content: flex-end; margin-top: 8px;">
          <button type="submit" class="btn btn-primary" [disabled]="!f.valid || loading">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="8.5" cy="7" r="4"></circle><line x1="20" y1="8" x2="20" y2="14"></line><line x1="23" y1="11" x2="17" y2="11"></line></svg>
            {{ loading ? 'Provisioning...' : 'Create Agent' }}
          </button>
        </div>
      </form>
    </div>
  `
})
export class CreateAgentComponent {
  form = {
    username: '',
    password: '',
    agentId: '',
    name: '',
    skills: [] as string[]
  };
  skillsString = 'sales';
  
  loading = false;
  successMsg = '';
  errorMsg = '';

  constructor(private api: ApiService) {}

  onSubmit() {
    this.loading = true;
    this.successMsg = '';
    this.errorMsg = '';
    
    this.form.skills = this.skillsString.split(',').map(s => s.trim()).filter(s => s);
    const tenantId = this.api.tenantId;
    if (!tenantId) {
      this.errorMsg = 'No tenant context found.';
      return;
    }

    this.api.createAgent(tenantId, this.form).subscribe({
      next: () => {
        this.loading = false;
        this.successMsg = 'Agent successfully provisioned!';
        this.form = { username: '', password: '', agentId: '', name: '', skills: [] };
      },
      error: (err) => {
        this.loading = false;
        this.errorMsg = 'Failed to create agent. Username or Agent ID may already exist.';
      }
    });
  }
}
