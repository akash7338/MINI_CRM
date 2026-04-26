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
      <h2>Provision New Agent</h2>
      <p style="margin-bottom: 20px;">Create a new agent account and profile.</p>

      <div *ngIf="successMsg" style="padding: 12px; background: var(--success-soft); color: var(--success); border-radius: 6px; margin-bottom: 16px; font-size: 14px;">
        {{ successMsg }}
      </div>
      <div *ngIf="errorMsg" style="padding: 12px; background: var(--danger-soft); color: var(--danger); border-radius: 6px; margin-bottom: 16px; font-size: 14px;">
        {{ errorMsg }}
      </div>

      <form (ngSubmit)="onSubmit()" #f="ngForm" style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
        <div>
          <label style="display: block; font-size: 12px; margin-bottom: 4px; color: var(--text-muted);">Agent ID</label>
          <input type="text" name="agentId" [(ngModel)]="form.agentId" required style="width: 100%; padding: 8px; background: rgba(0,0,0,0.2); border: 1px solid var(--border); border-radius: 4px; color: white;">
        </div>
        <div>
          <label style="display: block; font-size: 12px; margin-bottom: 4px; color: var(--text-muted);">Display Name</label>
          <input type="text" name="name" [(ngModel)]="form.name" required style="width: 100%; padding: 8px; background: rgba(0,0,0,0.2); border: 1px solid var(--border); border-radius: 4px; color: white;">
        </div>
        <div>
          <label style="display: block; font-size: 12px; margin-bottom: 4px; color: var(--text-muted);">Username</label>
          <input type="text" name="username" [(ngModel)]="form.username" required style="width: 100%; padding: 8px; background: rgba(0,0,0,0.2); border: 1px solid var(--border); border-radius: 4px; color: white;">
        </div>
        <div>
          <label style="display: block; font-size: 12px; margin-bottom: 4px; color: var(--text-muted);">Password</label>
          <input type="password" name="password" [(ngModel)]="form.password" required style="width: 100%; padding: 8px; background: rgba(0,0,0,0.2); border: 1px solid var(--border); border-radius: 4px; color: white;">
        </div>
        <div style="grid-column: 1 / -1;">
          <label style="display: block; font-size: 12px; margin-bottom: 4px; color: var(--text-muted);">Skills (comma separated)</label>
          <input type="text" name="skills" [(ngModel)]="skillsString" required style="width: 100%; padding: 8px; background: rgba(0,0,0,0.2); border: 1px solid var(--border); border-radius: 4px; color: white;" placeholder="e.g. sales, support">
        </div>
        
        <div style="grid-column: 1 / -1; display: flex; justify-content: flex-end; margin-top: 8px;">
          <button type="submit" class="btn btn-primary" [disabled]="!f.valid || loading">
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
    const tenantId = localStorage.getItem('tenantId') || 'tenant1';

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
