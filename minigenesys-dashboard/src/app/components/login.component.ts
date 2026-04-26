import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="login-container" style="display: flex; height: 100vh; align-items: center; justify-content: center; background: var(--bg-main);">
      <div class="card" style="width: 100%; max-width: 400px; padding: 40px;">
        <div style="display: flex; align-items: center; justify-content: center; gap: 12px; margin-bottom: 32px;">
          <div style="width: 40px; height: 40px; background: var(--primary); border-radius: 10px; display: flex; align-items: center; justify-content: center;">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <span style="font-size: 24px; font-weight: 800; letter-spacing: -0.02em;">MiniGenesys</span>
        </div>

        <h1 style="text-align: center; margin-bottom: 24px; font-size: 20px;">Sign In to Workspace</h1>

        <div *ngIf="error" style="padding: 12px; background: var(--danger-soft); color: var(--danger); border-radius: 6px; margin-bottom: 16px; font-size: 14px; text-align: center;">
          {{ error }}
        </div>

        <form (ngSubmit)="onSubmit()" #loginForm="ngForm">
          <div class="form-group" style="margin-bottom: 16px;">
            <label style="display: block; font-size: 13px; font-weight: 500; color: var(--text-muted); margin-bottom: 6px;">Username</label>
            <input type="text" name="username" [(ngModel)]="credentials.username" required 
                   style="width: 100%; padding: 10px 12px; background: rgba(255,255,255,0.05); border: 1px solid var(--border); border-radius: 6px; color: var(--text-main); outline: none;">
          </div>

          <div class="form-group" style="margin-bottom: 24px;">
            <label style="display: block; font-size: 13px; font-weight: 500; color: var(--text-muted); margin-bottom: 6px;">Password</label>
            <input type="password" name="password" [(ngModel)]="credentials.password" required 
                   style="width: 100%; padding: 10px 12px; background: rgba(255,255,255,0.05); border: 1px solid var(--border); border-radius: 6px; color: var(--text-main); outline: none;">
          </div>

          <button type="submit" class="btn btn-primary" style="width: 100%; padding: 12px;" [disabled]="!loginForm.form.valid || loading">
            {{ loading ? 'Authenticating...' : 'Sign In' }}
          </button>
        </form>
      </div>
    </div>
  `
})
export class LoginComponent {
  credentials = { username: '', password: '' };
  error = '';
  loading = false;

  constructor(private api: ApiService) {}

  onSubmit() {
    this.loading = true;
    this.error = '';
    this.api.login(this.credentials).subscribe({
      next: () => {
        window.location.reload();
      },
      error: (err) => {
        this.loading = false;
        this.error = 'Invalid credentials or service unavailable.';
        console.error('Login error', err);
      }
    });
  }
}
