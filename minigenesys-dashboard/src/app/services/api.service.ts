import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly GATEWAY_URL = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  login(): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/auth/login`, {}).pipe(
      tap((res: any) => {
        if (res.accessToken) {
          localStorage.setItem('token', res.accessToken);
        }
      })
    );
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
  createCall(skills: string[]): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls`, { requiredSkills: skills });
  }

  startCall(callId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls/${callId}/start`, {});
  }

  completeCall(callId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls/${callId}/complete`, {});
  }

  // Analytics API
  getMetrics(tenantId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/analytics/${tenantId}/metrics`);
  }
}
