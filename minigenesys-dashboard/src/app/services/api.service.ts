import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, BehaviorSubject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly GATEWAY_URL = 'http://localhost:8080';
  
  private agentIdSubject = new BehaviorSubject<string>('agent-ui-1');
  agentId$ = this.agentIdSubject.asObservable();

  constructor(private http: HttpClient) {}

  setAgentId(id: string) {
    this.agentIdSubject.next(id);
  }

  get currentAgentId() {
    return this.agentIdSubject.value;
  }

  private isRefreshing = false;

  login(): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/auth/login`, {}).pipe(
      tap((res: any) => {
        if (res.accessToken) {
          localStorage.setItem('token', res.accessToken);
        }
      })
    );
  }

  // Helper to handle 401s globally if needed, or just let components handle it.
  // For now, let's add a robust login check.
  ensureAuth(): Observable<any> {
    const token = localStorage.getItem('token');
    if (!token) return this.login();
    return new Observable(obs => obs.next(token));
  }

  // Agent API
  loginAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/login`, {});
  }

  logoutAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/logout`, {});
  }

  setAgentState(agentId: string, status: string): Observable<any> {
    return this.http.put(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/state`, { status });
  }

  getAgentState(agentId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/state`);
  }

  heartbeatAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/heartbeat`, {});
  }

  // Call API
  createCall(tenantId: string, skill: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls`, { 
      tenantId, 
      requiredSkills: [skill] 
    });
  }

  updateCallStatus(callId: string, status: string): Observable<any> {
    const endpoint = status === 'COMPLETED' ? 'complete' : 'start';
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls/${callId}/${endpoint}`, {});
  }

  // Analytics API
  getMetrics(tenantId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/analytics/${tenantId}/metrics`);
  }
}
