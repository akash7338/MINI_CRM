import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="login-page">
      <!-- Gradient Background -->
      <div class="login-bg">
        <div class="login-bg-blob login-bg-blob-1"></div>
        <div class="login-bg-blob login-bg-blob-2"></div>
        <div class="login-bg-blob login-bg-blob-3"></div>
      </div>

      <!-- Login Container -->
      <div class="login-container">
        <!-- Brand -->
        <div class="login-brand">
          <div class="login-brand-icon">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <span class="login-brand-text">MiniGenesys</span>
        </div>

        <!-- Card -->
        <div class="login-card">
          <div class="login-card-header">
            <h1 class="login-title">Sign In to Workspace</h1>
            <p class="login-subtitle">Enter your credentials to access the admin dashboard.</p>
          </div>

          <div *ngIf="error" class="alert alert-error" style="margin-bottom: 20px;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
            {{ error }}
          </div>

          <form (ngSubmit)="onSubmit()" #loginForm="ngForm">
            <div class="login-field">
              <label class="login-field-label">Username</label>
              <div class="login-input-wrapper">
                <svg class="login-input-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
                <input class="login-input" type="text" name="username" [(ngModel)]="credentials.username" required placeholder="admin@minigenesys.com">
              </div>
            </div>

            <div class="login-field">
              <label class="login-field-label">Password</label>
              <div class="login-input-wrapper">
                <svg class="login-input-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>
                <input class="login-input" type="password" name="password" [(ngModel)]="credentials.password" required placeholder="••••••••">
              </div>
            </div>

            <div class="login-options">
              <label class="login-checkbox-label">
                <input type="checkbox" class="login-checkbox">
                <span>Remember me</span>
              </label>
              <a class="login-link">Forgot Password?</a>
            </div>

            <button type="submit" class="login-submit-btn" [disabled]="!loginForm.form.valid || loading">
              {{ loading ? 'Authenticating...' : 'Sign In' }}
            </button>
          </form>

          <div class="login-footer">
            <a class="login-link-muted">Contact Support</a>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .login-page {
      position: fixed;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 100;
      overflow: hidden;
    }

    /* ===== Gradient Background ===== */
    .login-bg {
      position: absolute;
      inset: 0;
      background: linear-gradient(135deg, #E8F0FE 0%, #F0F4FA 30%, #FDF2F0 70%, #F8FAFC 100%);
      z-index: 0;
    }

    .login-bg-blob {
      position: absolute;
      border-radius: 50%;
      filter: blur(80px);
      opacity: 0.6;
    }

    .login-bg-blob-1 {
      width: 500px;
      height: 500px;
      background: linear-gradient(135deg, rgba(59, 130, 246, 0.2) 0%, rgba(147, 197, 253, 0.3) 100%);
      top: -100px;
      left: -100px;
      animation: float-blob 8s ease-in-out infinite;
    }

    .login-bg-blob-2 {
      width: 400px;
      height: 400px;
      background: linear-gradient(135deg, rgba(251, 191, 36, 0.12) 0%, rgba(245, 158, 11, 0.15) 100%);
      bottom: -80px;
      right: -60px;
      animation: float-blob 10s ease-in-out infinite reverse;
    }

    .login-bg-blob-3 {
      width: 300px;
      height: 300px;
      background: linear-gradient(135deg, rgba(59, 130, 246, 0.08) 0%, rgba(147, 197, 253, 0.12) 100%);
      top: 40%;
      right: 20%;
      animation: float-blob 12s ease-in-out infinite;
    }

    @keyframes float-blob {
      0%, 100% { transform: translate(0, 0) scale(1); }
      33% { transform: translate(20px, -20px) scale(1.05); }
      66% { transform: translate(-15px, 15px) scale(0.95); }
    }

    /* ===== Container ===== */
    .login-container {
      position: relative;
      z-index: 1;
      width: 100%;
      max-width: 420px;
      padding: 0 20px;
    }

    /* ===== Brand ===== */
    .login-brand {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      margin-bottom: 32px;
    }

    .login-brand-icon {
      width: 38px;
      height: 38px;
      background: linear-gradient(135deg, #3B82F6 0%, #2563EB 100%);
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 4px 14px rgba(59, 130, 246, 0.3);
    }

    .login-brand-text {
      font-size: 22px;
      font-weight: 800;
      letter-spacing: -0.03em;
      color: var(--text-main);
    }

    /* ===== Card ===== */
    .login-card {
      background: rgba(255, 255, 255, 0.75);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border-radius: 16px;
      border: 1px solid rgba(255, 255, 255, 0.6);
      box-shadow:
        0 20px 60px rgba(15, 23, 42, 0.08),
        0 1px 3px rgba(15, 23, 42, 0.04),
        inset 0 1px 0 rgba(255, 255, 255, 0.5);
      padding: 36px 32px;
    }

    .login-card-header {
      text-align: center;
      margin-bottom: 28px;
    }

    .login-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      margin-bottom: 6px;
      letter-spacing: -0.02em;
    }

    .login-subtitle {
      font-size: 14px;
      color: var(--text-muted);
      line-height: 1.5;
    }

    /* ===== Form Fields ===== */
    .login-field {
      margin-bottom: 18px;
    }

    .login-field-label {
      display: block;
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary, #475569);
      margin-bottom: 6px;
    }

    .login-input-wrapper {
      position: relative;
      display: flex;
      align-items: center;
    }

    .login-input-icon {
      position: absolute;
      left: 14px;
      color: #94A3B8;
      pointer-events: none;
      flex-shrink: 0;
    }

    .login-input {
      width: 100%;
      padding: 11px 14px 11px 42px;
      background: rgba(255, 255, 255, 0.8);
      border: 1px solid #E2E8F0;
      border-radius: 10px;
      color: var(--text-main);
      font-family: inherit;
      font-size: 14px;
      transition: border-color 150ms ease, box-shadow 150ms ease;
      outline: none;
    }

    .login-input:focus {
      border-color: #3B82F6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
      background: #FFFFFF;
    }

    .login-input::placeholder {
      color: #94A3B8;
    }

    /* ===== Options Row ===== */
    .login-options {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
    }

    .login-checkbox-label {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--text-muted);
      cursor: pointer;
    }

    .login-checkbox {
      width: 16px;
      height: 16px;
      border-radius: 4px;
      accent-color: #3B82F6;
      cursor: pointer;
    }

    .login-link {
      font-size: 13px;
      color: #3B82F6;
      font-weight: 600;
      cursor: pointer;
      text-decoration: none;
    }

    .login-link:hover {
      color: #2563EB;
      text-decoration: underline;
    }

    /* ===== Submit Button ===== */
    .login-submit-btn {
      width: 100%;
      padding: 12px;
      background: linear-gradient(135deg, #3B82F6 0%, #2563EB 100%);
      color: white;
      font-family: inherit;
      font-size: 15px;
      font-weight: 600;
      border: none;
      border-radius: 10px;
      cursor: pointer;
      transition: all 200ms ease;
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05), inset 0 1px 0 rgba(255, 255, 255, 0.15);
    }

    .login-submit-btn:hover:not(:disabled) {
      background: linear-gradient(135deg, #2563EB 0%, #1D4ED8 100%);
      box-shadow: 0 6px 20px rgba(59, 130, 246, 0.35);
      transform: translateY(-1px);
    }

    .login-submit-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
      transform: none;
      box-shadow: none;
    }

    /* ===== Footer ===== */
    .login-footer {
      text-align: center;
      margin-top: 20px;
      padding-top: 20px;
      border-top: 1px solid rgba(226, 232, 240, 0.6);
    }

    .login-link-muted {
      font-size: 13px;
      color: var(--text-muted);
      cursor: pointer;
      text-decoration: none;
      font-weight: 500;
    }

    .login-link-muted:hover {
      color: #3B82F6;
    }
  `]
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
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = 'Invalid credentials or service unavailable.';
        console.error('Login error', err);
      }
    });
  }
}
